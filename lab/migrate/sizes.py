"""Space use of a CoreProtect DuckDB file: used vs free blocks, and data bytes per table. Usage: sizes.py <file.duckdb>"""
import sys
import duckdb
c = duckdb.connect(sys.argv[1], read_only=True, config={"memory_limit": "1500MB", "threads": "2"})
print(c.execute("SELECT block_size, total_blocks, used_blocks, free_blocks, database_size FROM pragma_database_size()").fetchall())
bs = c.execute("SELECT block_size FROM pragma_database_size()").fetchone()[0]
rows = []
for (t,) in c.execute("SELECT table_name FROM duckdb_tables() ORDER BY 1").fetchall():
    n, blocks = c.execute(f"SELECT count(DISTINCT block_id), count(*) FROM pragma_storage_info('{t}') WHERE persistent").fetchone()
    rows.append((t, n * bs / 1048576))
for t, mb in sorted(rows, key=lambda r: -r[1])[:12]:
    print(f"{t:32s} {mb:9.1f} MB")
