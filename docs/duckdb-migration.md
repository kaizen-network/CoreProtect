# Live SQLite to DuckDB migration

`co migrate-duckdb` copies a running server's CoreProtect SQLite database into a new DuckDB file and then switches the plugin to it. The server keeps running and keeps logging the whole time. Logging to the database is paused once, for about one to two seconds, at the very end. Events from that pause wait in memory and are written to DuckDB.

The code is mainly in `src/main/java/net/coreprotect/database/LiveDuckDBMigration.java`. The test tools are in `lab/` (see [lab/README.md](../lab/README.md)), and the SQLite vs DuckDB comparison is in [lab/RESULTS.md](../lab/RESULTS.md).

## Running it on a real server

1. Make a backup of `plugins/CoreProtect/database.db` (plus `database.db-wal` and `database.db-shm` if they exist) while the server is stopped.
2. Install the fork build. On its first boot it patches the SQLite file to schema 2.25.1 (adds `co_sign.rolled_back` and the three entity tables). This is one-way, which is why the backup comes first.
3. Check free disk space. The DuckDB file was about 12% of the SQLite file in the tests (120 MB for a 1,009 MB SQLite file). Keep at least a quarter of the SQLite size free, plus room for the backup.
4. From the server console (or RCON), run `co migrate-duckdb`. Players cannot run it.
5. Watch the server log. Every line starts with `migrate-duckdb:`. It prints progress every 5 seconds per table (rows, percent, rate, time left), then "Done", "Failed" or "Cancelled".

