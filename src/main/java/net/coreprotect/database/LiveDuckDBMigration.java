package net.coreprotect.database;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.config.DatabaseConfigWriter;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.EntitySpawnTracking;
import net.coreprotect.utility.ErrorReporter;
import net.coreprotect.utility.serialize.MetadataCompression;

/**
 * Live SQLite to DuckDB migration, started with "co migrate-duckdb" from the console.
 *
 * Phase 1 copies every row up to a recorded high-water mark while the server keeps logging to SQLite, then catches up.
 * Phase 2 pauses logging the same way /co reload does, copies what was written since, switches config.yml to DuckDB
 * and resumes. Events raised during the pause wait in the consumer queue and are written to DuckDB.
 * The source is only read, through the plugin's own SQLite JDBC driver, on a read-only connection.
 *
 * "co migrate-duckdb compact" runs the same phases from the active DuckDB file into a new file next to it, read through
 * a second connection to the plugin's own DuckDB instance (a DuckDB file can only be opened by one process). In the
 * pause it closes the plugin's DuckDB, renames the old file aside, gives the new file the old name and reopens it.
 * config.yml does not change. A DuckDB file never shrinks after DELETE; the new file holds no free space.
 */
public final class LiveDuckDBMigration implements Runnable {

    // These two tables are changed in place by UPDATE statements outside rollback and purge (user: name and uuid,
    // entity_spawn: position, removed flag, kill and block links), so phase 2 copies them whole. database_lock gets a
    // fresh row. Every other table only grows while rollback, restore and purge are blocked, so phase 2 copies only
    // the rows above what phase 1 already copied.
    private static final Set<String> RECOPY_TABLES = Set.of("user", "entity_spawn");
    // With compress-metadata on, these columns are stored through MetadataCompression, like the plugin's own writes.
    private static final Map<String, String> COMPRESSED_COLUMNS = Map.of("container", "metadata", "entity_container", "metadata", "item", "data");
    private static final long SAMPLE_EVERY_ROWID = 1_000L;
    private static final int PAGE_ROWS = 10_000;
    private static final long VERIFY_CHUNK_ROWIDS = 1_000_000L;
    private static final int CATCH_UP_ROUNDS = 5;
    private static final long CATCH_UP_DONE_ROWS = 20_000L;
    private static final long PAUSE_TIMEOUT_MILLIS = 60_000L;
    private static final long PROGRESS_INTERVAL_NANOS = 5_000_000_000L;
    private static final String USAGE = "/co migrate-duckdb [compact] [--memory 256MB] [--threads 2] [--rows-per-second 100000] | /co migrate-duckdb cancel";

    private static volatile LiveDuckDBMigration running;

    private final String memoryLimit;
    private final int threads;
    private final long rowsPerSecond;
    private final String testFail;
    private final long testHoldPauseMillis;
    private final String prefix = ConfigHandler.prefix;
    private final boolean compact;
    private final File sourceFile;
    private final File target;
    File oldFile; // compact: where the previous file was renamed to
    private final List<String> tables = new ArrayList<>();
    private final Map<String, List<Column>> plans = new HashMap<>();
    final Map<String, Long> startHighWater = new HashMap<>();
    final Map<String, Long> copiedThrough = new HashMap<>();
    // Table name to the position of its compressed column in plans, only while compression is on.
    private final Map<String, Integer> compressedColumn = new HashMap<>();
    // Table name to byte lengths of its compressed column: read and stored for row IDs up to startHighWater, then
    // read and stored above it. Those are the ranges the phase 1 and phase 2 verify check.
    private final Map<String, long[]> blobBytes = new HashMap<>();
    private final boolean compress = Config.getGlobal().COMPRESS_METADATA;
    final ExecutorService compressor;
    DuckDBConnection duck;
    private volatile boolean cancelled;
    private volatile boolean paused;
    // Set once the plugin may have left its previous database: config.yml was changed, or its DuckDB file was closed.
    private boolean switching;
    private boolean switchedBack;
    private long throttleStart;
    private long throttleRows;

    LiveDuckDBMigration(boolean compact, String memoryLimit, int threads, long rowsPerSecond, String testFail, long testHoldPauseMillis) {
        this.compact = compact;
        this.sourceFile = new File(ConfigHandler.path, compact ? ConfigHandler.duckdb : ConfigHandler.sqlite);
        this.target = targetFile(compact);
        this.memoryLimit = memoryLimit;
        this.threads = threads;
        this.rowsPerSecond = rowsPerSecond;
        this.testFail = testFail;
        this.testHoldPauseMillis = testHoldPauseMillis;
        for (String table : Database.DATABASE_TABLES) {
            if (!table.equals("database_lock")) {
                tables.add(table);
            }
        }
        // Not the common ForkJoinPool: other plugins use it. The pool starts its threads on first use.
        compressor = compress ? Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "CoreProtect-MigrateDuckDB-Compress");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    public static void runCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            reply(sender, "migrate-duckdb can only be run from the server console.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            LiveDuckDBMigration migration = running;
            if (migration == null) {
                reply(sender, "No migration is running.");
            }
            else if (migration.paused) {
                reply(sender, "The migration is in its final pause and can no longer be cancelled.");
            }
            else {
                migration.cancelled = true;
                reply(sender, "Cancelling the migration. " + migration.stays());
            }
            return;
        }

