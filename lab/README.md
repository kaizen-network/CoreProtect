# CoreProtect SQLite vs DuckDB harness

Local only. Paper 26.3 build 237, the plugin built from the repo root (`..`), and the `CpBench` plugin in `bench-plugin/`. All commands run from this folder (`lab/`). `lab.py` uses only the Python standard library.

Results are in [RESULTS.md](RESULTS.md). The live migration (`co migrate-duckdb`) and its tests are described in [docs/duckdb-migration.md](../docs/duckdb-migration.md).

## One-time setup

```
python lab.py setup
curl -o server/paper-26.3-237.jar https://fill-data.papermc.io/v1/objects/57d8774be83dbe2be6a814e945f26a6b82339af9b1c82b768bbfdf4c23fda56e/paper-26.3-237.jar
python lab.py build
```

`setup` writes `eula.txt`, `server.properties` (localhost only, offline mode, flat world, view and simulation distance 2, RCON on 127.0.0.1:25575 with a random password), a CoreProtect `config.yml` (`check-updates: false`) and turns bStats off. The SHA-256 of the Paper jar is `57d8774be83dbe2be6a814e945f26a6b82339af9b1c82b768bbfdf4c23fda56e`.

`build` builds both jars with the JDK that `JAVA_HOME` points at (it must be JDK 25) and copies them to `server/plugins/CoreProtect.jar` and `server/plugins/CpBench.jar`. The plugin build on its own is:

```
cd ..
JAVA_HOME=<path to JDK 25> mvn -B -Dproject.branch=fork clean package
```

Output: `../target/CoreProtect-25.1.jar`. JDK 21 cannot build it (paper-api 26.3 is Java 25 bytecode). Without `-Dproject.branch` the jar refuses to enable ("branch has not been set").

## Run

| Step | Command |
| --- | --- |
| Pick a database (server stopped) | `python lab.py db duckdb out/v2-10m.duckdb` or `python lab.py db sqlite out/s1m.db` |
| Fresh empty database | `python lab.py db duckdb fresh` |
| Only switch the engine | `python lab.py db sqlite` |
| Start (waits for "Done", prints RSS) | `python lab.py start` |
| Load extra worlds after each start | `python lab.py rcon "cpbench world <name>"` |
| Send any console command | `python lab.py rcon "<command>"` |
| Process RSS (working set) | `python lab.py rss` |
| Show `[cp-bench]` lines | `python lab.py bench` or `python lab.py bench 20` |
| Stop | `python lab.py stop` |

`db` copies the file into `server/plugins/CoreProtect/` (as `database.db` or `database.duckdb`), deletes the old file's WAL or journal sidecars first, and sets `database-type` in `config.yml`. The copy is a working copy, so purge and rollback never touch the files in `out/`.

`world`, `world_nether` and `world_the_end` exist by default. Any other world name in the dataset needs `cpbench world <name>` after every start, because Paper does not load extra worlds on its own.

## Bench commands (`/cpbench`)

Each run logs `[cp-bench] <scenario> <args> rows=<n> ms=<t>`. Runs go through one worker thread, so they never overlap. `seconds` is "seconds back from now" (max 2147483647). The test data is in the past, so use a large value, e.g. `99999999` (about 3.2 years).

| Command | API call |
| --- | --- |
| `cpbench world <name>` | Loads the world, or creates an empty void world with that name |
| `cpbench radius <world> <x> <y> <z> <radius> <seconds> [limit=N]` | `performLookup(seconds, null, null, null, null, null, radius, location)` |
| `cpbench user <name> <seconds> [limit=N]` | `performLookup(seconds, [name], null, null, null, null, 0, null)` |
| `cpbench time <seconds> [limit=N]` | `blockLookup(LookupOptions.builder().time(seconds))`, all worlds |
| `cpbench block <world> <x> <y> <z>` | `blockLookup(block, 0)`, no time limit |
| `cpbench repeat <count> <scenario...>` | Runs the scenario `count` times, then logs `min= median= max=` |

With `limit=N`, `radius` and `user` use `performPartialLookup(..., 0, N)` and `time` uses `.limit(0, N)`.

