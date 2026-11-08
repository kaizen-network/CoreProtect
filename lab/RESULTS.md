# SQLite vs DuckDB for CoreProtect: results

Tested with the CoreProtect master code at `dffa04f` plus small lab changes. The engine comparison was measured on synthetic data: 10M rows on a Windows PC, 50M rows in a capped container on a 4-core Linux host. The real-data sections used copies of server databases on that host. Nothing ran on a production game server. The harness is described in [README.md](README.md).

Production servers in these docs are named by letter, the same way as in [docs/duckdb-migration.md](../docs/duckdb-migration.md): server A is a survival server (4.4 GB SQLite file), server B a skyblock-style server (72 GB), server C a build server (13.7 GB), server D a large survival server (96 GB), server E an RPG server (743 MB, from 2024).

## Short answer

Compare the two engines within one scale. The two scales ran on different machines, so numbers across scales are not comparable.

| | SQLite 10M | DuckDB 10M | SQLite 50M | DuckDB 50M |
| --- | ---: | ---: | ---: | ---: |
| File size | 1,018 MB | 118 MB (8.6x smaller) | 5,052 MB | 536 MB (9.4x smaller) |
| Purge of the oldest 22% | 147 s | 0.6 s | 513 s | 0.9 s |
| Top user, API lookup | 2,245 ms | 196 ms | 20,152 ms | 582 ms |
| Top user, `co lookup` | 892 ms | 238 ms | 16,924 ms | 627 ms |
| Rollback / restore, one user, last hour | 631 / 245 ms | 696 / 193 ms | 5,325 / 496 ms | 2,017 / 391 ms |
| Block inspector | 4 ms | 18 ms | 2 ms | 67 ms |
| Radius 10 | 33 ms | 50 ms | 40 ms | 162 ms |
| Small user | 9 ms | 28 ms | 11 ms | 61 ms |
| Last hour, all users | 9 ms | 41 ms | 4 ms | 21 ms |
| Writes through the API | 33,800 rows/s | 35,200 rows/s | 27,800 rows/s | 5,300 rows/s (waited for the 25 s index build) |
| Server memory (RSS), DuckDB vs SQLite | | 19 to 335 MB more | | 260 MB less to 300 MB more |

DuckDB is worth it for storage, purge and heavy work: big users, wide areas, `co lookup`, and rollback at 50M. On a copy of server A's real database the file was 3.5x smaller (block data about 11x, saved entity data only 1.7x). On server B it barely shrank (72.2 to 70.6 GB), because most of its data is container metadata, which DuckDB stores almost uncompressed. With the fork's `compress-metadata` and a `co migrate-duckdb compact`, server B's file then went to 2.11 GB.

The trade-off is small lookups (block inspector, small radius, small user). SQLite answers them from an index in a few ms. DuckDB has no normal index and scans, so these took 18 to 50 ms at 10M and 21 to 162 ms at 50M, and they grow with the size of the database. Server D's file is about 19 times the 50M test file, so its small lookups on DuckDB would likely be slower than here, unless old data is purged regularly (cheap on DuckDB).

The other catch is the version: no released CoreProtect build can run this today (see "Version gating" below).

## Things to know before deciding

