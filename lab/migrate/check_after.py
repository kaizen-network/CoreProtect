"""Offline check of a live migration, with the server stopped.

Usage: .venv/Scripts/python.exe migrate/check_after.py <database.db> <database.duckdb> [user name to count]
       [--expected plugins/MigWriter/expected-<world>.csv]

The SQLite file is the source as it was at the switch (opened read-only, its -wal is read too).
Per table: rows with row ID <= the SQLite max must match SQLite (count, min/max row ID, per column sum
or byte length sum), like loader/sqlite_to_duckdb.py. Also: no duplicate row IDs in DuckDB, every row ID
sequence is past the table's max row ID, and the rows above the SQLite max (written after the switch).
Every identifier table (material, block data, art, entity, world) must have unique ids and unique values.
With --expected (written by `migwrite ... blocks`), every logged row must read back as the logged material.
Exit code 0 when everything matches.
"""
import argparse
import csv
import sqlite3
import sys
from pathlib import Path

import duckdb

parser = argparse.ArgumentParser()
parser.add_argument("source")
parser.add_argument("target")
parser.add_argument("user", nargs="?")
parser.add_argument("--expected")
args = parser.parse_args()
source, target, user = args.source, args.target, args.user
src = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
dst = duckdb.connect(target, read_only=True, config={"memory_limit": "1500MB", "threads": "2"})
src_tables = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
sequences = dict(dst.execute("SELECT sequence_name, coalesce(last_value, start_value - 1) FROM duckdb_sequences()").fetchall())
bad = 0
print(f"{'table':20} {'sqlite rows':>12} {'duckdb rows':>12} {'after switch':>12}  result")
for (table,) in dst.execute("SELECT table_name FROM duckdb_tables() WHERE table_name LIKE 'co\\_%' ESCAPE '\\' "
                            "AND table_name NOT IN ('co_database_lock', 'co_duckdb_spatial_index') ORDER BY 1").fetchall():
    cols = dst.execute("SELECT column_name, data_type FROM duckdb_columns() WHERE table_name=? AND column_name<>'rowid' "
                       "ORDER BY column_index", [table]).fetchall()
    total, dup, dmax = dst.execute(f"SELECT count(*), count(*) - count(DISTINCT rowid), coalesce(max(rowid), 0) FROM {table}").fetchone()
    problems = []
    if dup:
        problems.append(f"{dup} duplicate row IDs")
    if sequences.get(f"{table}_rowid_seq", 0) < dmax:
        problems.append(f"sequence at {sequences.get(f'{table}_rowid_seq')} below max row ID {dmax}")
    if table not in src_tables:
        print(f"{table:20} {'-':>12} {total:>12,} {total:>12,}  {'OK' if not problems else problems}")
        bad += bool(problems)
        continue
    present = {r[1] for r in src.execute(f"PRAGMA table_info({table})")}
    smax = src.execute(f"SELECT coalesce(max(rowid), 0) FROM {table}").fetchone()[0]
    se, de = ["count(*)", "min(rowid)", "max(rowid)"], ["count(*)", "min(rowid)", "max(rowid)"]
    for name, typ in cols:
        if name not in present:
            continue
        q = f'"{name}"'
        if typ in ("VARCHAR", "BLOB"):
            se.append(f"sum(length(CAST({q} AS BLOB)))")
            de.append(f"sum({'strlen' if typ == 'VARCHAR' else 'octet_length'}({q}))")
        elif typ in ("DOUBLE", "FLOAT"):
            se.append(f"round(sum({q}), 0)")
            de.append(f"round(sum({q}), 0)")
        else:
            se.append(f"sum({q})")
            de.append(f"sum({q})")
    a = src.execute(f"SELECT {', '.join(se)} FROM {table}").fetchone()
    b = dst.execute(f"SELECT {', '.join(de)} FROM {table} WHERE rowid <= ?", [smax]).fetchone()
    for x, y, label in zip(a, b, ["count", "min rowid", "max rowid"] + [c for c, _ in cols if c in present]):
        if not (x == y or (x in (None, 0) and y in (None, 0)) or (isinstance(x, float) and abs(x - float(y or 0)) <= max(1.0, abs(x) * 1e-6))):
            problems.append(f"{label}: sqlite {x} duckdb {y}")
    after = dst.execute(f"SELECT count(*) FROM {table} WHERE rowid > ?", [smax]).fetchone()[0]
    print(f"{table:20} {a[0]:>12,} {total:>12,} {after:>12,}  {'OK' if not problems else problems}")
    bad += bool(problems)

if user:
    uid = dst.execute('SELECT rowid FROM co_user WHERE "user" = ?', [user]).fetchone()
    smax = src.execute("SELECT coalesce(max(rowid), 0) FROM co_block").fetchone()[0]
    if uid:
        before, after = dst.execute(f'SELECT count(*) FILTER (WHERE rowid <= {smax}), count(*) FILTER (WHERE rowid > {smax}) '
                                    f'FROM co_block WHERE "user" = {uid[0]}').fetchone()
        print(f"{user}: {before + after:,} block rows in DuckDB ({before:,} copied from SQLite, {after:,} written after the switch)")
duplicates = 0
for table, column in (("co_material_map", "material"), ("co_blockdata_map", "data"), ("co_art_map", "art"),
                      ("co_entity_map", "entity"), ("co_world", "world")):
    rows, ids, values = dst.execute(f"SELECT count(*), count(DISTINCT id), count(DISTINCT {column}) FROM {table}").fetchone()
    if rows != ids or rows != values:
        print(f"{table}: {rows} rows but {ids} distinct ids and {values} distinct values")
        duplicates += 1
print("identifier tables: ids and values unique" if not duplicates else "identifier tables: duplicates, see above")
bad += duplicates

if args.expected:
    world = Path(args.expected).stem.removeprefix("expected-")
    expected = {(int(x), int(y), int(z)): name for x, y, z, name in csv.reader(open(args.expected, encoding="utf-8"))}
    stored = dst.execute('SELECT b.x, b.y, b.z, m.material FROM co_block b JOIN co_world w ON w.id = b.wid '
                         'LEFT JOIN co_material_map m ON m.id = b.type JOIN co_user u ON u.rowid = b."user" '
                         'WHERE w.world = ? AND u."user" = ?', [world, "MigWriter"]).fetchall()
    found = {}
    for x, y, z, name in stored:
        found.setdefault((x, y, z), []).append(name)
    wrong = [(pos, name, found[pos]) for pos, name in expected.items() if pos in found and found[pos] != [name]]
    missing = [pos for pos in expected if pos not in found]
    extra = len(stored) - (len(expected) - len(missing))
    print(f"read-back: {len(expected):,} rows logged in {world}, {len(stored):,} stored, {len(wrong):,} read back as another material, "
          f"{len(missing):,} missing, {extra:,} extra")
    for pos, name, got in wrong[:5]:
        print(f"  {pos}: logged {name}, stored {got}")
    if wrong or missing or extra:
        bad += 1

lock = dst.execute("SELECT status FROM co_database_lock WHERE rowid = 1").fetchone()
print("lock row status:", lock[0] if lock else None)
print("check:", "OK" if not bad else f"{bad} problem(s)")
sys.exit(1 if bad else 0)
