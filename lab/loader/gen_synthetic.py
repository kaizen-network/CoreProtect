"""Build a test CoreProtect SQLite database: a real base file plus generated block/container/item history.

Usage: python gen_synthetic.py <base database.db> <new output.db> --block-rows N [--rate 15] [--seed 0.42] [--fake-blobs] [--item-blobs a.bin,b.bin] [--blob-share 0.15] [--memory-limit 2GB] [--threads 4]

The base file (an existing CoreProtect database) supplies users, sessions, commands, worlds,
material and blockdata maps. Generated history uses only ids that exist in those maps.

Model (rough stand-in for a busy survival server, not measured data):
- Activity comes in bursts: one user, one spot, one main material, break or place, ~25 rows
  on average (5% of bursts are 10x longer), 0.2-1.7 s between rows.
- Users are heavy-tailed (log-uniform over the first 3000 user ids); 5% of bursts are '#water'.
- Each user has a home spot within +-8000 blocks; bursts land within +-200 of it.
- Bursts overlap in time (rows/second = --rate), so consecutive rowids jump between players.
- Materials come only from an allowlist of plain full blocks that are also items (no block states),
  so rollback and restore work on this data. 1% of block rows are rolled back.
- Container rows (15% of block count) and item rows (8%).
- --fake-blobs (size tests only): 30% of block rows get blockdata ids, 0.5% meta, 10% of container/item
  rows metadata from a pool of 2000 blobs. These are not valid CoreProtect blobs and break rollback.
- --item-blobs a.bin[,b.bin]: --blob-share of container/item rows get metadata picked at random from real
  serialized item blobs (files of 4-byte big-endian length + blob each). The blob is
  not matched to the row's material, so these are for size and speed tests, not for rolling back containers.
"""
import argparse, os, re, sqlite3, struct, sys, time

import duckdb

ap = argparse.ArgumentParser()
ap.add_argument("base")
ap.add_argument("out")
ap.add_argument("--block-rows", type=int, required=True)
ap.add_argument("--rate", type=float, default=15.0, help="block rows per second of game time")
ap.add_argument("--seed", type=float, default=0.42)
ap.add_argument("--fake-blobs", action="store_true")
ap.add_argument("--item-blobs", help="comma-separated blob files for container/item metadata")
ap.add_argument("--blob-share", type=float, default=0.15, help="share of container/item rows with --item-blobs metadata")
ap.add_argument("--memory-limit", default="2GB", help="DuckDB memory cap; it spills to disk above this (default would be 80%% of RAM)")
ap.add_argument("--threads", type=int, default=4)
args = ap.parse_args()
if os.path.exists(args.out):
    sys.exit(f"output exists: {args.out}")

t_start = time.time()
base = sqlite3.connect(f"file:{os.path.abspath(args.base)}?mode=ro", uri=True)
base.execute(f"VACUUM INTO '{os.path.abspath(args.out)}'")
base.close()

out = sqlite3.connect(args.out)
n_mat = out.execute("SELECT max(id) FROM co_material_map").fetchone()[0]
n_bd = out.execute("SELECT max(id) FROM co_blockdata_map").fetchone()[0]
water = (out.execute("SELECT rowid FROM co_user WHERE user='#water'").fetchone() or (1,))[0]
worlds = [r[0] for r in out.execute("SELECT id FROM co_world ORDER BY id")]
indexes = [r[0] for r in out.execute("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name IN ('co_block','co_container','co_item') AND sql IS NOT NULL")]
for (name,) in out.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name IN ('co_block','co_container','co_item')").fetchall():
    out.execute(f"DROP INDEX {name}")
out.commit()
out.close()

N = args.block_rows
span = int(N / args.rate)
t_end = int(time.time())
w = worlds + [worlds[0]] * 3  # mostly the first world
world_expr = "CASE " + " ".join(f"WHEN rw < {(i + 1) / len(w)} THEN {wid}" for i, wid in enumerate(w)) + f" ELSE {worlds[0]} END"
FULL_BLOCK = re.compile(r"(_planks|_wool|_concrete|_concrete_powder|_terracotta|_stained_glass|_bricks|_block)$|^(stone|dirt|cobblestone|mossy_cobblestone|andesite|granite|diorite|polished_andesite|polished_diorite|sandstone|red_sandstone|cut_sandstone|glass|bricks|obsidian|end_stone|bookshelf|clay|ice|packed_ice|blue_ice|smooth_stone|sea_lantern|shroomlight|coarse_dirt|red_sand|quartz_pillar)$")
out = sqlite3.connect(args.out)
allowed = [i for i, m in out.execute("SELECT id, material FROM co_material_map ORDER BY id") if FULL_BLOCK.search(m.split(":")[-1])
           and m.split(":")[-1] not in ("command_block", "structure_block", "mushroom_stem", "red_mushroom_block", "brown_mushroom_block")]
out.close()
print(f"{len(allowed)} allowed materials")
zipf_mat = f"({allowed})[CAST(floor(exp(random() * ln({len(allowed)}))) AS INTEGER)]"

fake = "true" if args.fake_blobs else "false"
d = duckdb.connect(config={"memory_limit": args.memory_limit, "threads": str(args.threads), "temp_directory": args.out + ".tmp"})
d.execute(f"SELECT setseed({args.seed})")
d.execute("INSTALL sqlite; LOAD sqlite;")
d.execute(f"ATTACH '{os.path.abspath(args.out)}' AS sq (TYPE sqlite)")


