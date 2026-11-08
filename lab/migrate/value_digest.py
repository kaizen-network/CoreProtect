"""Order-independent digest of CoreProtect's metadata columns, to compare a SQLite source with its migrated DuckDB file.
python value_digest.py <database.db> [dictionary.bin]       (SQLite; standard library only)
python value_digest.py <database.duckdb> <dictionary.bin>    (DuckDB)
With the dictionary from the jar (metadata-dictionary-1.bin), compressed values are decoded first; a file with compressed rows needs it.
Per column: rows, total uncompressed bytes, and the sum mod 2^64 of sha256(rowid:value)[:8]. Equal output = every value equal."""
import hashlib, json, sys, zlib

COLS = {"co_container": "metadata", "co_entity_container": "metadata", "co_item": "data"}
path = sys.argv[1]
zdict = open(sys.argv[2], "rb").read() if len(sys.argv) > 2 else None
if path.endswith(".duckdb"):
    import duckdb
    con = duckdb.connect(path, read_only=True)
else:
    import sqlite3
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
out = {}
for table, col in COLS.items():
    cur = con.execute(f"SELECT rowid, {col} FROM {table} WHERE {col} IS NOT NULL")
    rows = size = acc = compressed = 0
    while batch := cur.fetchmany(50000):
        for rowid, value in batch:
            value = bytes(value)
            if zdict is not None and value[:2] == b"CZ":
                compressed += 1
                d = zlib.decompressobj(zdict=zdict)
                value = d.decompress(value[4:])
                assert d.eof, f"{table} {rowid}: stream not complete"
            rows += 1
            size += len(value)
            acc = (acc + int.from_bytes(hashlib.sha256(b"%d:" % rowid + value).digest()[:8], "big")) % 2**64
    out[f"{table}.{col}"] = {"rows": rows, "bytes": size, "digest": acc, **({"compressed": compressed} if zdict is not None else {})}
    print(table, out[f"{table}.{col}"], file=sys.stderr, flush=True)
print(json.dumps(out))
