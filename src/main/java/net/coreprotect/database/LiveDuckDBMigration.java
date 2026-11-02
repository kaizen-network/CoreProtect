package net.coreprotect.database;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.config.DatabaseConfigWriter;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.EntitySpawnTracking;
import net.coreprotect.utility.ErrorReporter;

/**
 * Live SQLite to DuckDB migration, started with "co migrate-duckdb" from the console.
 *
 * Phase 1 copies every row up to a recorded high-water mark while the server keeps logging to SQLite, then catches up.
 * Phase 2 pauses logging the same way /co reload does, copies what was written since, switches config.yml to DuckDB
 * and resumes. Events raised during the pause wait in the consumer queue and are written to DuckDB.
 * The source is only read, through the plugin's own SQLite JDBC driver, on a read-only connection.
 */
public final class LiveDuckDBMigration implements Runnable {

    // These two tables are changed in place by UPDATE statements outside rollback and purge (user: name and uuid,
    // entity_spawn: position, removed flag, kill and block links), so phase 2 copies them whole. database_lock gets a
    // fresh row. Every other table only grows while rollback, restore and purge are blocked, so phase 2 copies only
    // the rows above what phase 1 already copied.
    private static final Set<String> RECOPY_TABLES = Set.of("user", "entity_spawn");
    private static final int PAGE_ROWS = 10_000;
    private static final long VERIFY_CHUNK_ROWIDS = 1_000_000L;
    private static final int CATCH_UP_ROUNDS = 5;
    private static final long CATCH_UP_DONE_ROWS = 20_000L;
    private static final long PAUSE_TIMEOUT_MILLIS = 60_000L;
    private static final long PROGRESS_INTERVAL_NANOS = 5_000_000_000L;
    private static final String USAGE = "/co migrate-duckdb [--memory 256MB] [--threads 2] [--rows-per-second 100000] | /co migrate-duckdb cancel";

    private static volatile LiveDuckDBMigration running;

    private final String memoryLimit;
    private final int threads;
    private final long rowsPerSecond;
    private final String testFail;
    private final long testHoldPauseMillis;
    private final String prefix = ConfigHandler.prefix;
    private final File source = new File(ConfigHandler.path, ConfigHandler.sqlite);
    private final File target = new File(ConfigHandler.path, ConfigHandler.duckdb);
    private final List<String> tables = new ArrayList<>();
    private final Map<String, List<Column>> plans = new HashMap<>();
    private final Map<String, Long> startHighWater = new HashMap<>();
    private final Map<String, Long> copiedThrough = new HashMap<>();
    private DuckDBConnection duck;
    private volatile boolean cancelled;
    private volatile boolean paused;
    private boolean configWritten;
    private boolean switchedBack;
    private long throttleStart;
    private long throttleRows;