When it says "Done", `config.yml` already says `database-type: duckdb` and the plugin is logging to DuckDB. No restart is needed. The SQLite file is left as it was at the switch and can stay as a backup.

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--memory 256MB` | 256MB | DuckDB memory cap for the copy. DuckDB spills to `database.duckdb.tmp` above it. |
| `--threads 2` | 2 | DuckDB threads for the copy, and threads for compressing item metadata when `compress-metadata` is on. |
| `--rows-per-second N` | 100000 | Speed cap for phase 1. `0` means no cap. |
| `co migrate-duckdb cancel` | | Stops the copy during phase 1. SQLite stays active. |
| `co migrate-duckdb compact [options]` | | Copies the active DuckDB file into a new one and swaps them (see "Compressed item metadata and compaction"). |

`--test-fail copy|pause|close|rename|switch|switch-error|switch-back` and `--test-hold-pause <ms>` exist only for testing the failure paths (`close` and `rename` apply to `compact`). `--test-no-reopen` (no value) skips reopening the new file before the phase 1 verify, for comparison runs. Do not use them on a real server.

The command refuses to start when the active database is not SQLite, when `database.duckdb` or `database.duckdb.wal` already exists, or while a purge, rollback, restore, reload or another migration is running.

## What happens

Phase 1 runs on a background thread, while the server logs to SQLite as usual. The thread asks for the lowest priority, which only matters on Windows: on Linux the JVM ignores Java thread priorities by default, so there the `--rows-per-second` cap is the only brake.

- It notes the highest row ID of every table and creates the DuckDB file with the plugin's own table definitions.
- It copies every table up to that row ID in row ID order, 10,000 rows per page, at most `--rows-per-second`.
- It verifies the copy the same way `lab/loader/sqlite_to_duckdb.py` does: row count, lowest and highest row ID, and per column the sum (numbers) or the total byte length (text and blobs).
- It then copies the rows written meanwhile, in up to 5 rounds, until fewer than 20,000 new rows are left.

Phase 2 is the short pause. It uses the same steps as `/co reload`: stop the logging thread, wait for queued writes and open connections to finish, then:

- copy the rows written since the last round, and copy `co_user` and `co_entity_spawn` whole (see below),
- verify them, move every row ID sequence past the copied rows, mark the file complete,
- write `database-type: duckdb` into `config.yml` (other settings and comments stay), open DuckDB while keeping the world, material, block data, art and entity ids already in memory (see below), resume logging.

The source is only read, through the plugin's own SQLite driver, on a read-only connection. DuckDB's sqlite extension is never used inside the server (it would load a second SQLite library into the process). Rows are written with DuckDB's Appender.

## Which tables are copied how

Every UPDATE and DELETE in the plugin was checked. Rollback, restore and purge are blocked during the migration, so their changes cannot happen. What is left:

| Table | How | Why |
| --- | --- | --- |
| `co_user` | copied whole in the pause | Name and UUID of existing rows are updated when a player logs in under a new name. |
| `co_entity_spawn` | copied whole in the pause | Position, removed flag, kill link and block link of existing rows are updated all the time. |
| `co_database_lock` | not copied, new row | Only a heartbeat. The new file gets its own lock row. |
| all other tables | phase 1, then only new rows | Rows are only added. The only in-place change is `rolled_back`, which comes from rollback and restore. |

The "all other tables" group is `block`, `container`, `item`, `sign`, `entity_container`, `entity_interaction`, `chat`, `command`, `session`, `username_log`, `skull`, `entity`, `world`, `material_map`, `blockdata_map`, `entity_map`, `art_map` and `version`.

`co_duckdb_spatial_index` is left empty. The plugin builds it from the tables on its first write after the switch.

## Design notes

Row ID sequences: DuckDB 1.4.5 cannot restart a sequence, and it also refuses to drop one that a column default used, even after `DROP DEFAULT` (tested: "Cannot drop entry ... because there are entries that depend on it"). So the migrator creates each sequence first, starting at the phase 1 high-water mark + 1, and then runs the plugin's own `createTables`. Its `CREATE SEQUENCE IF NOT EXISTS ... START 1` keeps the existing sequences. At the end each sequence is moved forward with `nextval` for the rows copied after phase 1 (1 million steps take 63 ms). The schema of a migrated file matches a file the plugin created itself (`lab/migrate/schema_diff.py`: columns, constraints, sequences without start values).

Unfinished files: during the copy the new file's lock row has status 2, the plugin's own "migration incomplete" marker. It is set to 0 just before the switch. A file left behind by a crash can therefore never be opened by accident: the plugin refuses it with "Database contains an incomplete CoreProtect migration and cannot be activated" (tested).

Identifier ids: CoreProtect hands out world, material, block data, art and entity ids in memory and queues the matching `*_map` insert. At the pause such an insert can still be in the queue, so the id is in neither file. `/co reload` reloads these ids from the database, which would hand the same id out again for a new value; the old value's queued insert then wins and the new value's rows read back as the old one (reproduced with the earlier build: 51 of 1,200 rows read back as another material, and the log showed "Error while validating material cache"). The migrator therefore switches with a new `ConfigHandler.performInitialization(startup, false)`, which keeps the ids in memory. The new file holds the same rows as SQLite, and the queued inserts land in it after the switch. The same applies when it switches back to SQLite.

Blocking: the migration takes the existing `ConfigHandler.migrationRunning` flag through a new `Consumer.claimMigration()`, which refuses while a rollback still has `rolled_back` updates queued. While it is set, rollback, restore, undo and apply answer "Database busy" (they all use the same check), an API rollback returns no result, and purge and `/co reload` answer "Upgrade in progress". A server stop during phase 1 ends the migration within a page.

## What is blocked, and for how long

| | Phase 1 (minutes) | Pause (1 to 2 s) |
| --- | --- | --- |
| Logging events | works, to SQLite | waits in memory, then goes to DuckDB |
| Lookups, inspector | work, on SQLite | get no database connection, as during `/co reload` |
| Rollback, restore, undo | refused | refused |
| Purge, `/co reload` | refused | refused |

In the test, an API lookup during the pause returned 0 rows instead of an error, so other plugins using the API see empty results for that second.

After the switch, the first DuckDB write builds the spatial index from all copied rows. That took 6.3 to 6.7 s for 12.6 million rows. The server keeps running, but new events wait in memory for that long before they are stored. After a restart the index loads from disk in 85 ms.

## Failures

Any failure before `config.yml` is changed leaves SQLite active and untouched, resumes logging, unblocks rollback and purge, and renames the unfinished file to `database.duckdb.failed-<date>-<time>`. If opening DuckDB fails after `config.yml` was changed, including with a Java `Error` such as running out of memory, the migrator writes `database-type: sqlite` back and reopens SQLite. If even that fails, logging stays paused with a red console message (the same rule as a failed `/co reload`, so queued events are not lost), `database.duckdb` is left where it is because `config.yml` may still name it, and the fix is to set `database-type: sqlite` and run `/co reload` (tested, see below).

`database.duckdb.failed-*` files are not meant to be used. Delete them once you know why the migration failed.

## Going back to SQLite after a successful switch

1. Stop the server.
2. Set `database-type: sqlite` in `config.yml`.
3. Move `database.duckdb` (and `database.duckdb.wal` if present) out of the folder.
4. Keep `database.db` together with `database.db-wal` and `database.db-shm` if they exist. The migrator's read-only connection is the last one to close, so the last few seconds of SQLite writes can still be in `database.db-wal`. Copying `database.db` alone loses them. The first start on SQLite folds the WAL back in.
5. Start the server.

Events logged after the switch exist only in the DuckDB file.

## Memory

The copy runs DuckDB inside the server process with its own cap (`--memory`, default 256MB) next to the Java heap. In the tests the server's RSS peaked at 2,352 to 2,408 MB in every run, starting from 2,035 to 2,289 MB (2 GB heap, `-Xms` equal to `-Xmx`). Part of that growth is heap memory being touched for the first time, so DuckDB's share is at most 117 to 366 MB. With little headroom in a container, use `--memory 128MB --threads 1`.

After the switch the plugin's DuckDB uses `duckdb-memory-limit` from `config.yml` (default 512MB) for good. Plan container headroom for that, not for the migration.

## Measured numbers

Test setup: Windows 11 PC with 12 logical cores, Paper 26.3 build 237, `-Xmx2G`, server with no players, a working copy of the 10M synthetic database from [lab/RESULTS.md](../lab/RESULTS.md) (`lab/out/v2-10m.db`: 10.0M block, 1.5M container, 0.8M item rows, plus large user and username tables). A test plugin (`lab/migrate/writer`) logged block placements at 200 rows/s through the CoreProtect API during each run.

| | Result |
| --- | --- |
| Phase 1 at the default cap | 99,995 rows/s, 12.5M rows in 125 s |
| Phase 1 without a cap | 234,005 to 243,660 rows/s, 12.5M rows in 52 to 53 s |
| Phase 1 verify | 9.9 to 10.7 s |
| Logging pause | 1,085 ms (copying `co_user`), 1,673 ms (plus 20,000 entity_spawn rows) |
| Whole migration | 137 s at the default cap, 65 s without |
| Tick time (5 s average) | idle 0.3 to 0.7 ms; phase 1 at the default cap 0.3 to 0.9 ms; without a cap 0.2 to 0.3 ms |
| RSS peak | 2,352 to 2,408 MB (2 GB heap) |
| DuckDB file | 120 MB from a 1,009 MB SQLite file |

The default cap is conservative: even without it, tick times did not change on this PC. A host with few cores may differ. One early run used `cpbench write`, which loads a chunk on the main thread for every row it logs; tick times in that run averaged 1.7 to 2.2 ms, caused by those chunk loads and not by the copy. `cpbench write` now writes inside force-loaded chunks too.

## Tests that were run

- Schema: two migrated files compared with a file the plugin created itself: same columns, constraints and sequences.
- Data: `lab/migrate/check_after.py` after each successful run, with the server stopped. Every table matches SQLite up to the switch, no duplicate row IDs, every sequence is past its highest row ID, lock status 0. The writer plugin's rows add up exactly: 40,000 logged = 27,400 copied from SQLite + 12,600 written to DuckDB after the switch (run 2); 107,400 = 100,179 + 7,221 (run 3).
- Empty tables: the lab data has no sign, skull, entity, entity_spawn, entity_container, entity_interaction, entity_map or art_map rows. `lab/migrate/fill_empty_tables.py` added synthetic rows (including non-ASCII text, blobs, doubles, unique UUIDs and kill links) for run 3. All matched.
- Lookups through `cpbench` (radius 5, radius 20, a mid user, a top user with a limit, one block) returned the same row counts on SQLite before and on DuckDB after the switch and after a restart.
- A rollback after the switch: all 200 rows of a new write were marked `rolled_back = 1` in DuckDB.
- Restart on DuckDB: "Using DuckDB for data storage", spatial index loaded in 85 ms.
- Run 4 held the pause open for 8 s: 2,000 rows logged during it all reached DuckDB (102,179 = 100,179 + 2,000), and an API lookup sent during it returned 0 rows.
- Failures: forced failure inside the pause, forced failure right after the config switch, cancel in phase 1, server stop in phase 1. Each time SQLite stayed active, `config.yml` said `sqlite`, rollback worked again, and all 87,400 writer rows from those runs were in SQLite.
- Guards in phase 1: rollback "Database busy", purge "Upgrade in progress", reload "Upgrade in progress", a second migration refused.
- Identifier ids (run 5): `migwrite 1200 60 <world> blocks` started about 10 s before the pause, so new material and block data ids were handed out before, during and after it (pause held open 3 s). With the earlier build, 51 of 1,200 rows read back as another material and the log showed the cache error. With the fixed build: no cache errors, all 1,200 rows read back as the logged material, `co_material_map` grew from 611 to 1,225 rows with unique ids and values, and `check_after.py` passed.
- An `Error` thrown right after DuckDB was opened (`--test-fail switch-error`), with the same new-ids writer running: "Switched back to SQLite", `config.yml` said `sqlite`, the file was renamed, and SQLite held all 1,200 rows, all read back correctly, with no cache errors.
- A failure right after the config switch (`--test-fail switch`) with the fixed build: switched back, all 15,000 rows of a 200 rows/s writer reached SQLite.
- Switching back also failing (`--test-fail switch-back`): red message, logging stayed paused, `config.yml` still said `duckdb`, `database.duckdb` was not renamed. Then `database-type: sqlite` plus `/co reload`: all 15,000 rows queued meanwhile reached SQLite.

## Test with players online, on a copy of real data

Data: a copy of the CoreProtect database of server A, a survival server (not included; 4.4 GB, schema 2.24.0, 25.8M block rows, 2.0M container rows, 365k entity rows, 171 days), checked with `PRAGMA quick_check`. The fork upgraded it to 2.25.1 in about 3 s. Setup: a temporary Pterodactyl server on a 4-core Linux test host (Docker, 6 GB limit with the heap at 70% as on server A, 2 CPUs, the host shared with other services).

Players: 12 Mineflayer bots (`lab/migrate/bots/`) placed and broke blocks, opened chests, chatted and ran a command the whole time, about 25 logged actions per second. Mineflayer supports up to Minecraft 26.1, and through ViaBackwards on 26.3 every bot was kicked or had its block placements refused, so this server ran Paper 26.1.2 (build 74) without Via. The bots stand still (their movement packets are dropped), and `bukkit.yml` needs `connection-throttle: 0` because they all join from one address.

| | Result |
| --- | --- |
| Whole migration | 625 s |
| Phase 1 | 29,077,552 rows in 446 s (65,258 rows/s, below the 100,000 cap because of the 2 CPUs) |
| Phase 1 verify | 178 s |
| Catch-up | 1 round, 18,397 rows |
| Logging pause | 408 ms (2,533 rows copied in the pause, "waited 0.0 s for queued writes") |
| Tick time, 5 s average | before 6.2 to 7.0 ms, during 4.1 to 10.9 ms (one sample 20.6), after 4.2 to 8.7 ms; single-tick spikes up to about 100 ms happened before and during alike |
| Spatial index | built 7.7 s after the switch; 309 ms after a restart; 99 ms after a purge |
| `co lookup`, top player (1.5M block rows) | SQLite 651 to 2,617 ms, DuckDB 108 to 297 ms |
| `co lookup`, 90th percentile player (1,809 rows) | SQLite 14 to 51 ms, DuckDB 22 to 46 ms |
| Rollback / restore of one bot's last minute, on DuckDB | 206 / 59 ms |
| Purge of the oldest 21 days on the migrated file, bots online | 1,232 ms, 16,881,095 block rows removed, tick time 6.2 ms after |
| File size | SQLite 4,455 MB; DuckDB 1,622 MB right after the migration, of which 279 MB free space left by the copy (the offline loader makes 1,265 MB) |

Checks after the run, server stopped:

- `check_after.py`: every table matches SQLite up to the switch, identifier ids and values unique, lock status 0. It needed a 1.5 GB DuckDB memory cap at this size (512 MB ran out).
- `lab/migrate/bots_check.py`: each bot counts only the placements and breaks the server confirmed. Every break was logged exactly once (40,396). Placements matched too: the only difference was one row per bot for the chest each bot placed at the start, which the bot does not count.
- The bots' chest use (one item in, the same item out) leaves no container rows, because CoreProtect logs the net change between opening and closing a chest.

## Production run: server A, a 4.4 GB survival database

Server A itself was migrated with no players online. Setup: a Pterodactyl container with an 8 GB limit (heap capped at 70%, starting at 128 MB), 4 CPUs, Purpur 1.21.11, CoreProtect CE 24.0 before.

The server was stopped and `database.db` was copied as a backup (there was no `-wal` or `-shm` file after the clean stop). The first start on the fork build upgraded the schema from 24.0 to 25.1 in about 2 s and stayed on SQLite. The plugin added its DuckDB settings to `config.yml`; they were set to `duckdb-memory-limit: 256MB` and `duckdb-threads: 2`. Then `co migrate-duckdb --memory 128MB --threads 1` ran, with container memory sampled every 10 s and a cancel planned above 7,400 MiB.

| | Result |
| --- | --- |
| Whole migration | 308 s |
| Phase 1 | 29,043,608 rows in 300 s (96,902 rows/s, held at the 100,000 cap) |
| Phase 1 verify | 8.2 s |
| Catch-up | 1 round, 0 new rows |
| Logging pause | 211 ms (2,524 rows copied in the pause) |
| Container memory | 4,925 MiB before, at most 5,201 MiB during the copy, 5,882 MiB one minute after the switch, 5,872 MiB ten minutes after. This figure includes some file cache. |
| `co lookup ... t:999d #count`, top player and 90th percentile player | 1,552,978 and 2,582 rows on SQLite before, the same on DuckDB after |
| File size | SQLite 4,450 MB; DuckDB 1,621 MB plus a 1 MB WAL |

