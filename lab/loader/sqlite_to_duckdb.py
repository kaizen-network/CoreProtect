"""Copy a CoreProtect SQLite database into a new CoreProtect DuckDB file.

Usage: python sqlite_to_duckdb.py <source database.db> <new database.duckdb> [--memory-limit 2GB] [--threads 4]

The source is opened read-only and never changed. The target must not exist.
Schema is copied verbatim from DuckDBDatabase.createTables (CoreProtect master dffa04f).
Row IDs are preserved and each row ID sequence starts after the source's largest row ID,
which is what the plugin expects of a migrated file. The spatial index table is left
empty: the plugin rebuilds it from the tables on first use.

Needs the duckdb package matching the plugin's JDBC driver (1.5.6), see requirements.txt.
"""
import argparse, os, sqlite3, sys, time

import duckdb

PREFIX = "co_"
EVENT = 'time INTEGER, "user" INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER'
# Order and column definitions from DuckDBDatabase.createTables @ dffa04f.
TABLES = {
    "art_map": "id INTEGER, art VARCHAR",
    "block": EVENT + ", type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, action TINYINT, rolled_back TINYINT",
    "chat": EVENT + ", message VARCHAR",
    "command": EVENT + ", message VARCHAR",
    "container": EVENT + ", type INTEGER, data INTEGER, amount INTEGER, metadata BLOB, action TINYINT, rolled_back TINYINT",
    "entity_container": 'time INTEGER, "user" INTEGER, entity_spawn_rowid INTEGER NOT NULL, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, amount INTEGER, metadata BLOB, action TINYINT, rolled_back TINYINT',
    "entity_interaction": 'time INTEGER, "user" INTEGER, entity_spawn_rowid INTEGER NOT NULL, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, action TINYINT, metadata BLOB, rolled_back TINYINT',
    "item": EVENT + ", type INTEGER, data BLOB, amount INTEGER, action TINYINT, rolled_back TINYINT",
    "database_lock": None,  # special: rowid INTEGER PRIMARY KEY, no sequence
    "entity": "time INTEGER, data BLOB",
    "entity_spawn": "time INTEGER, block_rowid BIGINT, kill_rowid INTEGER, uuid VARCHAR UNIQUE, wid INTEGER, current_wid INTEGER, origin_x DOUBLE, origin_y DOUBLE, origin_z DOUBLE, x DOUBLE, y DOUBLE, z DOUBLE, yaw FLOAT, pitch FLOAT, data BLOB, removed TINYINT, UNIQUE(kill_rowid)",
    "entity_map": "id INTEGER, entity VARCHAR",
    "material_map": "id INTEGER, material VARCHAR",
    "blockdata_map": "id INTEGER, data VARCHAR",
    "session": EVENT + ", action TINYINT",
    "sign": EVENT + ", action TINYINT, color INTEGER, color_secondary INTEGER, data TINYINT, waxed TINYINT, face TINYINT, "
    + ", ".join(f"line_{i} VARCHAR" for i in range(1, 9)) + ", rolled_back TINYINT DEFAULT 0",
    "skull": "time INTEGER, owner VARCHAR, skin VARCHAR",
    "user": 'time INTEGER, "user" VARCHAR, uuid VARCHAR',
    "username_log": 'time INTEGER, uuid VARCHAR, "user" VARCHAR',
    "version": "time INTEGER, version VARCHAR",
    "world": "id INTEGER, world VARCHAR",
}
SPATIAL = ("CREATE TABLE IF NOT EXISTS co_duckdb_spatial_index (table_id TINYINT NOT NULL, start_rowid BIGINT NOT NULL, "
           "end_rowid BIGINT NOT NULL, row_count INTEGER NOT NULL, chunks BLOB NOT NULL, entities BLOB, PRIMARY KEY(table_id,start_rowid))")
INT_MAX = 2**31 - 1


def columns(defs):
    """Column names and DuckDB types from a definition string (skips table constraints)."""
    out = []
    for part in defs.split(", "):
        if part.startswith("UNIQUE("):
            continue
        name, typ = part.split(" ")[:2]
        out.append((name.strip('"'), typ))
    return out