    private LiveDuckDBMigration(String memoryLimit, int threads, long rowsPerSecond, String testFail, long testHoldPauseMillis) {
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
                reply(sender, "Cancelling the migration. SQLite stays active.");
            }
            return;
        }

        String memoryLimit = "256MB";
        int threads = 2;
        long rowsPerSecond = 100_000L;
        String testFail = null;
        long testHoldPauseMillis = 0L;
        try {
            for (int i = 1; i < args.length; i += 2) {
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
                    case "--test-fail": // copy, pause, switch, switch-error or switch-back: fails at that point, for testing the recovery paths
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

        if (!ConfigHandler.databaseType.isSQLite()) {
            reply(sender, "The active database is " + ConfigHandler.databaseType.getDisplayName() + ". This command only migrates from SQLite.");
            return;
        }
        File target = new File(ConfigHandler.path, ConfigHandler.duckdb);
        if (target.exists() || new File(target.getPath() + ".wal").exists()) {
            reply(sender, target.getPath() + " (or its .wal) already exists. Move it away first.");
            return;
        }
        Consumer.OperationStartResult claim = Consumer.claimMigration();
        if (claim != Consumer.OperationStartResult.STARTED) {
            reply(sender, "Cannot start now: " + describe(claim) + ".");
            return;
        }

        LiveDuckDBMigration migration = new LiveDuckDBMigration(memoryLimit, threads, rowsPerSecond, testFail, testHoldPauseMillis);
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
        reply(sender, "Migration to DuckDB started. Rollback, restore, purge and reload are blocked until it ends. Progress is in the server log.");
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        boolean switched = false;
        try (Connection sqlite = openSource()) {
            phaseOne(sqlite);
            switched = phaseTwo(sqlite);
            log("Done in " + seconds(System.nanoTime() - started) + ". CoreProtect is now logging to DuckDB. The SQLite file was not changed and can be kept as a backup.");
        }
        catch (CancellationException e) {
            log("Cancelled. SQLite stays active.");
        }
        catch (Throwable e) {
            boolean onSqlite = !configWritten || switchedBack;
            error("Failed: " + e + (onSqlite ? ". SQLite stays active." : ". See the error above about switching back."));
            ErrorReporter.report(e);
        }
        finally {
            closeTarget();
            // Only move the file when the plugin is known to be on SQLite: if switching back failed, config.yml may
            // still name this file.
            if (!switched && (!configWritten || switchedBack)) {
                moveTargetAside();
            }
            running = null;
            Consumer.releaseMigration();
        }
    }

    private void phaseOne(Connection sqlite) throws Exception {
        log("Source " + source.getPath() + " (" + megabytes(source.length()) + " MB), free disk " + megabytes(target.getAbsoluteFile().getParentFile().getUsableSpace()) + " MB, DuckDB memory " + memoryLimit + ", threads " + threads + ", " + (rowsPerSecond > 0 ? "at most " + String.format(Locale.ROOT, "%,d", rowsPerSecond) + " rows/s" : "no rate limit") + ".");
        Set<String> sourceTables = checkSource(sqlite);
        for (String table : tables) {
            long highWater = sourceTables.contains(prefix + table) ? maxRowId(sqlite, table) : 0L;
            if (!table.equals("block") && highWater > Integer.MAX_VALUE) {
                throw new IllegalStateException(prefix + table + " has row ID " + highWater + ", which does not fit DuckDB's INTEGER row ID column");
            }
            startHighWater.put(table, highWater);
            copiedThrough.put(table, 0L);
        }

        duck = DuckDBDatabase.openFile(target, memoryLimit, threads);
        createSchema();
        planColumns(sqlite, sourceTables);

        long copyStart = System.nanoTime();
        throttleStart = copyStart;
        long rows = 0;
        for (String table : tables) {
            if (!RECOPY_TABLES.contains(table)) {
                rows += copy(sqlite, table, 0L, startHighWater.get(table));
            }
        }
        long copyNanos = System.nanoTime() - copyStart;
        log("Phase 1 copied " + count(rows) + " rows in " + seconds(copyNanos) + " (" + count(rate(rows, copyNanos)) + " rows/s).");
        testFail("copy");

        long verifyStart = System.nanoTime();
        for (String table : tables) {
            if (!RECOPY_TABLES.contains(table)) {
                verify(sqlite, table, 0L, startHighWater.get(table));
            }
        }
        log("Phase 1 verified in " + seconds(System.nanoTime() - verifyStart) + ".");

        // Catch up with rows written during the copy, so the final pause only has a few seconds of rows left.
        for (int round = 1; round <= CATCH_UP_ROUNDS; round++) {
            long caught = 0;
            for (String table : tables) {
                if (plans.containsKey(table) && !RECOPY_TABLES.contains(table)) {
                    caught += copy(sqlite, table, copiedThrough.get(table), maxRowId(sqlite, table));
                }
            }
            log("Catch-up round " + round + ": " + count(caught) + " new rows.");
            if (caught < CATCH_UP_DONE_ROWS) {
                break;
            }
        }
    }

    private boolean phaseTwo(Connection sqlite) throws Exception {
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
                long highWater = plans.containsKey(table) ? maxRowId(sqlite, table) : 0L;
                finalRowIds.put(table, highWater);
                boolean whole = RECOPY_TABLES.contains(table);
                rows += copy(sqlite, table, whole ? 0L : copiedThrough.get(table), highWater);
                verify(sqlite, table, whole ? 0L : startHighWater.get(table), highWater);
            }
            try (Statement statement = duck.createStatement()) {
                for (String table : tables) {
                    // Sequences were created at the phase 1 high-water mark + 1; move them past the rows copied since.
                    long steps = finalRowIds.get(table) - startHighWater.get(table);
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

            DatabaseConfigWriter.persistDatabaseType(DatabaseType.DUCKDB);
            configWritten = true;
            testFail("switch");
            testFail("switch-back");
            // Keep the in-memory ids: the new file holds the same rows, and ids whose map insert is still queued must not be handed out again.
            if (!ConfigHandler.performInitialization(false, false) || !ConfigHandler.databaseType.isDuckDB()) {
                throw new IllegalStateException("DuckDB did not initialize after the switch");
            }
            if ("switch-error".equals(testFail)) {
                throw new Error("test error requested after DuckDB was opened");
            }
            EntitySpawnTracking.invalidateDatabaseVerification();
            DuckDBRecovery.reset();
            return true;
        }
        catch (Throwable e) {
            if (configWritten) {
                switchedBack = switchBackToSqlite();
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
                EntitySpawnTracking.invalidateDatabaseVerification();
                DuckDBRecovery.reset();
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

    private Connection openSource() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        return DriverManager.getConnection("jdbc:sqlite:" + source.getPath(), properties);
    }

    private Set<String> checkSource(Connection sqlite) throws SQLException {
        Set<String> sourceTables = new HashSet<>();
        try (Statement statement = sqlite.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (resultSet.next()) {
                    sourceTables.add(resultSet.getString(1));
                }
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

    private void planColumns(Connection sqlite, Set<String> sourceTables) throws SQLException {
        String columnQuery = "SELECT column_name, data_type, column_default FROM duckdb_columns() WHERE schema_name='main' AND table_name=? ORDER BY column_index";
        try (PreparedStatement targetColumns = duck.prepareStatement(columnQuery); Statement statement = sqlite.createStatement()) {
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
                plans.put(table, columns);
            }
        }
    }

    private long copy(Connection sqlite, String table, long from, long to) throws Exception {
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
        String sql = "SELECT " + select + " FROM " + prefix + table + " WHERE rowid>? AND rowid<=? ORDER BY rowid LIMIT " + PAGE_ROWS;
        long last = from;
        long rows = 0;
        long tableStart = System.nanoTime();
        long nextProgress = tableStart + PROGRESS_INTERVAL_NANOS;
        try (PreparedStatement statement = sqlite.prepareStatement(sql); DuckDBAppender appender = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, prefix + table)) {
            while (last < to) {
                checkStop();
                statement.setLong(1, last);
                statement.setLong(2, to);
                int pageRows = 0;
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        appender.beginRow();
                        for (Column column : columns) {
                            column.append(resultSet, appender);
                        }
                        appender.endRow();
                        last = resultSet.getLong(1);
                        pageRows++;
                    }
                }
                if (pageRows == 0) {
                    break;
                }
                appender.flush();
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
     * Same check as loader/sqlite_to_duckdb.py: row count, min and max row ID, and per column the sum (numbers) or the
     * byte length sum (text and blobs), over the row ID range (from, to]. SQLite is read in row ID chunks so no read
     * transaction stays open for long.
     */
    private void verify(Connection sqlite, String table, long from, long to) throws SQLException {
        List<Column> columns = plans.get(table);
        if (columns == null || to <= from) {
            return;
        }
        List<String> names = new ArrayList<>(List.of("count", "min rowid", "max rowid"));
        StringBuilder sqliteSql = new StringBuilder("SELECT count(*),min(rowid),max(rowid)");
        StringBuilder duckSql = new StringBuilder("SELECT count(*),min(rowid),max(rowid)");
        List<Boolean> approximate = new ArrayList<>(List.of(false, false, false));
        for (Column column : columns) {
            if (column.index <= 1) {
                continue;
            }
            names.add(column.name);
            if (column.kind == Column.VARCHAR || column.kind == Column.BLOB) {
                sqliteSql.append(",sum(length(CAST(").append(column.sql()).append(" AS BLOB)))");
                duckSql.append(",sum(").append(column.kind == Column.VARCHAR ? "strlen" : "octet_length").append('(').append(column.sql()).append("))");
            }
            else {
                sqliteSql.append(",sum(").append(column.sql()).append(')');
                duckSql.append(",sum(").append(column.sql()).append(')');
            }
            approximate.add(column.kind == Column.DOUBLE || column.kind == Column.FLOAT);
        }
        String range = " FROM " + prefix + table + " WHERE rowid>? AND rowid<=?";

        BigDecimal[] expected = new BigDecimal[names.size()];
        long chunkStart = from;
        try (PreparedStatement first = sqlite.prepareStatement("SELECT min(rowid)" + range)) {
            first.setLong(1, from);
            first.setLong(2, to);
            try (ResultSet resultSet = first.executeQuery()) {
                if (resultSet.next() && resultSet.getObject(1) != null) {
                    chunkStart = resultSet.getLong(1) - 1;
                }
                else {
                    chunkStart = to;
                }
            }
        }
        try (PreparedStatement statement = sqlite.prepareStatement(sqliteSql + range)) {
            while (chunkStart < to) {
                long chunkEnd = Math.min(to, chunkStart + VERIFY_CHUNK_ROWIDS);
                statement.setLong(1, chunkStart);
                statement.setLong(2, chunkEnd);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    for (int i = 0; i < expected.length; i++) {
                        BigDecimal value = decimal(resultSet.getObject(i + 1));
                        if (value == null) {
                            continue;
                        }
                        if (expected[i] == null) {
                            expected[i] = value;
                        }
                        else if (i == 1) {
                            expected[i] = expected[i].min(value);
                        }
                        else if (i == 2) {
                            expected[i] = expected[i].max(value);
                        }
                        else {
                            expected[i] = expected[i].add(value);
                        }
                    }
                }
                chunkStart = chunkEnd;
            }
        }

        try (PreparedStatement statement = duck.prepareStatement(duckSql + range)) {
            statement.setLong(1, from);
            statement.setLong(2, to);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                for (int i = 0; i < expected.length; i++) {
                    BigDecimal actual = decimal(resultSet.getObject(i + 1));
                    if (!same(expected[i], actual, approximate.get(i))) {
                        throw new IllegalStateException("verify failed for " + prefix + table + " row IDs " + from + " to " + to + ", " + names.get(i) + ": SQLite " + expected[i] + ", DuckDB " + actual);
                    }
                }
            }
        }
    }

    private long maxRowId(Connection sqlite, String table) throws SQLException {
        try (Statement statement = sqlite.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT max(rowid) FROM " + prefix + table)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
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
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        for (String suffix : new String[] { "", ".wal" }) {
            File file = new File(target.getPath() + suffix);
            if (!file.exists()) {
                continue;
            }
            File moved = new File(target.getPath() + ".failed-" + stamp + suffix);
            if (file.renameTo(moved)) {
                log("Moved the unfinished DuckDB file to " + moved.getName() + ".");
            }
            else {
                error("Could not rename " + file.getPath() + ". Move it away before the next attempt.");
            }
        }
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

        void append(ResultSet resultSet, DuckDBAppender appender) throws SQLException {
            if (index == 0) {
                if (hasDefault) {
                    appender.appendDefault();
                }
                else {
                    appender.appendNull();
                }
                return;
            }
            if (kind == VARCHAR) {
                String value = resultSet.getString(index);
                if (value == null) {
                    appender.appendNull();
                }
                else {
                    appender.append(value);
                }
                return;
            }
            if (kind == BLOB) {
                byte[] value = resultSet.getBytes(index);
                if (value == null) {
                    appender.appendNull();
                }
                else {
                    appender.append(value);
                }
                return;
            }
            if (kind == DOUBLE || kind == FLOAT) {
                double value = resultSet.getDouble(index);
                if (resultSet.wasNull()) {
                    appender.appendNull();
                }
                else if (kind == DOUBLE) {
                    appender.append(value);
                }
                else {
                    appender.append((float) value);
                }
                return;
            }
            long value = resultSet.getLong(index);
            if (resultSet.wasNull()) {
                appender.appendNull();
            }
            else if (kind == BIGINT) {
                appender.append(value);
            }
            else if (kind == INTEGER && value == (int) value) {
                appender.append((int) value);
            }
            else if (kind == TINYINT && value == (byte) value) {
                appender.append((byte) value);
            }
            else {
                throw new IllegalStateException("value " + value + " does not fit " + TYPES.get(kind) + " column " + name);
            }
        }
    }
}