The log showed no CoreProtect or DuckDB warnings after the switch. With no players online nothing was written yet, so the spatial index is built on the first write after players return.

## Production run: server B, a 72 GB skyblock-style database

Server B was migrated with 15 to 20 players online. Setup: a Pterodactyl container with a 24 GB limit, 12 CPUs, Purpur 26.2. The fork's first boot on its SQLite file, before the migration, upgraded the schema from 24.0 to 25.1 in about 1 s.

The first attempt (`--memory 256MB --threads 2 --rows-per-second 150000`) copied every row and then failed in the phase 1 verify: "Out of Memory Error: failed to allocate data of size 16.0 MiB (243.8 MiB/244.1 MiB used)". The verify sums the byte length of every blob, and server B's `co_container` holds 78 million rows of item metadata. The failure path worked as designed: SQLite stayed active and the unfinished file was renamed `database.duckdb.failed-<date>-<time>`.

The second attempt with `--memory 2GB --threads 2 --rows-per-second 150000` and `duckdb-memory-limit: 1GB` in `config.yml`:

| | Result |
| --- | --- |
| Whole migration | 1,524 s |
| Phase 1 | 141,633,073 rows in 1,437 s: `co_block` 41.3M at the 150,000 cap, `co_container` 78.0M in 993 s (about 79,000 rows/s, slowed by the blobs), `co_entity_container` 17.8M, `co_item` 3.5M |
| Phase 1 verify | 85 s |
| Catch-up | 24,682 new rows, then 0 |
| Logging pause | 1,272 ms (rows copied in the pause: mostly `co_user` and `co_entity_spawn`) |
| Server | TPS 20 throughout; tick time 12 to 23 ms, 31 ms once during the verify |
| `co lookup u:<player> t:<cutoff> #count` (cutoff at the switch), two players | 27,438 and 194,702 rows on SQLite before, the same on DuckDB after |
| File size | SQLite 72.15 GB; DuckDB 70.58 GB |