- **Synthetic data, plus one real copy.** The 10M and 50M rows are generated and have no entity data. A copy of a real server database (server A, not included) was tested too (see "Real data" below): its block data compresses like the synthetic data, but its saved entity data barely compresses.
- **Two machines.** 10M ran on a Windows PC with 12 logical cores. 50M ran on a 4-core Linux host in a Docker container capped at 2 CPUs and 5 GB, on a host shared with other services (host load rose to about 4.4 on 4 cores during the run). The same seeded generator made the same 50M data on both machines; this PC stopped its own 50M conversion earlier because it ran low on memory (the loader now caps DuckDB at 2 GB).
- **Cold cache.** The first lookup after start was much slower on SQLite at 50M: radius 50 took 21 s and the top user 24 s, against 0.4 s and 0.6 s on DuckDB. The table shows the median of 5 repeats.
- **User lookups on DuckDB have no index.** They scan, so their time grows with the total number of rows. SQLite's user index makes small users fast but big users very slow (20 s at 50M).
- **The SQLite test files are denser than real ones.** The generator built SQLite's indexes in one go. A purge that deleted nothing still rewrote the 10M file and made it 7% bigger, because indexes built row by row take more space. Real production files are probably bigger per row than the test files, which favours DuckDB on size.
- **A DuckDB purge does not shrink the file.** The freed space is reused for new rows (536 MB to 532 MB at 50M). A SQLite purge rebuilds the whole file (5,052 MB to 4,264 MB at 50M) and needs free disk for the kept rows while it runs.
- **Spatial index.** DuckDB builds its location index on the first write after a start when the index is empty (a freshly converted file): 6.3 s for 10M rows, 25.4 s for 50M. New events wait in memory meanwhile. Later loads take about 100 ms.
- **DuckDB code in CoreProtect is new** and was still changing upstream at the time of the test.

## Version gating

- The DuckDB code is in master, which still says version 24.1. Its v25 schema changes only run at plugin version 25.1 or higher.
- A build with a version above `ConfigHandler.PATCH_VERSION` refuses to start unless the branch name contains `-dev`, and `-dev` builds skip all schema patches.
- Without the 2.25.1 patch, `co purge` on an upgraded v24 file fails with `no such column: rolled_back`.
- So the lab build is pom 25.1 with `PATCH_VERSION` raised to 25.1, built with `-Dproject.branch=lab` (the tools now use `fork`; any name without `-dev` behaves the same).
- A server running a `24.1-dev` build never patches its database, so `co purge` there may hit the same error. Not verified.

## Test setup