def bursts(name, rows, mean_len):
    n = max(1, int(rows / (mean_len * 1.45)))
    d.execute(f"""
        CREATE OR REPLACE TABLE {name} AS
        SELECT burst, usr, {world_expr} AS wid,
               CAST(hash(usr, 1) % 16000 AS INTEGER) - 8000 + CAST((random() - 0.5) * 400 AS INTEGER) AS cx,
               CAST(hash(usr, 2) % 16000 AS INTEGER) - 8000 + CAST((random() - 0.5) * 400 AS INTEGER) AS cz,
               CAST(64 + (random() - 0.5) * 120 AS INTEGER) AS cy,
               {t_end} - CAST(random() * {span} AS INTEGER) AS t0,
               greatest(1, CAST(-ln(1 - random()) * {mean_len} AS INTEGER) * CASE WHEN random() < 0.05 THEN 10 ELSE 1 END) AS len,
               0.2 + random() * 1.5 AS dt,
               CASE WHEN random() < 0.55 THEN 0 ELSE 1 END AS action,
               {zipf_mat} AS base_type
        FROM (SELECT range AS burst,
                     CASE WHEN random() < 0.05 THEN {water} ELSE CAST(floor(exp(random() * ln(3000))) AS INTEGER) END AS usr,
                     random() AS rw
              FROM range({n}))
    """)


def timed(label, sql):
    ts = time.time()
    d.execute(sql)
    print(f"{label}: {time.time() - ts:.1f}s", flush=True)


# Pool of item metadata blobs: real serialized items with --item-blobs, else repeated hex (compresses a bit, like serialized item meta).
if args.item_blobs:
    blobs = []
    for path in args.item_blobs.split(","):
        with open(path, "rb") as f:
            while header := f.read(4):
                blobs.append(f.read(struct.unpack(">I", header)[0]))
    d.execute("CREATE TABLE pool (i INTEGER, b BLOB)")
    d.executemany("INSERT INTO pool VALUES (?, ?)", list(enumerate(blobs)))
    pool_n, pool_share = len(blobs), args.blob_share
    print(f"{pool_n} item blobs, {sum(map(len, blobs)) / pool_n:.0f} bytes on average")
else:
    d.execute("CREATE TABLE pool AS SELECT range AS i, CAST(repeat(md5(CAST(range AS VARCHAR)), 1 + range % 8) AS BLOB) AS b FROM range(2000)")
    pool_n, pool_share = 2000, 0.1 if args.fake_blobs else 0

bursts("bb", N, 25)
timed("co_block", f"""
    INSERT INTO sq.co_block (time, user, wid, x, y, z, type, data, meta, blockdata, action, rolled_back)
    SELECT t0 + CAST(j * dt AS INTEGER), usr, wid,
           cx + CAST((random() - 0.5) * 2 * (3 + sqrt(j)) AS INTEGER),
           greatest(-64, least(319, cy + CAST((random() - 0.5) * 6 AS INTEGER))),
           cz + CAST((random() - 0.5) * 2 * (3 + sqrt(j)) AS INTEGER),
           CASE WHEN random() < 0.7 THEN base_type ELSE {zipf_mat} END,
           0,
           CASE WHEN {fake} AND random() < 0.005 THEN CAST(md5(CAST(random() AS VARCHAR)) || md5(CAST(random() AS VARCHAR)) AS BLOB) END,
           CASE WHEN {fake} AND random() < 0.3 THEN CAST(CAST(1 + CAST(random() * {n_bd - 1} AS INTEGER) AS VARCHAR) || ',' || CAST(1 + CAST(random() * {n_bd - 1} AS INTEGER) AS VARCHAR) AS BLOB) END,
           CASE WHEN random() < 0.05 THEN 2 ELSE action END,
           CASE WHEN random() < 0.01 THEN 1 ELSE 0 END
    FROM (SELECT *, unnest(range(len)) AS j FROM bb)
    ORDER BY 1, burst, j
""")

for table, share, mean_len, meta_col in (("co_container", 0.15, 6, "metadata"), ("co_item", 0.08, 4, "data")):
    bursts("cb", N * share, mean_len)
    cols = "time, user, wid, x, y, z, type, data, amount, metadata, action, rolled_back" if table == "co_container" \
        else "time, user, wid, x, y, z, type, data, amount, action, rolled_back"
    meta = "pool.b"
    vals = (f"t0 + CAST(j * dt * 4 AS INTEGER), usr, wid, cx, cy, cz, {zipf_mat}, 0, 1 + CAST(random() * 63 AS INTEGER), {meta}, "
            "CASE WHEN random() < 0.5 THEN 0 ELSE 1 END, 0") if table == "co_container" else \
           (f"t0 + CAST(j * dt * 4 AS INTEGER), usr, wid, cx + CAST((random() - 0.5) * 8 AS INTEGER), cy, cz + CAST((random() - 0.5) * 8 AS INTEGER), "
            f"{zipf_mat}, {meta}, 1 + CAST(random() * 63 AS INTEGER), CAST(random() * 4 AS INTEGER), 0")
    timed(table, f"INSERT INTO sq.{table} ({cols}) SELECT {vals} FROM (SELECT *, CASE WHEN random() < {pool_share} THEN CAST(random() * {pool_n - 1} AS INTEGER) END AS pidx "
                     f"FROM (SELECT *, unnest(range(len)) AS j FROM cb)) r LEFT JOIN pool ON pool.i = r.pidx ORDER BY 1, burst, j")
d.close()

out = sqlite3.connect(args.out)
ts = time.time()
for sql in indexes:
    out.execute(sql)
out.commit()
print(f"indexes: {time.time() - ts:.1f}s")
for t in ("co_block", "co_container", "co_item"):
    print(t, out.execute(f"SELECT count(*), min(time), max(time) FROM {t}").fetchone())
out.close()
print(f"done in {time.time() - t_start:.0f}s, {os.path.getsize(args.out) / 1048576:.0f} MB")