DuckDB stores blobs almost uncompressed, so a database made mostly of container metadata barely shrinks. New rows were logged right after the switch, and the log showed no CoreProtect or DuckDB warnings.

## Compressed item metadata and compaction

Note: compression ratios depend on the items a server stores, so the numbers on server data below are examples.

Most of server B's database is item metadata: Java-serialized item stacks in `co_container.metadata`, `co_entity_container.metadata` and `co_item.data`. DuckDB 1.4.5 stores BLOB columns uncompressed (forcing its ZSTD compression on a BLOB column falls back to "Uncompressed"), so server B's 72.2 GB SQLite file became a 70.6 GB DuckDB file.

`compress-metadata: true` in `config.yml` (default false) stores these three columns as `CZ`, a format version, a dictionary id and a zlib stream that uses a preset dictionary shipped in the jar. It is lossless: decompressing gives back the exact bytes, so every decoder after it is unchanged. Rows written before stay readable, and so do rows written while it was on after it is turned off again. A build without this code cannot read compressed rows, so like the schema upgrade it is one-way. The ClickHouse write path is separate and never compresses; copying a compressed SQLite or DuckDB database into ClickHouse would need the rows decompressed first.

The shipped dictionary (16 KB) was trained on the metadata of generated vanilla items from Paper 1.21.11 and 26.3. How the size was chosen, on about 95,000 held-out blobs of generated items (the zlib stream alone; stored values also carry the 4-byte header, which brings the shipped dictionary to about 4.9x on these items):