        boolean compact = args.length > 1 && args[1].equalsIgnoreCase("compact");
        String memoryLimit = "256MB";
        int threads = 2;
        long rowsPerSecond = 100_000L;
        String testFail = null;
        long testHoldPauseMillis = 0L;
        try {
            for (int i = compact ? 2 : 1; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + args[i]);
                }
                String value = args[i + 1];
                switch (args[i].toLowerCase(Locale.ROOT)) {
                    case "--memory":
                        memoryLimit = value;
                        break;
                    case "--threads":
                        threads = Integer.parseInt(value);
                        break;
                    case "--rows-per-second":
                        rowsPerSecond = Long.parseLong(value);
                        break;
                    case "--test-fail": // copy, pause, close and rename (compact only), switch, switch-error or switch-back: fails at that point, for testing the recovery paths
                        testFail = value.toLowerCase(Locale.ROOT);
                        break;
                    case "--test-hold-pause": // milliseconds: keeps the final pause open longer, for testing
                        testHoldPauseMillis = Long.parseLong(value);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (threads < 1 || rowsPerSecond < 0) {
                throw new IllegalArgumentException("threads must be at least 1 and rows-per-second 0 (no limit) or more");
            }
        }
        catch (IllegalArgumentException e) {
            reply(sender, e.getMessage() + ". Usage: " + USAGE);
            return;
        }

        if (compact ? !ConfigHandler.databaseType.isDuckDB() : !ConfigHandler.databaseType.isSQLite()) {
            reply(sender, "The active database is " + ConfigHandler.databaseType.getDisplayName() + ". " + (compact ? "Only a DuckDB database can be compacted." : "This command only migrates from SQLite."));
            return;
        }
        File target = targetFile(compact);
        if (target.exists() || new File(target.getPath() + ".wal").exists()) {
            reply(sender, target.getPath() + " (or its .wal) already exists. Move it away first.");
            return;
        }
        Consumer.OperationStartResult claim = Consumer.claimMigration();
        if (claim != Consumer.OperationStartResult.STARTED) {
            reply(sender, "Cannot start now: " + describe(claim) + ".");
            return;
        }

        LiveDuckDBMigration migration = new LiveDuckDBMigration(compact, memoryLimit, threads, rowsPerSecond, testFail, testHoldPauseMillis);
        running = migration;
        try {
            Thread thread = new Thread(migration, "CoreProtect-MigrateDuckDB");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
        catch (RuntimeException | Error e) {
            running = null;
            Consumer.releaseMigration();
            throw e;
        }
        reply(sender, (compact ? "Compaction of the DuckDB file" : "Migration to DuckDB") + " started. Rollback, restore, purge and reload are blocked until it ends. Progress is in the server log.");
    }

    static File targetFile(boolean compact) {
        return new File(ConfigHandler.path, ConfigHandler.duckdb + (compact ? ".compact" : ""));
    }

    private String sourceName() {
        return compact ? "source" : "SQLite";
    }

    private String stays() {
        return compact ? "The current DuckDB file stays active." : "SQLite stays active.";
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        boolean switched = false;
        try (Connection source = openSource()) {
            phaseOne(source);
            switched = phaseTwo(source);
            if (compact) {
                log("Done in " + seconds(System.nanoTime() - started) + ". CoreProtect is logging to the compacted " + sourceFile.getName() + " (" + megabytes(sourceFile.length()) + " MB). The old file is kept as " + oldFile.getName() + " (" + megabytes(oldFile.length()) + " MB); delete it once you no longer need it.");
            }
            else {
                log("Done in " + seconds(System.nanoTime() - started) + ". CoreProtect is now logging to DuckDB. The SQLite file was not changed and can be kept as a backup.");
            }
        }
        catch (CancellationException e) {
            log("Cancelled. " + stays());
        }
        catch (Throwable e) {
            boolean onOld = !switching || switchedBack;
            error("Failed: " + e + (onOld ? ". " + stays() : ". See the error above about switching back."));
            ErrorReporter.report(e);
        }
        finally {
            if (compressor != null) {
                compressor.shutdownNow();
            }
            closeTarget();
            // Only move the file when the plugin is known to be on its previous database: if switching back failed,
            // config.yml may still name this file, or it may be the active DuckDB file.
            if (!switched && (!switching || switchedBack)) {
                moveTargetAside();
            }
            running = null;
            Consumer.releaseMigration();
        }
    }