## Timing lines from CoreProtect itself

The plugin logs one line when each of these finishes, only when the JVM runs with `-Dcpbench=true` (`lab.py start` passes it):

```
python lab.py rcon "co lookup u:<user> t:30d"          -> [cp-bench] lookup sender=Rcon ms=...
python lab.py rcon "co lookup r:#global b:stone t:30d" -> [cp-bench] lookup sender=Rcon ms=...
python lab.py rcon "co rollback u:<user> t:1h r:#global" -> [cp-bench] rollback sender=Rcon ms=...
python lab.py rcon "co restore u:<user> t:1h r:#global"  -> [cp-bench] restore sender=Rcon ms=...
python lab.py rcon "co purge t:30d"                    -> [cp-bench] purge ms=...
```

From the console, `co lookup` needs a user, or a `#` radius plus a block filter. `co rollback` and `co restore` need a user. Rollback and restore times include placing the blocks in the world. The test data is in the past, so `t:30d` may find nothing; `t:2000d` is accepted (checked on an empty database only).

## Compression tools

- `loader/gen_synthetic.py ... --item-blobs a.bin,b.bin [--blob-share 0.15]` gives that share of the generated container and item rows real serialized item metadata. A blob file holds a 4-byte big-endian length before each blob; any source of serialized item stacks works, for example the `co_container.metadata` values of an existing database.
- `bench/CompressBench.java` times the plugin's own compression per blob, one thread: `java -cp ../target/CoreProtect-25.1.jar bench/CompressBench.java <blob files>`. It prints the ratio, compress and decompress microseconds, and plain zlib without a dictionary for comparison.
- `migrate/value_digest.py` checks every metadata value after a migration. Run it on the SQLite file, and on the DuckDB file with the dictionary from the jar (`metadata-dictionary-1.bin`); equal output means every value came back byte for byte, at the same row ID.

## Things to keep in mind

- DuckDB runs with the defaults CoreProtect writes to `config.yml`: `duckdb-memory-limit: 512MB`, `duckdb-threads: 3`, `duckdb-max-temp-directory-size: 10GB`. `lab.py db` does not change them.
- The first run of a scenario includes JIT and cache warm-up (31 ms, then 2 to 3 ms on an empty database). Use `repeat` and read the median.
- Paper 26.3 has no spawn chunk gamerule (`spawnChunkRadius` and `spawn_chunk_radius` are both rejected), and the boot log shows "Loading 0 persistent chunks" for each dimension.
- Without `limit=N`, every matching row is loaded into the 2 GB heap. On a big dataset use a limit, or raise `-Xmx` in `JAVA_ARGS` at the top of `lab.py`.
- The API scenarios only search block break and place by default. `co lookup` without `a:` searches every action type, and on DuckDB it uses a paged query. Compare API numbers with API numbers and `co lookup` numbers with `co lookup` numbers.
- `time` uses the typed API because the legacy `performLookup` returns null for a global search with no radius.
- Purge changes the database. Run `db` again with the source file before the next run.
- SQLite purge writes the kept rows into `database.db.tmp` and then replaces `database.db`, so it needs free disk for that copy.
- The build uses `-Dproject.branch=fork`, which behaves like a community release: patch scripts run on an older database and the version is recorded. Any branch containing `-dev` (for example `development`) skips patch scripts ("Development branch detected, skipping patch scripts."). On a v24 SQLite file that breaks `co purge` with `no such column: rolled_back`, because the 2.25.1 patch that adds `co_sign.rolled_back` never ran.
- The first boot logs `[Worker-Main-3/ERROR]: No key layers in MapLike[{}]` while creating the flat world. The server still starts normally.

## Second server

`LAB_SERVER=<folder>` makes `lab.py` use another server folder (copy `server/`, then change `server-port` and `rcon.port` in its `server.properties`). `LAB_FORK=<path>` builds the CoreProtect jar from another worktree instead of the repo root.

## Logs

- `server/logs/latest.log`: the current run. Paper renames it on the next start, so run `python lab.py bench` before restarting.
- `server/logs/*.log.gz`: older runs.
- `server/console.out`: raw console output of the last `start`.