| Method | Container metadata | Item data |
| --- | ---: | ---: |
| zlib per row | 1.4x | 1.4x |
| zlib with an 8 KB dictionary | 4.3x | 4.3x |
| zlib with a 16 KB dictionary (shipped) | 5.2x | 5.1x |
| zlib with a 32 KB dictionary | 5.8x | 5.7x |

Upstream's compact `BlockMetaCodec` was not used before the zlib step: it changes the key order of maps in 30 to 50% of item blobs, so it is not byte-exact. Compressing costs about 37 microseconds per item blob of about 500 bytes (plain zlib without a dictionary takes about 18), and decompressing about 7. Measured with the jar's own code on the generated items, one thread, on the test host's AMD EPYC 9355P (`lab/bench/CompressBench.java`).

`co migrate-duckdb` compresses the three columns during the copy when `compress-metadata` is on, on `--threads` worker threads. The verify then compares the bytes read and stored per range, and every 1,000th compressed value is decompressed and compared with the original.

`co migrate-duckdb compact` copies the active DuckDB file into `database.duckdb.compact` with the same phases, compressing when `compress-metadata` is on, and copies `co_duckdb_spatial_index` as it is in the pause (row IDs are kept, so there is no index rebuild). In the pause it closes the plugin's DuckDB, checks with a read-only open that nothing in the process still holds the file, renames the old file to `database.duckdb.old-<date>-<time>` and the new one to `database.duckdb`, and reopens. Any failure renames the files back and reopens the old file; the new file is kept as `database.duckdb.compact.failed-<date>-<time>`. Without compression it is still useful: it drops the free space a purge leaves behind. Delete `.old-*` and `.failed-*` files by hand once you are done with them.

If the server process dies hard (a crash or a kill, not a stop) in the few milliseconds between the two renames, the next start finds no `database.duckdb`, and DuckDB creates a new empty one: the data is in `database.duckdb.old-*` (the complete old file) and `database.duckdb.compact` (complete, but its catch-up may be a moment behind). Stop the server, move the empty `database.duckdb` away, rename the `.old-*` file back to `database.duckdb`, and start again. A hard stop after the second rename is harmless: `database.duckdb` is then the finished compacted file. Not tested: `--test-fail close`, `--test-fail switch-back`, and a server stop during a compaction.

Tests on the test host (Paper 26.1.2, the server A copy, 12 bots online, `--memory 1GB --threads 4 --rows-per-second 0`):

| Test | Result |
| --- | --- |
| New rows on SQLite with `compress-metadata: true` | 54 bot container rows stored compressed, 229,949 older rows untouched; Python's zlib with the dictionary decodes all 54 to Java serialization |
| Rollback then restore of a chest with 9 named and enchanted items, on SQLite | chest empty after the rollback; after the restore the `data get block` output is identical to before |
| T1: SQLite to DuckDB, compression off | 665 s, phase 1 462 s, verify 202 s, pause 801 ms, 1,620 MB (the earlier run: 625 s, 1,622 MB) |
| T2: compact with compression on | 260 s, pause 919 ms; 242 spatial index rows copied, no stall on the first write; the old file stopped changing |
| Rollback then restore of a chest on the compacted file | the same 9 items; only the order of components inside one item differs, which also differs between items given the same way |
| T3: `compact --test-fail rename` and `--test-fail switch` | old file renamed back and reopened, logging resumed, pauses 1,244 and 744 ms, the new file kept as `.compact.failed-*` |
| T4: SQLite to DuckDB, compression on | 783 s, phase 1 551 s, verify 230 s, pause 469 ms, compressed as in T2 |

Compression ratios for server A's data and the other data sets are in "Compression on different data" below. Server A's whole DuckDB file only shrinks about 10% (1,621 MB right after its migration, 1,467 MB compacted and compressed) because most of it is `co_entity` (saved data of killed entities), which this does not compress. On server B most of the file is container metadata.

## Production run: compacting server B

Server B (same setup as above) runs with `compress-metadata: true`, and was compacted with `--threads 4 --rows-per-second 150000`.

An attempt with `--memory 1GB` copied 142,655,385 rows in 951 s and then failed in the phase 1 verify on the new file's side: "Out of Memory Error: failed to allocate data of size 16.0 MiB (944.4 MiB/953.6 MiB used)". The old file stayed active and the new one was kept as `.compact.failed-*`. The run below used `--memory 2GB`, on the build with DuckDB 1.5.6 and the reopen before the verify (see "Known limits"), with 8 to 22 players online:

| | Result |
| --- | --- |
| Whole compaction | 960.7 s |
| Phase 1 | 143,470,190 rows in 956.5 s at the 150,000 cap: `co_container` 79.6M in 529 s, `co_entity_container` 18.7M in 125 s, `co_item` 3.5M |
| DuckDB memory | 1,841 MB after the copy, 2 MB after reopening the new file |
| Phase 1 verify | 2.8 s |
| Compression | `co_container.metadata` 38,166,560,582 to 9,904,088,682 bytes (3.85x), `co_entity_container.metadata` 3,178,987,158 to 606,351,210 (5.24x), `co_item.data` 624,293,653 to 129,868,031 (4.81x) |
| Catch-up | 17,701 new rows |
| Logging pause | 980 ms (68,581 rows copied in the pause, 1,274 spatial index rows) |
| Server | TPS 20 throughout; tick time 19.5 to 25.1 ms |
| File size | 72.15 GB as SQLite, 70.58 GB right after the live migration, 2.11 GB (2,105,683,968 bytes) compacted and compressed: 34x smaller than the SQLite file |
| After the switch | a 60-day chest container lookup returns rows copied before the switch; a 15-minute container lookup includes rows logged right after it |