    void phaseOne(Connection source) throws Exception {
        log("Source " + sourceFile.getPath() + " (" + megabytes(sourceFile.length()) + " MB), free disk " + megabytes(target.getAbsoluteFile().getParentFile().getUsableSpace()) + " MB, DuckDB memory " + memoryLimit + ", threads " + threads + ", " + (rowsPerSecond > 0 ? "at most " + String.format(Locale.ROOT, "%,d", rowsPerSecond) + " rows/s" : "no rate limit") + ".");
        if (compact) {
            log("Compacting the active DuckDB file into " + target.getName() + ". The source is read through the plugin's own DuckDB connection; --memory and --threads apply to the new file.");
        }
        log(compress ? "compress-metadata is on: container and entity_container metadata and item data are stored compressed, on " + threads + " compression threads." : "compress-metadata is off: blobs are copied as they are.");
        Set<String> sourceTables = checkSource(source);
        for (String table : tables) {
            long highWater = sourceTables.contains(prefix + table) ? maxRowId(source, table, 0L) : 0L;
            if (!table.equals("block") && highWater > Integer.MAX_VALUE) {
                throw new IllegalStateException(prefix + table + " has row ID " + highWater + ", which does not fit DuckDB's INTEGER row ID column");
            }
            startHighWater.put(table, highWater);
            copiedThrough.put(table, 0L);
        }

        duck = DuckDBDatabase.openFile(target, memoryLimit, threads);
        createSchema();
        planColumns(source, sourceTables);

        long copyStart = System.nanoTime();
        throttleStart = copyStart;
        long rows = 0;
        for (String table : tables) {
            if (!RECOPY_TABLES.contains(table)) {
                rows += copy(source, table, 0L, startHighWater.get(table));
            }
        }
        long copyNanos = System.nanoTime() - copyStart;
        log("Phase 1 copied " + count(rows) + " rows in " + seconds(copyNanos) + " (" + count(rate(rows, copyNanos)) + " rows/s).");
        testFail("copy");

        long verifyStart = System.nanoTime();
        for (String table : tables) {
            if (!RECOPY_TABLES.contains(table)) {
                verify(source, table, 0L, startHighWater.get(table));
            }
        }
        log("Phase 1 verified in " + seconds(System.nanoTime() - verifyStart) + ".");
        for (String table : tables) {
            long[] bytes = blobBytes.get(table);
            if (bytes != null && bytes[1] > 0) {
                log(prefix + table + "." + COMPRESSED_COLUMNS.get(table) + ": " + count(bytes[0]) + " bytes read, " + count(bytes[1]) + " bytes stored (" + String.format(Locale.ROOT, "%.2f", (double) bytes[0] / bytes[1]) + "x smaller).");
            }
        }

        // Catch up with rows written during the copy, so the final pause only has a few seconds of rows left.
        for (int round = 1; round <= CATCH_UP_ROUNDS; round++) {
            long caught = 0;
            for (String table : tables) {
                if (plans.containsKey(table) && !RECOPY_TABLES.contains(table)) {
                    caught += copy(source, table, copiedThrough.get(table), maxRowId(source, table, copiedThrough.get(table)));
                }
            }
            log("Catch-up round " + round + ": " + count(caught) + " new rows.");
            if (caught < CATCH_UP_DONE_ROWS) {
                break;
            }
        }
    }