- Paper 26.3 build 237, `-Xmx2G`, no players. 10M: Windows 11, JDK 25. 50M: Debian 13 host, Docker container with Java 25.0.5, `--cpus 2 --memory 5g`. DuckDB JDBC 1.4.5.0 (the plugin's own at the time), Python `duckdb==1.4.5` for the loader.
- Data: `loader/gen_synthetic.py` takes an existing CoreProtect database as the base (users, sessions, commands; no block rows; not included) and adds generated rows: 10.0M block, 1.5M container, 0.8M item, over 7.7 days, using 113 plain full blocks so rollback and restore work.
- `loader/sqlite_to_duckdb.py` converts SQLite to DuckDB with the plugin's own table definitions, keeps row IDs, and verifies counts and per-column sums.
- `bench/compare.py` copies the file in, starts the server and runs the same steps on both engines. API lookups report the median of 5 runs. Users and places are picked from the data at run time; names stay out of result files.

## Full results, 10M rows (PC, second run)

| Step | SQLite ms | DuckDB ms |
| --- | ---: | ---: |
| Write 100k placements through the API until stored | 2,957 | 2,845 |
| Radius 10, all time | 33 | 50 |
| Radius 50, all time | 212 | 80 |
| Block inspector | 4 | 18 |
| Top user (838,385 rows), all time | 2,245 | 196 |
| Mid user (814 rows), all time | 9 | 28 |
| Last hour, all users | 9 | 41 |
| `co lookup` mid user | 35 | 82 |
| `co lookup` top user | 892 | 238 |
| `co rollback` one user, last hour (1,915 rows) | 631 | 696 |
| `co restore` same | 245 | 193 |
| `co purge` oldest 22% of the time range | 147,036 | 604 |
| RSS at start / after lookups / end (MB) | 1,797 / 2,257 / 2,173 | 1,958 / 2,392 / 2,283 |
| Size before / after purge (MB) | 1,018 / 864 | 118 / 115 |
| Block rows after purge | 7,912,382 | 7,912,382 |

## Full results, 50M rows (Linux container)

| Step | SQLite ms | DuckDB ms |
| --- | ---: | ---: |
| Write 100k placements through the API until stored | 3,598 | 18,720 (overlapped the 25.4 s index build) |
| Radius 10, all time | 40 (first 3,937) | 162 (first 715) |
| Radius 50, all time | 382 (first 21,339) | 233 (first 370) |
| Block inspector | 2 | 67 |
| Top user (4,149,925 rows), all time | 20,152 (first 24,203) | 582 (first 621) |
| Mid user (4,103 rows), all time | 11 | 61 |
| Last hour, all users | 4 | 21 |
| `co lookup` mid user | 1,317 | 218 |
| `co lookup` top user | 16,924 | 627 |
| `co rollback` one user, last hour (1,787 rows) | 5,325 | 2,017 |
| `co restore` same | 496 | 391 |
| `co purge` oldest 22% of the time range | 512,608 | 867 |
| RSS at start / after lookups / end (MB) | 2,081 / 2,380 / 2,429 | 1,998 / 2,683 / 2,170 |
| Size before / after purge (MB) | 5,052 / 4,264 | 536 / 532 |
| Block rows after purge | 39,164,495 | 39,164,511 |

The 16-row difference after the purge comes from the cutoff being given in seconds relative to the moment each run sent the command.

The raw result files of these runs are not included. Print a table from your own runs with `python bench/table.py out/results-<label>-sqlite.json out/results-<label>-duckdb.json`.

## Real data: a copy of server A's database

A copy of server A's CoreProtect database (a survival server; 4,455 MB, 25.8M block rows, 171 days; not included) was migrated live on a test server with 12 bots playing; see [docs/duckdb-migration.md](../docs/duckdb-migration.md) for that test. Space per table, from SQLite's `dbstat` and DuckDB's storage info:

| Data | SQLite | DuckDB | Smaller by |
| --- | ---: | ---: | ---: |
| Blocks (table and its 3 indexes) | 2,366 MB | 214 MB | about 11x |
| Killed entities (`co_entity`, saved entity data) | 1,417 MB | 830 MB | 1.7x |
| Containers (table and 3 indexes) | 306 MB | 123 MB | 2.5x |
| Items (table and 3 indexes) | 134 MB | 52 MB | 2.6x |
| Whole file | 4,455 MB | 1,265 MB offline, 1,622 MB right after the live migration (279 MB of it free space) | 3.5x |

Block data shrinks as much as in the synthetic tests. The saved data of killed entities hardly compresses, and on server A it is a third of the SQLite file and most of the DuckDB file, so the overall gain depends on how much entity data a server has.

Lookups on the same data, three runs each: top player (1.5M block rows) SQLite 651 to 2,617 ms vs DuckDB 108 to 297 ms; a 90th percentile player (1,809 rows) SQLite 14 to 51 ms vs DuckDB 22 to 46 ms. A purge of the oldest 21 days (16.9M block rows) took 1.2 s on DuckDB.

Small lookups on real data are much less of a problem than the synthetic tables above suggest. Raw DuckDB queries in Python on the offline-loaded server A file (210 row groups of 122,880 rows, 2 threads, median of 5):

- One player's block count: 1.1 ms for the median player (data in 1 row group), 10 ms at the 90th percentile (7 row groups), 19 to 20 ms for the 99th percentile and the top player. DuckDB already skips row groups by their minimum and maximum user id, and user ids follow the order players first joined. A per-row-group player filter was tried on paper: at most 2 ms faster, and 8 to 10 times slower for players spread over most row groups. Not built.
- A block-inspector-style count (one exact position) on 30 random rows: 27 ms median, 85 ms at the 90th percentile as a full scan; 13 ms and 49 ms when limited to the row groups that hold the chunk, which is what the plugin's spatial index does. The chunk was in 22 row groups at the median, 70 at the 90th percentile.

The synthetic data spreads every user and every location over every row group, which is the worst case for both.

## Real data and compressed files: server B, server E and a synthetic set

The same files and server as in "Test-host runs on three more data sets" in [docs/duckdb-migration.md](../docs/duckdb-migration.md): 2 CPUs, 6 GB, Paper 26.1.2, no players, `duckdb-memory-limit: 1GB`, the heap at 2.5 GB for server B and 4.3 GB for the others. The steps of `bench/compare.py` without the purge, sent through the server console; first run / median of 5, in ms. Users and places are picked from the data, and names stay out of the result files.

`cpbench world` creates empty worlds with the data's world names, so the first run on a server also generates the chunks that its rollback and write touch. The tables therefore show DuckDB runs made after another run, except where a column says otherwise.

Server B's data (141.7M rows; DuckDB file compressed). The host has 15 GB of RAM, so SQLite's first lookups read its 72 GB file from disk, while most of DuckDB's 2 GB file stays cached:

| Step | SQLite | DuckDB |
| --- | ---: | ---: |
| Write 100k placements through the API until stored | 2,910 | 601 |
| Radius 10, all time | 45,395 / 2,950 | 85 / 25 |
| Radius 50, all time | 47,656 / 12,010 | 38 / 26 |
| Block inspector | 4,585 / 139 | 1,282 / 296 |
| Top user (577,524 block rows), all time | 6,638 / 578 | 28 / 20 |
| Small user (53 rows), all time | 18 / 1 | 10 / 4 |
| Last hour, all users | 12 / 3 | 29 / 22 |
| `co lookup` small user | 226 | 168 |
| `co lookup` top user | 10,188 | 1,021 |
| `co lookup r:#global a:container b:chest,barrel,hopper`, all time | 8,433 | 986 |
| `co rollback` one user, last hour (1,802 rows) | 700 | 1,005 |
| `co restore` same | 191 | 273 |

Server E's data (8.2M rows, one builder with 7.6M of them; the rollback is that builder's last hour). DuckDB ran before and after SQLite:

| Step | SQLite | DuckDB, first run | DuckDB, after SQLite |
| --- | ---: | ---: | ---: |
| Write 100k placements through the API until stored | 3,214 | 2,545 | 454 |
| Radius 10, all time | 2,420 / 73 | 67 / 15 | 424 / 22 |
| Radius 50, all time | 2,089 / 109 | 24 / 27 | 37 / 27 |
| Block inspector | 4 / 5 | 5 / 6 | 4 / 3 |
| Top user (7,584,061 block rows), all time | 21,432 / 7,570 | 23 / 22 | 23 / 31 |
| Small user (5 rows), all time | 3 / 2 | 8 / 10 | 11 / 2 |
| Last hour, all users | 9 / 4 | 35 / 16 | 49 / 44 |
| `co lookup` small user | 211 | 206 | 273 |
| `co lookup` top user | 824 | 334 | 481 |
| `co rollback` the builder, last hour (111,844 rows) | 3,084 | 4,894 | 7,633 |
| `co restore` same | 1,793 | 2,223 | 4,661 |

Synthetic set (12.3M rows, 15% of container and item rows with item metadata), DuckDB without and with `compress-metadata` (the T1 and T2 files):

| Step | SQLite | DuckDB, plain | DuckDB, compressed |
| --- | ---: | ---: | ---: |
| Write 100k placements through the API until stored | 4,408 | 2,696 | 589 |
| Radius 10, all time | 38 / 10 | 172 / 23 | 519 / 32 |
| Radius 50, all time | 26 / 19 | 20 / 10 | 142 / 28 |
| Block inspector | 2 / 1 | 19 / 12 | 18 / 17 |
| Top user (838,385 block rows), all time | 1,075 / 1,032 | 9 / 13 | 10 / 10 |
| Mid user (813 rows), all time | 4 / 7 | 30 / 16 | 20 / 18 |
| Last hour, all users | 12 / 3 | 53 / 38 | 49 / 21 |
| `co lookup` mid user | 44 | 209 | 70 |
| `co lookup` top user | 326 | 118 | 121 |
| `co rollback` one user, last hour (1,783 rows) | 1,387 | 1,504 | 1,227 |
| `co restore` same | 476 | 448 | 514 |