The file is much smaller than the stored metadata bytes alone (10.6 GB), so DuckDB compresses the shorter blobs further by itself; how was not checked.

## Server C: values larger than one DuckDB block

The live migration of server C, a build server (146,133,632 `co_block` rows, SQLite 13,746 MB, `--memory 256MB --threads 2 --rows-per-second 100000`), failed during the copy at about 121 million block rows: "Failed to create checkpoint because of error: could not allocate block of size 176.0 KiB (243.2 MiB/244.1 MiB used)". SQLite stayed active. A copy of its SQLite file on the test host failed the same way at the same row.

Cause: DuckDB 1.4.5 holds far more memory than the data when a checkpoint writes BLOB values larger than one block (128 KiB, set upstream). Server C's `co_block.meta` has 110 values over 128 KiB (the largest is 5,532,873 bytes), and 15 of them (178,221 bytes each, one block type placed and broken repeatedly) sit within 60 row IDs. Copying row IDs 100M to 145.6M of the real table with the plugin's schema and settings:

| Run | Result |
| --- | --- |
| DuckDB 1.4.5, 256MB | failed at the same row |
| 1.4.5, without the 15 large rows | OK |
| 1.4.5, 256 KiB blocks | OK |
| 1.4.5, one commit every 245,760 rows | failed |
| 1.4.5, 512MB | OK, but `OVERFLOW_STRINGS` peaked at 310 MiB |
| DuckDB 1.5.6, 256MB, 128 KiB blocks | OK, `OVERFLOW_STRINGS` peak 6 MiB |
| DuckDB 2.0.0.1, 256MB, 128 KiB blocks | OK, 7 MiB |

Generated data never reproduced it (one 178 KB value with a full cache passed), and neither did the same rows without a large table copied before them. 256 KiB is DuckDB's largest block size and server C has 64 values above it, so the fork moved to DuckDB 1.5.6.0 instead. Files stay compatible both ways with the plugin's settings: a file created by 1.5.6 opens and takes writes in 1.4.5, and a 1.4.5 file written by 1.5.6 still opens and takes writes in 1.4.5.

Full migration of the server C copy on the test host with the 1.5.6 build and the same options:

| | Result |
| --- | --- |
| Whole migration | 1,890.9 s |
| Phase 1 | 147,242,338 rows in 1,473.8 s (`co_block` 146,133,632 in 1,461.3 s at the 100,000 cap) |
| Phase 1 verify | 415.0 s |
| Logging pause | 617 ms |
| Container peak | 1,241 MiB |
| After the switch | `co lookup r:#global b:stone t:365d` Page 1/6,080,914 in 13.1 s; `co status` on DuckDB |