    private boolean phaseTwo(Connection source) throws Exception {
        paused = true;
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY); // logging waits on this thread now; phase 1 ran at the lowest priority
        Consumer.OperationStartResult start = Consumer.beginDatabaseReload();
        if (start != Consumer.OperationStartResult.STARTED) {
            throw new IllegalStateException("could not pause logging: " + describe(start));
        }
        long pauseStart = System.nanoTime();
        boolean resume = true;
        try {
            if (!Consumer.lockDatabaseReload(PAUSE_TIMEOUT_MILLIS) || !Database.awaitConnectionDrain(Math.max(0L, PAUSE_TIMEOUT_MILLIS - (System.nanoTime() - pauseStart) / 1_000_000L))) {
                throw new IllegalStateException("timed out waiting for queued writes and open connections to finish");
            }
            long drainNanos = System.nanoTime() - pauseStart;
            if (testHoldPauseMillis > 0) {
                log("Test option: holding the pause for " + testHoldPauseMillis + " ms.");
                Thread.sleep(testHoldPauseMillis);
            }

            long rows = 0;
            Map<String, Long> finalRowIds = new HashMap<>();
            for (String table : tables) {
                boolean whole = RECOPY_TABLES.contains(table);
                long highWater = plans.containsKey(table) ? maxRowId(source, table, whole ? 0L : copiedThrough.get(table)) : 0L;
                finalRowIds.put(table, highWater);
                rows += copy(source, table, whole ? 0L : copiedThrough.get(table), highWater);
                verify(source, table, whole ? 0L : startHighWater.get(table), highWater);
            }
            if (compact) {
                rows += copySpatialIndex(source);
            }
            try (Statement statement = duck.createStatement()) {
                for (String table : tables) {
                    // Sequences were created at the phase 1 high-water mark + 1; move them past the rows copied since.
                    // A live DuckDB sequence can be further ahead: the plugin reserves block row IDs in batches.
                    long last = compact ? Math.max(finalRowIds.get(table), sequenceValue(source, table)) : finalRowIds.get(table);
                    long steps = last - startHighWater.get(table);
                    if (steps > 0) {
                        statement.executeQuery("SELECT max(nextval('" + prefix + table + "_rowid_seq')) FROM range(" + steps + ")").close();
                    }
                }
                testFail("pause");
                statement.executeUpdate("UPDATE " + prefix + "database_lock SET status=" + Database.DATABASE_LOCK_INACTIVE + ",time=" + (System.currentTimeMillis() / 1000L) + " WHERE rowid=1");
                statement.execute("CHECKPOINT");
            }
            closeTarget();
            long copyNanos = System.nanoTime() - pauseStart - drainNanos;
            log("Phase 2 copied and verified " + count(rows) + " rows in " + seconds(copyNanos) + " (waited " + seconds(drainNanos) + " for queued writes).");

            if (compact) {
                swapFiles(source);
            }
            else {
                DatabaseConfigWriter.persistDatabaseType(DatabaseType.DUCKDB);
                switching = true;
            }
            testFail("switch");
            testFail("switch-back");
            // Keep the in-memory ids: the new file holds the same rows, and ids whose map insert is still queued must not be handed out again.
            if (!ConfigHandler.performInitialization(false, false) || !ConfigHandler.databaseType.isDuckDB()) {
                throw new IllegalStateException(compact ? "the compacted file did not open" : "DuckDB did not initialize after the switch");
            }
            if ("switch-error".equals(testFail)) {
                throw new Error("test error requested after DuckDB was opened");
            }
            resetDatabaseState();
            return true;
        }
        catch (Throwable e) {
            if (switching) {
                switchedBack = compact ? reopenOldFile() : switchBackToSqlite();
                resume = switchedBack;
            }
            throw e;
        }
        finally {
            Consumer.endDatabaseReload(resume);
            paused = false;
            log("Logging was paused for " + (System.nanoTime() - pauseStart) / 1_000_000L + " ms" + (resume ? "." : " and stays paused (see the error above)."));
        }
    }

    private boolean switchBackToSqlite() {
        try {
            testFail("switch-back");
            DatabaseConfigWriter.persistDatabaseType(DatabaseType.SQLITE);
            if (ConfigHandler.performInitialization(false, false) && ConfigHandler.databaseType.isSQLite()) {
                resetDatabaseState();
                log("Switched back to SQLite.");
                return true;
            }
        }
        catch (Throwable e) {
            ErrorReporter.report(e);
        }
        // Same rule as a failed /co reload: keep the queue in memory rather than write to a database that did not open.
        error("Could not switch back to SQLite. Logging stays paused and new events are kept in memory. Set database-type: sqlite in config.yml and run /co reload.");
        return false;
    }

    /**
     * Compact: puts the new file in place of the old one while logging is paused. Every connection to the old file
     * must be closed first. duckdb_jdbc keeps one instance per file while any connection to it is open and hands that
     * instance out again, and on Linux the rename would succeed while the plugin kept writing to the renamed file.
     */
    void swapFiles(Connection source) throws Exception {
        source.close();
        switching = true;
        Database.closeConnection();
        checkReleased(sourceFile);
        testFail("close");
        oldFile = new File(sourceFile.getPath() + ".old-" + stamp());
        move(sourceFile, oldFile);
        log("Closed " + sourceFile.getName() + " and renamed it to " + oldFile.getName() + ".");
        testFail("rename");
        move(target, sourceFile);
        log("Renamed " + target.getName() + " to " + sourceFile.getName() + ".");
    }

    private boolean reopenOldFile() {
        try {
            testFail("switch-back");
            Database.closeConnection(); // the compacted file, if it was opened
            if (oldFile != null && oldFile.exists()) {
                if (sourceFile.exists()) {
                    checkReleased(sourceFile);
                    move(sourceFile, target);
                }
                move(oldFile, sourceFile);
                log("Renamed " + oldFile.getName() + " back to " + sourceFile.getName() + ".");
            }
            if (ConfigHandler.performInitialization(false, false) && ConfigHandler.databaseType.isDuckDB()) {
                resetDatabaseState();
                log("Reopened the old DuckDB file.");
                return true;
            }
        }
        catch (Throwable e) {
            ErrorReporter.report(e);
        }
        error("Could not reopen the old DuckDB file. Logging stays paused and new events are kept in memory. " + (oldFile != null && oldFile.exists() ? "The old file is " + oldFile.getName() + ". Move " + sourceFile.getName() + " away, rename the old file back to " + sourceFile.getName() : "Check " + sourceFile.getName()) + " and run /co reload.");
        return false;
    }

    private static void resetDatabaseState() {
        EntitySpawnTracking.invalidateDatabaseVerification();
        DuckDBRecovery.reset();
        DuckDBSpatialIndex.reset(); // also done when DuckDB closes and opens; keyed by URL, which a compaction keeps
    }

    // A connection with other settings to a file that is still open in this process is refused, so this read-only
    // open only works once the plugin's instance of the file is really closed.
    static void checkReleased(File file) {
        Properties properties = new Properties();
        properties.setProperty("access_mode", "READ_ONLY");
        try (Connection probe = DriverManager.getConnection("jdbc:duckdb:" + file.getAbsolutePath(), properties)) {
            probe.getAutoCommit();
        }
        catch (SQLException e) {
            throw new IllegalStateException(file.getName() + " is still open in this process, so it is not renamed (" + e.getMessage() + ")", e);
        }
    }

    // Renames a DuckDB file and its .wal, if it has one. Never replaces an existing file.
    static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath());
        File wal = new File(from.getPath() + ".wal");
        if (wal.exists()) {
            Files.move(wal.toPath(), new File(to.getPath() + ".wal").toPath());
        }
    }

    Connection openSource() throws Exception {
        if (compact) {
            // Not through Database.getConnection: that registers the connection, and the pause would wait for it.
            return DuckDBDatabase.getConnection();
        }
        Properties properties = new Properties();
        properties.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        return DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getPath(), properties);
    }

    private Set<String> checkSource(Connection source) throws SQLException {
        Set<String> sourceTables = new HashSet<>();
        try (Statement statement = source.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(compact ? "SELECT table_name FROM duckdb_tables() WHERE schema_name='main'" : "SELECT name FROM sqlite_master WHERE type='table'")) {
                while (resultSet.next()) {
                    sourceTables.add(resultSet.getString(1));
                }
            }
            if (compact) {
                return sourceTables; // the plugin made this file: no old schema, and DuckDB already enforces UNIQUE(kill_rowid)
            }
            try (ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode")) {
                if (resultSet.next() && !"wal".equalsIgnoreCase(resultSet.getString(1))) {
                    log("Warning: SQLite is not in WAL mode, so each read briefly holds back the logging thread.");
                }
            }
            if (sourceTables.contains(prefix + "version")) {
                try (ResultSet resultSet = statement.executeQuery("SELECT version FROM " + prefix + "version ORDER BY rowid DESC LIMIT 1")) {
                    if (resultSet.next() && compareVersion(resultSet.getString(1), "2.23.0") < 0) {
                        throw new IllegalStateException("source schema version " + resultSet.getString(1) + " is older than 2.23.0");
                    }
                }
            }
            if (sourceTables.contains(prefix + "entity_spawn")) {
                try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM (SELECT kill_rowid FROM " + prefix + "entity_spawn WHERE kill_rowid IS NOT NULL GROUP BY kill_rowid HAVING count(*) > 1)")) {
                    if (resultSet.next() && resultSet.getLong(1) > 0) {
                        throw new IllegalStateException(prefix + "entity_spawn has " + resultSet.getLong(1) + " duplicate kill_rowid values (DuckDB has a UNIQUE constraint on it)");
                    }
                }
            }
        }
        return sourceTables;
    }

    private void createSchema() throws Exception {
        try (Statement statement = duck.createStatement()) {
            // createTables uses CREATE SEQUENCE IF NOT EXISTS ... START 1, so these keep their start value.
            // DuckDB cannot restart a sequence or drop one a column default depends on, so the start is set here.
            for (String table : tables) {
                statement.execute("CREATE SEQUENCE " + prefix + table + "_rowid_seq START " + (startHighWater.get(table) + 1L));
            }
        }
        DuckDBDatabase.createTables(prefix, duck, true); // the plugin's own DDL; true skips its lock row, written below
        try (Statement statement = duck.createStatement()) {
            // "Migration incomplete" until phase 2 finishes: the plugin refuses to open a file left behind by a crash.
            statement.executeUpdate("INSERT INTO " + prefix + "database_lock (rowid,status,time) VALUES (1," + Database.DATABASE_LOCK_MIGRATION_INCOMPLETE + "," + (System.currentTimeMillis() / 1000L) + ")");
        }
    }

    private void planColumns(Connection source, Set<String> sourceTables) throws SQLException {
        String columnQuery = "SELECT column_name, data_type, column_default FROM duckdb_columns() WHERE schema_name='main' AND table_name=? ORDER BY column_index";
        try (PreparedStatement targetColumns = duck.prepareStatement(columnQuery); Statement statement = source.createStatement()) {
            for (String table : tables) {
                if (!sourceTables.contains(prefix + table)) {
                    log(prefix + table + " is not in the source, its DuckDB table stays empty.");
                    continue;
                }
                Set<String> present = new HashSet<>();
                try (ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + prefix + table + ")")) {
                    while (resultSet.next()) {
                        present.add(resultSet.getString("name").toLowerCase(Locale.ROOT));
                    }
                }
                List<Column> columns = new ArrayList<>();
                int index = 0;
                targetColumns.setString(1, prefix + table);
                try (ResultSet resultSet = targetColumns.executeQuery()) {
                    while (resultSet.next()) {
                        String name = resultSet.getString(1);
                        boolean inSource = name.equals("rowid") || present.contains(name.toLowerCase(Locale.ROOT));
                        if (!inSource) {
                            log(prefix + table + "." + name + " is not in the source, DuckDB uses its default.");
                        }
                        columns.add(new Column(name, resultSet.getString(2), inSource ? ++index : 0, resultSet.getString(3) != null));
                    }
                }
                if (columns.isEmpty() || !columns.get(0).name.equals("rowid")) {
                    throw new IllegalStateException("unexpected DuckDB layout for " + prefix + table);
                }
                for (int i = 0; compress && i < columns.size(); i++) {
                    Column column = columns.get(i);
                    if (column.name.equals(COMPRESSED_COLUMNS.get(table)) && column.kind == Column.BLOB && column.index > 0) {
                        compressedColumn.put(table, i);
                    }
                }
                plans.put(table, columns);
            }
        }
    }

    long copy(Connection source, String table, long from, long to) throws Exception {
        List<Column> columns = plans.get(table);
        if (columns == null || to <= from) {
            return 0L;
        }
        StringBuilder select = new StringBuilder();
        for (Column column : columns) {
            if (column.index > 0) {
                select.append(select.length() == 0 ? "" : ",").append(column.sql());
            }
        }
        // Pages are windows of row IDs, not LIMIT pages: DuckDB has no index on rowid, so "rowid>? ORDER BY rowid LIMIT n"
        // sorts the whole rest of the table for every page (650 ms per page on 5 million rows), while a bounded window
        // only reads the row groups it covers. SQLite reads either form through its rowid key.
        String range = " FROM " + prefix + table + " WHERE rowid>? AND rowid<=?";
        String sql = "SELECT " + select + range + " ORDER BY rowid";
        Integer blob = compressedColumn.get(table);
        long last = from;
        long rows = 0;
        long tableStart = System.nanoTime();
        long nextProgress = tableStart + PROGRESS_INTERVAL_NANOS;
        try (PreparedStatement statement = source.prepareStatement(sql); PreparedStatement next = source.prepareStatement("SELECT min(rowid)" + range); DuckDBAppender appender = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, prefix + table)) {
            while (last < to) {
                checkStop();
                long end = Math.min(to, last + PAGE_ROWS);
                statement.setLong(1, last);
                statement.setLong(2, end);
                List<Object[]> page = new ArrayList<>(PAGE_ROWS);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Object[] row = new Object[columns.size()];
                        for (int i = 0; i < row.length; i++) {
                            row[i] = columns.get(i).read(resultSet);
                        }
                        page.add(row);
                    }
                }
                last = end;
                if (page.isEmpty()) {
                    // Jump over a gap, for example the old rows a purge removed.
                    next.setLong(1, end);
                    next.setLong(2, to);
                    try (ResultSet resultSet = next.executeQuery()) {
                        last = resultSet.next() && resultSet.getObject(1) != null ? resultSet.getLong(1) - 1 : to;
                    }
                    continue;
                }
                if (blob != null) {
                    long[] sums = compressPage(compressor, threads, page, blob, startHighWater.get(table));
                    long[] total = blobBytes.computeIfAbsent(table, key -> new long[4]);
                    for (int i = 0; i < total.length; i++) {
                        total[i] += sums[i];
                    }
                }
                // The appender is only ever used from this thread.
                for (Object[] row : page) {
                    appender.beginRow();
                    for (int i = 0; i < row.length; i++) {
                        columns.get(i).append(row[i], appender);
                    }
                    appender.endRow();
                }
                appender.flush();
                int pageRows = page.size();
                rows += pageRows;
                throttle(pageRows);
                long now = System.nanoTime();
                if (now >= nextProgress) {
                    nextProgress = now + PROGRESS_INTERVAL_NANOS;
                    double done = (double) (last - from) / (to - from);
                    long rowRate = rate(rows, now - tableStart);
                    long left = rowRate > 0 ? (long) (rows * (1 - done) / done / rowRate) : 0;
                    log(prefix + table + ": " + count(rows) + " rows, " + (int) (done * 100) + "% of row IDs, " + count(rowRate) + " rows/s, about " + left + " s left.");
                }
            }
        }
        copiedThrough.put(table, to);
        if (rows >= PAGE_ROWS) {
            long nanos = System.nanoTime() - tableStart;
            log(prefix + table + ": " + count(rows) + " rows in " + seconds(nanos) + " (" + count(rate(rows, nanos)) + " rows/s).");
        }
        return rows;
    }

    /**
     * Compresses one column of a page in place, in contiguous slices on the executor. Returns the byte lengths
     * {read, stored} for row IDs up to split, then {read, stored} above it; row[0] is the row ID. Every compressed
     * value whose row ID is a multiple of 1,000 is decompressed again and must give back the original bytes.
     */
    static long[] compressPage(ExecutorService executor, int threads, List<Object[]> page, int column, long split) throws Exception {
        int slices = Math.max(1, Math.min(threads, page.size()));
        List<Future<long[]>> parts = new ArrayList<>(slices);
        for (int slice = 0; slice < slices; slice++) {
            int start = page.size() * slice / slices;
            int end = page.size() * (slice + 1) / slices;
            parts.add(executor.submit(() -> compressRows(page, column, split, start, end)));
        }
        long[] sums = new long[4];
        try {
            for (Future<long[]> part : parts) {
                long[] partSums = part.get();
                for (int i = 0; i < sums.length; i++) {
                    sums[i] += partSums[i];
                }
            }
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Error) {
                throw (Error) e.getCause();
            }
            throw (Exception) e.getCause();
        }
        return sums;
    }

    private static long[] compressRows(List<Object[]> page, int column, long split, int start, int end) {
        long[] sums = new long[4];
        for (int i = start; i < end; i++) {
            Object[] row = page.get(i);
            byte[] original = (byte[]) row[column];
            if (original == null) {
                continue;
            }
            byte[] stored = MetadataCompression.compress(original);
            long rowId = (Long) row[0];
            // Values that were already compressed in the source come back unchanged and are not checked.
            if (stored != original && rowId % SAMPLE_EVERY_ROWID == 0 && !Arrays.equals(original, MetadataCompression.decompress(stored))) {
                throw new IllegalStateException("the compressed value of row ID " + rowId + " does not decompress to the original bytes");
            }
            row[column] = stored;
            int bucket = rowId <= split ? 0 : 2;
            sums[bucket] += original.length;
            sums[bucket + 1] += stored.length;
        }
        return sums;
    }

    /**
     * Same check as loader/sqlite_to_duckdb.py: row count, min and max row ID, and per column the sum (numbers) or the
     * byte length sum (text and blobs), over the row ID range (from, to]. Both sides are read in row ID chunks so no read
     * transaction stays open for long and no single query sums a whole large table.
     * A compressed column cannot be compared directly: its source sum must match the bytes the copy read, and its
     * DuckDB sum the bytes the copy stored, for the same range.
     */
    void verify(Connection source, String table, long from, long to) throws SQLException {
        List<Column> columns = plans.get(table);
        if (columns == null || to <= from) {
            return;
        }
        List<String> names = new ArrayList<>(List.of("count", "min rowid", "max rowid"));
        StringBuilder sourceSql = new StringBuilder("SELECT count(*),min(rowid),max(rowid)");
        StringBuilder duckSql = new StringBuilder("SELECT count(*),min(rowid),max(rowid)");
        List<Boolean> approximate = new ArrayList<>(List.of(false, false, false));
        Integer blob = compressedColumn.get(table);
        int blobSum = -1;
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            if (column.index <= 1) {
                continue;
            }
            if (blob != null && blob == c) {
                blobSum = names.size();
            }
            names.add(column.name);
            if (column.kind == Column.VARCHAR || column.kind == Column.BLOB) {
                String duckLength = ",sum(" + (column.kind == Column.VARCHAR ? "strlen" : "octet_length") + "(" + column.sql() + "))";
                sourceSql.append(compact ? duckLength : ",sum(length(CAST(" + column.sql() + " AS BLOB)))");
                duckSql.append(duckLength);
            }
            else {
                sourceSql.append(",sum(").append(column.sql()).append(')');
                duckSql.append(",sum(").append(column.sql()).append(')');
            }
            approximate.add(column.kind == Column.DOUBLE || column.kind == Column.FLOAT);
        }
        String range = " FROM " + prefix + table + " WHERE rowid>? AND rowid<=?";
        BigDecimal[] expected = sums(source, sourceSql + range, range, from, to, names.size());
        BigDecimal[] actual = sums(duck, duckSql + range, range, from, to, names.size());
        String failed = "verify failed for " + prefix + table + " row IDs " + from + " to " + to + ", ";
        for (int i = 0; i < expected.length; i++) {
            BigDecimal want = expected[i];
            if (i == blobSum) {
                long[] bytes = blobBytes.getOrDefault(table, new long[4]);
                int bucket = from < startHighWater.get(table) ? 0 : 2;
                if (!same(expected[i], BigDecimal.valueOf(bytes[bucket]), false)) {
                    throw new IllegalStateException(failed + names.get(i) + " bytes: " + sourceName() + " " + expected[i] + ", read by the copy " + bytes[bucket]);
                }
                want = BigDecimal.valueOf(bytes[bucket + 1]);
            }
            if (!same(want, actual[i], approximate.get(i))) {
                throw new IllegalStateException(failed + names.get(i) + ": " + (i == blobSum ? "stored by the copy" : sourceName()) + " " + want + ", " + (compact ? "compacted " : "DuckDB ") + actual[i]);
            }
        }
    }

    // Count, min and max row ID and sums of one verify query over (from, to], added up over row ID chunks.
    private static BigDecimal[] sums(Connection connection, String sql, String range, long from, long to, int size) throws SQLException {
        BigDecimal[] result = new BigDecimal[size];
        long chunkStart = to;
        try (PreparedStatement first = connection.prepareStatement("SELECT min(rowid)" + range)) {
            first.setLong(1, from);
            first.setLong(2, to);
            try (ResultSet resultSet = first.executeQuery()) {
                if (resultSet.next() && resultSet.getObject(1) != null) {
                    chunkStart = resultSet.getLong(1) - 1;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            while (chunkStart < to) {
                long chunkEnd = Math.min(to, chunkStart + VERIFY_CHUNK_ROWIDS);
                statement.setLong(1, chunkStart);
                statement.setLong(2, chunkEnd);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    for (int i = 0; i < result.length; i++) {
                        BigDecimal value = decimal(resultSet.getObject(i + 1));
                        if (value == null) {
                            continue;
                        }
                        if (result[i] == null) {
                            result[i] = value;
                        }
                        else if (i == 1) {
                            result[i] = result[i].min(value);
                        }
                        else if (i == 2) {
                            result[i] = result[i].max(value);
                        }
                        else {
                            result[i] = result[i].add(value);
                        }
                    }
                }
                chunkStart = chunkEnd;
            }
        }
        return result;
    }

    // Highest row ID, or "above" when no row is above it. DuckDB then only reads the row groups above that row ID.
    private long maxRowId(Connection source, String table, long above) throws SQLException {
        try (PreparedStatement statement = source.prepareStatement("SELECT max(rowid) FROM " + prefix + table + " WHERE rowid>?")) {
            statement.setLong(1, above);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getObject(1) != null ? resultSet.getLong(1) : above;
            }
        }
    }

    long sequenceValue(Connection source, String table) throws SQLException {
        try (PreparedStatement statement = source.prepareStatement("SELECT last_value FROM duckdb_sequences() WHERE schema_name='main' AND sequence_name=?")) {
            statement.setString(1, prefix + table + "_rowid_seq");
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L; // NULL (never used) reads as 0
            }
        }
    }

    // Compact: the index segments point at row IDs, which the copy keeps, so the new file can use them as they are
    // instead of building the index again from every row after the switch.
    long copySpatialIndex(Connection source) throws SQLException {
        String table = prefix + "duckdb_spatial_index";
        long rows = 0;
        try (Statement statement = source.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT table_id,start_rowid,end_rowid,row_count,chunks,entities FROM " + table + " ORDER BY table_id,start_rowid"); DuckDBAppender appender = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, table)) {
            while (resultSet.next()) {
                appender.beginRow();
                appender.append(resultSet.getByte(1));
                appender.append(resultSet.getLong(2));
                appender.append(resultSet.getLong(3));
                appender.append(resultSet.getInt(4));
                appender.append(resultSet.getBytes(5));
                byte[] entities = resultSet.getBytes(6);
                if (entities == null) {
                    appender.appendNull();
                }
                else {
                    appender.append(entities);
                }
                appender.endRow();
                rows++;
            }
        }
        String check = "SELECT count(*),sum(table_id),sum(start_rowid),sum(end_rowid),sum(row_count),sum(octet_length(chunks)),sum(octet_length(entities)) FROM " + table;
        String[] expected = firstRow(source, check);
        String[] actual = firstRow(duck, check);
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("verify failed for " + table + ": source " + Arrays.toString(expected) + ", compacted " + Arrays.toString(actual));
        }
        log("Copied " + count(rows) + " " + table + " rows.");
        return rows;
    }

    private static String[] firstRow(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            String[] values = new String[resultSet.getMetaData().getColumnCount()];
            for (int i = 0; i < values.length; i++) {
                values[i] = resultSet.getString(i + 1);
            }
            return values;
        }
    }

    private void throttle(long rows) throws InterruptedException {
        if (rowsPerSecond <= 0 || paused) {
            return;
        }
        throttleRows += rows;
        long wait = throttleStart + throttleRows * 1_000_000_000L / rowsPerSecond - System.nanoTime();
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
    }

    private void checkStop() {
        if (cancelled) {
            throw new CancellationException();
        }
        if (ConfigHandler.shutdownDrainRunning || !ConfigHandler.serverRunning) {
            throw new IllegalStateException("the server is stopping");
        }
        // The source connection keeps the plugin's DuckDB instance alive, so a recovery could not restart it.
        if (compact && DuckDBRecovery.isPending()) {
            throw new IllegalStateException("DuckDB asked for a restart of its database, the compaction stops to let it happen");
        }
    }

    private void testFail(String point) {
        if (point.equals(testFail)) {
            throw new IllegalStateException("test failure requested at '" + point + "'");
        }
    }

    private void closeTarget() {
        if (duck == null) {
            return;
        }
        try {
            duck.close();
        }
        catch (SQLException e) {
            ErrorReporter.report(e);
        }
        duck = null;
    }

    private void moveTargetAside() {
        String stamp = stamp();
        for (String suffix : new String[] { "", ".wal" }) {
            File file = new File(target.getPath() + suffix);
            if (!file.exists()) {
                continue;
            }
            File moved = new File(target.getPath() + ".failed-" + stamp + suffix);
            if (file.renameTo(moved)) {
                log("Moved the unfinished file " + file.getName() + " to " + moved.getName() + ".");
            }
            else {
                error("Could not rename " + file.getPath() + ". Move it away before the next attempt.");
            }
        }
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static boolean same(BigDecimal expected, BigDecimal actual, boolean approximate) {
        if (expected == null || actual == null) {
            return (expected == null || expected.signum() == 0) && (actual == null || actual.signum() == 0);
        }
        if (approximate) {
            BigDecimal tolerance = expected.abs().multiply(new BigDecimal("0.000001")).max(BigDecimal.ONE);
            return expected.subtract(actual).abs().compareTo(tolerance) <= 0;
        }
        return expected.compareTo(actual) == 0;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static int compareVersion(String version, String other) {
        String[] left = version.split("\\.");
        String[] right = other.split("\\.");
        for (int i = 0; i < 3; i++) {
            int a = i < left.length ? Integer.parseInt(left[i].replaceAll("\\D", "")) : 0;
            int b = i < right.length ? Integer.parseInt(right[i].replaceAll("\\D", "")) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static String describe(Consumer.OperationStartResult result) {
        switch (result) {
            case PURGE_RUNNING:
                return "a purge is running";
            case ROLLBACK_RUNNING:
                return "a rollback or restore is running or still saving its results";
            case RELOAD_RUNNING:
                return "a reload or another migration is running";
            case PERSISTENCE_HALTED:
                return "database writes are halted after an error";
            default:
                return "the server is stopping";
        }
    }

    private static long rate(long rows, long nanos) {
        return nanos <= 0 ? 0 : rows * 1_000_000_000L / nanos;
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
    }

    private static long megabytes(long bytes) {
        return bytes / 1_048_576L;
    }

    private static void log(String message) {
        Chat.console("migrate-duckdb: " + message);
    }

    private static void error(String message) {
        Chat.sendConsoleMessage(Color.RED + "[CoreProtect] migrate-duckdb: " + message);
    }

    private static void reply(CommandSender sender, String message) {
        Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + message);
    }

    private static final class Column {
        static final int BIGINT = 0;
        static final int INTEGER = 1;
        static final int TINYINT = 2;
        static final int DOUBLE = 3;
        static final int FLOAT = 4;
        static final int VARCHAR = 5;
        static final int BLOB = 6;
        private static final List<String> TYPES = List.of("BIGINT", "INTEGER", "TINYINT", "DOUBLE", "FLOAT", "VARCHAR", "BLOB");

        final String name;
        final int kind;
        final int index; // position in the SQLite result set, 0 when the source has no such column
        final boolean hasDefault;

        Column(String name, String type, int index, boolean hasDefault) {
            this.name = name;
            this.kind = TYPES.indexOf(type);
            this.index = index;
            this.hasDefault = hasDefault;
            if (kind < 0) {
                throw new IllegalStateException("unsupported DuckDB column type " + type + " for " + name);
            }
        }

        String sql() {
            return name.equals("rowid") ? "rowid" : "\"" + name + "\"";
        }

        // String, byte[], Double or Long, or null for SQL NULL and for a column the source does not have.
        Object read(ResultSet resultSet) throws SQLException {
            if (index == 0) {
                return null;
            }
            if (kind == VARCHAR) {
                return resultSet.getString(index);
            }
            if (kind == BLOB) {
                return resultSet.getBytes(index);
            }
            if (kind == DOUBLE || kind == FLOAT) {
                double value = resultSet.getDouble(index);
                return resultSet.wasNull() ? null : (Object) value;
            }
            long value = resultSet.getLong(index);
            return resultSet.wasNull() ? null : (Object) value;
        }

        void append(Object value, DuckDBAppender appender) throws SQLException {
            if (index == 0) {
                if (hasDefault) {
                    appender.appendDefault();
                }
                else {
                    appender.appendNull();
                }
                return;
            }
            if (value == null) {
                appender.appendNull();
            }
            else if (kind == VARCHAR) {
                appender.append((String) value);
            }
            else if (kind == BLOB) {
                appender.append((byte[]) value);
            }
            else if (kind == DOUBLE) {
                appender.append((double) (Double) value);
            }
            else if (kind == FLOAT) {
                appender.append((float) (double) (Double) value);
            }
            else {
                long number = (Long) value;
                if (kind == BIGINT) {
                    appender.append(number);
                }
                else if (kind == INTEGER && number == (int) number) {
                    appender.append((int) number);
                }
                else if (kind == TINYINT && number == (byte) number) {
                    appender.append((byte) number);
                }
                else {
                    throw new IllegalStateException("value " + number + " does not fit " + TYPES.get(kind) + " column " + name);
                }
            }
        }
    }
}