What these show:

- API lookups of the top user were 29 to 344 times faster on DuckDB (median), and `co lookup` of the top user 1.7 to 10 times. On SQLite they grow with the user's rows: server E's builder took 7.6 s per lookup even when cached.
- Radius lookups were much faster on DuckDB on server B's data (first runs of 45 to 48 s on SQLite) and faster on server E's, but mostly not on the synthetic set, whose 1.2 GB SQLite file stays cached.
- Small lookups (a small user, the last hour) stayed at 1 to 7 ms on SQLite and took 2 to 44 ms on DuckDB (medians), as in the synthetic 10M and 50M runs. The block inspector on server B's data took 139 ms on SQLite and 296 ms on DuckDB.
- Rollbacks of about 1,800 rows took 0.7 s on SQLite and 1.0 s on DuckDB on server B's data; on the synthetic set both took 1.2 to 1.5 s. The 111,844-row rollback on server E's data was slower on DuckDB (4.9 and 7.6 s against 3.1 s).
- On the compressed file, radius lookups were slower in this run (medians 32 against 23 ms and 28 against 10 ms) and the other steps were mixed. Decompressing one value takes about 7 microseconds (see "Compressed item metadata and compaction" in the migration guide).
- 100k writes through the API were stored in 0.45 to 2.7 s on DuckDB and 2.9 to 4.4 s on SQLite.

## Production estimate

This estimate was made before the production migrations. The measured results for all four servers are in [docs/duckdb-migration.md](../docs/duckdb-migration.md).

Storage: servers A and B are measured. For the others, 3.5x (a share of entity data like server A's) to 11x (almost only block data) is a range, not a measurement. Server B shows the range is not a floor: most of its file is container metadata (78 million `co_container` rows), which DuckDB stores almost uncompressed, so it barely shrank. Server D's share of container data was not checked.

| Server | CoreProtect SQLite now | DuckDB estimate |
| --- | ---: | ---: |
| D | 95.5 GB | 8.7 to 27.3 GB if it is mostly block data; close to 95 GB if it is mostly container data like server B |
| B | 72.2 GB | 70.6 GB measured after the live migration; 2.11 GB measured after `compress-metadata` and `co migrate-duckdb compact` |
| C | 13.7 GB | 1.2 to 3.9 GB |
| A | 4.2 GB | 1.3 GB measured on a copy (1.6 GB right after a live migration) |

Memory: DuckDB's cache (`duckdb-memory-limit`, default 512MB) lives outside the Java heap, so it needs room between `-Xmx` and the container limit.

| Server | Container limit | `-Xmx` share | Room outside the heap |
| --- | ---: | ---: | ---: |
| D | 32 GB | 67% | about 10 GB |
| A | 8 GB | 70% | about 2.4 GB |
| C | 6 GB | 80% | about 1.2 GB (the container showed 6.2 GB in use) |
| B | not checked | | |

## Moving a live server

`co migrate-duckdb` copies a running server's SQLite database into DuckDB and switches over with a logging pause of about 1 to 2 seconds. An independent code review found four problems (identifier ids handed out twice across the switch, and three failure-path gaps); all four are fixed and tested. See [docs/duckdb-migration.md](../docs/duckdb-migration.md).

## Other engines

- `database-type` in master accepts `duckdb`, `clickhouse`, `sqlite` and `mysql`.
- MySQL: CoreProtect already supports it. A live copy from SQLite would work like the DuckDB migrator, but it was not built.
- ClickHouse: needs a separate ClickHouse 26.1 or newer server. Not tested.
- The plugin's own `co migrate-db` lives in a closed-source class and is not in community builds.