Server C itself then got the 1.5.6 build (Paper downloaded `duckdb_jdbc` 1.5.6.0 on start) and was migrated with the same options, with 0 players online throughout: phase 1 147,242,360 rows in 1,472.4 s, verify 33.1 s (415 s on the test host's slower disk), catch-up 83 rows, logging pause 180 ms, done in 1,506.0 s. The DuckDB file was 833 MB (833,368,064 bytes) three days later, 17.3x smaller than the 14,414,004,224-byte SQLite file; `co status` on DuckDB with an empty queue, `co lookup r:#global b:stone t:30d` Page 1/39,173, no CoreProtect warnings after the switch.

## SQLite upgrade to 25.1 in Pterodactyl containers

This is about servers that are still on SQLite and get the fork before any migration. Server D, a large survival server (SQLite, 100,520,476,672 bytes, coming from CE 24.0), started a fork build without the fix below. The v24.1 step finished, then the v25.0 step failed after 3 s while building the chat, command and sign prefix indexes: `SQLITE_FULL ... database or disk is full` at `__2_25_0.java:57`. The step was rolled back, the database stayed at 2.24.1 (CE 24.0 still opens it), logging went on, and the plugin retries at every restart. Until the upgrade finishes, sign rollbacks fail (no `co_sign.rolled_back`) and text lookups run without the prefix indexes. The data disk had 186 of 480 GiB used.

Cause: SQLite sorts an index build in temp files, which go to `/tmp` by default. Pterodactyl's Wings runs every server container with a read-only root and `/tmp` as a 100 MB tmpfs (`ReadonlyRootfs=true`, `/tmp:rw,exec,nosuid,size=100M`). The fork did not set another temp folder.

| Run (8,000,000 chat rows, sqlite-jdbc 3.49.1.0, `docker run --read-only --tmpfs /tmp:size=100m`) | Result |
| --- | --- |
| The v25.0 chat index statement | `SQLITE_FULL` after 3.3 s |
| Same, after `PRAGMA temp_store_directory` on the data volume, set on another connection that was already closed | OK in 9.4 s |

Fix: `createSQLiteTables` sets `temp_store_directory` to `plugins/CoreProtect/tmp` before anything else, and ignores errors. The setting is process-wide in SQLite, so it also applies to other plugins using the server's SQLite driver. It covers the upgrade, index builds at startup and purges.

End to end on a Wings server on the test host (SQLite, a fresh fork database turned into a 2.24.1 one: the 10 prefix indexes and `co_sign.rolled_back` dropped, 8,000,000 chat rows added): the build without the fix failed with the same `SQLITE_FULL` and "Upgrade interrupted". The build with the fix ran v25.0 in about 8 s and v25.1, logged "Successfully upgraded to v25.1.", and the file had all 10 prefix indexes and the sign column. The temp folder was empty afterwards.

Server D then got the build with the fix. On its next start v25.0 and v25.1 ran in about 20 s, with no `SQLITE_FULL` and no CoreProtect warnings after the start. It was then migrated to DuckDB (next section).

## Production run: server D, a 96 GB survival database

Server D was migrated with 12 players online at the start. Setup: a Pterodactyl container with a 32 GB limit (heap 67%), 8 CPUs (the server alone used about 4.5), a Spigot-based 1.21.11 server, the build with the temp-folder fix. There was no test run on a copy: the file was 96 GB, and a failure leaves SQLite active, as it did on servers B and C. `compress-metadata: true` was set and applied with `co reload` (the migrator reads it when it starts), then `co migrate-duckdb --memory 2GB --threads 2 --rows-per-second 150000`.

| | Result |
| --- | --- |
| Whole migration | 6,404.0 s |
| Phase 1 | 901,609,259 rows in 6,014.2 s (149,912 rows/s): `co_block` 809,994,231 in 5,400.1 s at the cap, `co_command` 11,596,784 in 77.2 s, `co_container` 45,377,602 in 324.7 s (139,762 rows/s), `co_item` 33,496,151 in 209.3 s |
| DuckDB memory | 1,846 MB after the copy, 2 MB after reopening the new file |
| Phase 1 verify | 387.8 s |
| Catch-up | 8,183 new rows |
| Logging pause | 1,061 ms (almost all of it `co_user`; waited 0.0 s for queued writes) |
| Server | TPS 20.0 throughout (19.9 on the 5-minute average twice) |
| File size | 100,834,947,072 bytes (SQLite); 15,661,019,136 bytes (DuckDB) three days later, 6.4x smaller |
| After the switch | `co status` on DuckDB, no CoreProtect or DuckDB warnings; `co lookup r:#global b:stone t:1d` Page 1/10,795 in about 53 s with `duckdb-memory-limit: 512MB` |

Most of the file is `co_block`, whose rows are not compressed by `compress-metadata`, so it shrinks less than server B. With `duckdb-memory-limit: 2GB` and `co reload`, the same lookup returned in under a second, twice in a row (the first right after the reload). The 53 s was also the first lookup after the switch, so how much of it was the memory limit was not separated.

## Production run: two small hub servers

Two small hub servers (Paper 1.21.11, already on SQLite at 2.25.1) got the build with the temp-folder fix and were migrated one at a time: a restart, `compress-metadata: true` with `co reload`, then `co migrate-duckdb` with the default options.

| | Hub 1 | Hub 2 |
| --- | --- | --- |
| SQLite file | 30 MB | 31 MB |
| Phase 1 | 140,693 rows in 1.8 s (mostly `co_username_log`) | 149,710 rows in 1.5 s |
| Pause | 249 ms (115,805 `co_user` rows) | 856 ms (115,035 `co_user` rows) |
| Whole migration | 2.4 s | 3.1 s |
| DuckDB file | 8,794,112 bytes | 8,794,112 bytes |

Another plugin on hub 1 logged a high memory usage warning once during the copy (2 GB heap), not again after.

## Test-host runs on three more data sets

Setup: the 4-core test host (AMD EPYC 9355P, 15 GB RAM, shared with other services), one Pterodactyl server limited to 2 CPUs and 6 GB, Paper 26.1.2, no players. After every run, `lab/migrate/value_digest.py` compared every metadata value of the SQLite file with the DuckDB file (decoded with the jar's dictionary): all came back byte for byte, at the same row IDs.

- Server B's SQLite file as it was before its live migration (72.15 GB). The heap was lowered to 2.5 GB so that `--memory 2GB` fit in the 6 GB.
- Server E, a 743 MB SQLite file of an RPG server from 2024 (schema 2.22.2, 8.2M block rows, mostly one builder, few container rows, custom items). The fork upgraded it to 25.1 in about 1 s.
- A synthetic set from `lab/loader/gen_synthetic.py --block-rows 10000000 --item-blobs ...`: 15% of its container and item rows carry generated vanilla items that the dictionary was not trained on. User names in its base file were replaced.

| | Server B | Server E | Synthetic |
| --- | --- | --- | --- |
| SQLite file | 72,151,310,336 bytes | 743,088,128 bytes | 1,236,131,840 bytes |
| Rows copied | 141,657,765 | 8,239,187 | 12,348,020 |
| T1: SQLite to DuckDB, compression off | not run (would not fit on the disk next to the SQLite file; the live migration made 70.58 GB) | 87.8 s, pause 147 ms, 39,071,744 bytes (19.0x smaller) | 128.9 s, pause 155 ms, 206,450,688 bytes (6.0x smaller) |
| T2: compact T1's file, compression on | not run | 83.3 s, pause 183 ms, 31,600,640 bytes | 126.8 s, pause 340 ms, 155,987,968 bytes |
| T4: SQLite to DuckDB, compression on | `--memory 2GB --threads 2 --rows-per-second 150000`: 2,878.3 s (phase 1 2,445.2 s at 57,933 rows/s, held back by the 2 CPUs; verify 430.6 s), pause 1,199 ms, 2,024,026,112 bytes (35.6x smaller) | 86.7 s, pause 115 ms, 31,993,856 bytes (23.2x smaller) | 129.1 s, pause 191 ms, 156,643,328 bytes (7.9x smaller) |
| Values checked | 14,875,713 | 1,519 | 350,436 |

Servers E and synthetic ran with the default options (100,000 rows/s cap, 256MB, 2 threads). On server B's data DuckDB used 1,806 MB at the end of the copy and 2 MB after reopening the new file. Lookup and rollback timings on these files, SQLite against DuckDB with and without compression, are in [lab/RESULTS.md](../lab/RESULTS.md).

## Compression on different data

Uncompressed bytes against stored bytes, with the shipped dictionary:

| Data | Container metadata | Entity container metadata | Item data |
| --- | ---: | ---: | ---: |
| Generated vanilla items (synthetic set) | 4.94x | | 4.95x |
| Server A, survival | 4.38x | | 3.19x |
| Server B, skyblock-style | 3.85x | 5.24x | 4.81x |
| Server C, build | 3.08x | | 3.57x |
| Server D, survival | 4.36x | 4.25x (14 values) | 4.67x |
| Server E, RPG with custom items | 2.09x (69 values) | | 3.07x |

The dictionary holds the structure of vanilla item serialization, so items with long custom names and lore, as on server E, compress less. How much of a file this saves depends on how much of it is item metadata: on server B 72 GB became about 2 GB, on the synthetic set the DuckDB file went from 206 to 156 MB, and on server A only about 10%.

## Known limits

- Compressing item metadata runs on `--threads` worker threads, which compete with the server for CPU. On server B 4 threads kept up with the 150,000 rows/s cap (79.6 million `co_container` rows in 529 s) at TPS 20; speed without the cap was not measured on server B.
- `compress-metadata` covers container, entity container and item rows only. `co_entity.data` (killed entities) is not compressed; on server A that table is most of the DuckDB file.
- The phase 1 verify on the new file's side ran out of DuckDB memory on server B after about 140 million rows, at 256MB (the live migration) and at 1GB (a compaction), each time with the cap almost full before a 16 MiB request. The live migration then passed at 2GB. The migrator now reopens the new file before the verify (on the test host that took DuckDB's memory from 206 MB to 1 MB), and the fork is on DuckDB 1.5.6. With both, server B's compaction and a migration of a copy of its SQLite file passed at 2GB, with DuckDB at 2 MB after the reopen. Lower caps were not tried after these changes, so use `--memory 2GB` for a database of server B's size.
- Servers still on a DuckDB 1.4.5 build (files created by it use 128 KiB blocks) can hit the checkpoint failure described under "Server C" during normal logging, if a value over 128 KiB is written while DuckDB's memory is full. The 1.5.6 build passed the same copy with 128 KiB blocks.
- The pause grows with the size of `co_user` and `co_entity_spawn`, because both are copied whole. Measured: about 120,000 to 250,000 user rows per second and 52,000 to 66,000 entity_spawn rows per second. A server with a million users would pause for about 4 to 8 seconds.
- `co_entity_spawn` was only tested with synthetic rows, not with real entity tracking data.
- On a busy server the pause also includes finishing the batch being written and any running lookup (at most 60 s, then the migration fails and SQLite stays). With 12 bots logging about 25 actions per second the wait was still 0.0 s and the pause 408 ms, but a heavier server or a long lookup at that moment can make it longer.
- Lookups get no result during the pause (API lookups return an empty list), and new events wait in memory after the switch while the spatial index is built (6 to 7 s for 12.6M rows, 7.7 s for 25.8M rows).
- The verification compares counts and per-column sums, not every row.
- The players-online tests used bots on Paper 26.1.2. In production, servers B (15 to 20 players) and D (12 players) were migrated with players online; server A had none.
- `/co reload` itself still reloads the identifier ids, so the problem described under "Identifier ids" can also happen upstream when a reload runs right after a new id was handed out. Not fixed here: a plain reload can also switch to another database whose ids differ, so keeping the in-memory ids is only safe in the migrator.
- The identifier test depends on timing (an id must be handed out right before the pause). It reproduced the problem on the earlier build in one run; the fixed build passed it once with the same setup.
- The fork's `DatabaseConfigWriter` failed on Windows when setting the owner of the temporary config file (Windows refuses even the same owner). It now only sets the owner when it differs. This also affected the plugin's existing DuckDB fallback path.

## Files

The harness these tools run on is described in [lab/README.md](../lab/README.md).

| File | Purpose |
| --- | --- |
| `lab/migrate/e2e.py` | Runs one migration on a running lab server, samples RSS and tick times. `WRITER_AT=verify` sends the writer command when phase 1's copy ends. |
| `lab/migrate/check_after.py` | Offline data check of a finished migration. Also checks that every identifier table has unique ids and values; `--expected <csv>` checks that every row from `migwrite ... blocks` reads back as the logged material. |
| `lab/migrate/schema_diff.py` | Compares the schema of two DuckDB files. |
| `lab/migrate/fill_empty_tables.py` | Adds synthetic rows to the empty tables of a SQLite working copy. |
| `lab/migrate/bots/` | Mineflayer bots for the players-online test (`HOST`, `PORT`, `BOTS`, `DELAY`, `MCVER`); counts confirmed actions in `stats.json`. |
| `lab/migrate/bots_check.py` | Compares the bots' counts with CoreProtect's block rows in the migrated file. |
| `lab/migrate/sizes.py` | Used and free space of a DuckDB file, and space per table. |
| `lab/migrate/writer/` | Test plugin: `/migwrite <rows> <rows per second> <world> [blocks]` logs placements in force-loaded chunks. With `blocks` it cycles through every block material (except heads and skulls) and writes `plugins/MigWriter/expected-<world>.csv`. |

The tests used a separate server folder, `lab/server-mig`, on ports 25566 and 25576 (`LAB_SERVER=server-mig`).