def q(name):
    return f'"{name}"'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("target")
    ap.add_argument("--memory-limit", default="2GB", help="DuckDB memory cap; it spills to disk above this (default would be 80%% of RAM)")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()
    if os.path.exists(args.target):
        sys.exit(f"target exists, refusing to touch it: {args.target}")

    src = sqlite3.connect(f"file:{os.path.abspath(args.source)}?mode=ro", uri=True)
    src_tables = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    src_cols = {t: {r[1] for r in src.execute(f"PRAGMA table_info({t})")} for t in src_tables}

    # Pre-checks: anything that would fail or silently change data in DuckDB.
    versions = [v for (v,) in src.execute(f"SELECT version FROM {PREFIX}version ORDER BY rowid")] if PREFIX + "version" in src_tables else []
    last = tuple(int(p) for p in versions[-1].split(".")[:3]) if versions else (0, 0, 0)
    if versions and last < (2, 23, 0):
        sys.exit(f"source schema version {versions[-1]} is older than 2.23.0; start it once on CoreProtect 24.x first")
    stats = {}
    for t in TABLES:
        name = PREFIX + t
        if name in src_tables:
            stats[t] = src.execute(f"SELECT count(*), min(rowid), max(rowid) FROM {name}").fetchone()
    problems = [f"{t}: max rowid {s[2]} does not fit INTEGER" for t, s in stats.items() if t != "block" and (s[2] or 0) > INT_MAX]
    if PREFIX + "entity_spawn" in src_tables:
        for col in ("uuid", "kill_rowid"):
            dup = src.execute(f"SELECT count(*) FROM (SELECT {col} FROM {PREFIX}entity_spawn WHERE {col} IS NOT NULL GROUP BY {col} HAVING count(*) > 1)").fetchone()[0]
            if dup:
                problems.append(f"entity_spawn: {dup} duplicate {col} values (DuckDB has UNIQUE on it)")
    if problems:
        sys.exit("pre-check failed:\n  " + "\n  ".join(problems))
    print(f"source {args.source}: {os.path.getsize(args.source)/1048576:.1f} MB, schema {versions[-1] if versions else '?'}")

    # default_block_size matches Database.DUCKDB_BLOCK_SIZE, so the file layout equals a plugin-created one.
    dst = duckdb.connect(args.target, config={"default_block_size": "131072", "memory_limit": args.memory_limit,
                                              "threads": str(args.threads), "temp_directory": args.target + ".tmp"})
    dst.execute("INSTALL sqlite; LOAD sqlite;")
    dst.execute(f"ATTACH '{os.path.abspath(args.source)}' AS src (TYPE sqlite, READ_ONLY)")

    for t in TABLES:
        if t != "database_lock":
            start = (stats.get(t, (0, None, None))[2] or 0) + 1
            dst.execute(f"CREATE SEQUENCE {PREFIX}{t}_rowid_seq START {start}")
    for t, defs in TABLES.items():
        if defs is None:
            dst.execute(f"CREATE TABLE {PREFIX}{t} (rowid INTEGER PRIMARY KEY, status TINYINT, time INTEGER)")
            continue
        rid = "BIGINT" if t == "block" else "INTEGER"
        dst.execute(f"CREATE TABLE {PREFIX}{t} (rowid {rid} NOT NULL DEFAULT nextval('{PREFIX}{t}_rowid_seq'), {defs})")
    dst.execute(SPATIAL)
    # Fresh, unlocked lock row, as DuckDBDatabase.initialize writes it.
    dst.execute(f"INSERT INTO {PREFIX}database_lock (rowid, status, time) VALUES (1, 0, {int(time.time())})")

    t0 = time.time()
    for t, defs in TABLES.items():
        name = PREFIX + t
        if defs is None or t not in stats or stats[t][0] == 0:
            continue
        cols = [c for c, _ in columns(defs) if c in src_cols[name]]
        missing = [c for c, _ in columns(defs) if c not in src_cols[name]]
        collist = ", ".join(q(c) for c in cols)
        n = stats[t][0]
        ts = time.time()
        # One statement per table: the sqlite scanner does not push rowid ranges down, so chunks would each rescan the table.
        dst.execute(f"INSERT INTO {name} (rowid, {collist}) SELECT rowid, {collist} FROM src.{name} ORDER BY rowid")
        dt = time.time() - ts
        print(f"copied {t:18} {n:>12,} rows in {dt:7.1f}s ({n / max(dt, 1e-9):,.0f}/s)" + (f"  missing in source, default used: {missing}" if missing else ""))
    print(f"copy total {time.time() - t0:.1f}s")

    # Verify: count, rowid range, and per-column sums / byte lengths must match the source.
    bad = 0
    for t, defs in TABLES.items():
        name = PREFIX + t
        if defs is None or t not in stats:
            continue
        exprs_src, exprs_dst = ["count(*)", "min(rowid)", "max(rowid)"], ["count(*)", "min(rowid)", "max(rowid)"]
        for c, typ in columns(defs):
            if c not in src_cols[name]:
                continue
            if typ in ("VARCHAR", "BLOB"):
                exprs_src.append(f"sum(length(CAST({q(c)} AS BLOB)))")
                exprs_dst.append(f"sum(strlen({q(c)}))" if typ == "VARCHAR" else f"sum(octet_length({q(c)}))")
            elif typ in ("DOUBLE", "FLOAT"):
                exprs_src.append(f"round(sum({q(c)}), 0)")
                exprs_dst.append(f"round(sum({q(c)}), 0)")
            else:
                exprs_src.append(f"sum({q(c)})")
                exprs_dst.append(f"sum({q(c)})")
        a = src.execute(f"SELECT {', '.join(exprs_src)} FROM {name}").fetchone()
        b = dst.execute(f"SELECT {', '.join(exprs_dst)} FROM {name}").fetchone()
        same = [x == y or (x in (None, 0) and y in (None, 0)) or (isinstance(x, float) and abs(x - (y or 0)) <= max(1.0, abs(x) * 1e-6)) for x, y in zip(a, b)]
        if not all(same):
            bad += 1
            print(f"MISMATCH {t}: source {a} target {b}")
    dst.execute("DETACH src")
    dst.execute("CHECKPOINT")
    dst.close()
    size = os.path.getsize(args.target)
    print(f"verify: {'OK' if not bad else f'{bad} table(s) differ'}; target {size/1048576:.1f} MB")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
