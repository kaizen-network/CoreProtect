"""Print two result files side by side: python bench/table.py out/results-<label>-sqlite.json out/results-<label>-duckdb.json"""
import json, sys
a, b = (json.load(open(p)) for p in sys.argv[1:3])
print(f"| Step | SQLite ms | DuckDB ms |\n| --- | ---: | ---: |")
f = lambda s: f"{s['median_ms']:,.0f} (first {s['first_ms']:,.0f})" if s.get("median_ms") is not None else (f"{s['first_ms']:,.0f}" if s["first_ms"] is not None else "n/a")
for x, y in zip(a["steps"], b["steps"]):
    print(f"| {x['step']} | {f(x)} | {f(y)} |")
for k in ("start_s", "rss_start_mb", "rss_after_lookups_mb", "rss_end_mb", "size_before_purge_mb", "size_after_purge_mb", "block_rows_after_purge"):
    print(f"| {k} | {a.get(k)} | {b.get(k)} |")
