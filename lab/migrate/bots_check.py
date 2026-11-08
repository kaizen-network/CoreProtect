"""Compare each bot's confirmed placements and breaks with CoreProtect's block rows in the migrated DuckDB file.

Usage: python bots_check.py <database.duckdb> <bots start, unix seconds> <stats.json>
"""
import json, sys

import duckdb

db, start, stats_file = sys.argv[1], int(sys.argv[2]), sys.argv[3]
stats = json.load(open(stats_file))["stats"]
c = duckdb.connect(db, read_only=True, config={"memory_limit": "512MB", "threads": "2"})
rows = c.execute("""SELECT u."user", b.action, count(*) FROM co_block b JOIN co_user u ON u.rowid = b."user"
                    WHERE u."user" LIKE 'LabBot%' AND b.time >= ? GROUP BY 1, 2""", [start]).fetchall()
logged = {}
for name, action, n in rows:
    logged.setdefault(name, {})[action] = n
bad = 0
for name in sorted(stats):
    s, l = stats[name], logged.get(name, {})
    ok = s["placed"] == l.get(1, 0) and s["broken"] == l.get(0, 0)
    bad += not ok
    print(f"{name}: placed {s['placed']} vs rows {l.get(1, 0)}, broken {s['broken']} vs rows {l.get(0, 0)}{'' if ok else '  MISMATCH'}")
tot = lambda k: sum(s[k] for s in stats.values())
print("total placed", tot("placed"), "rows", sum(l.get(1, 0) for l in logged.values()),
      "| broken", tot("broken"), "rows", sum(l.get(0, 0) for l in logged.values()), "| bots with mismatch", bad)
cont = c.execute("""SELECT count(*) FROM co_container b JOIN co_user u ON u.rowid = b."user" WHERE u."user" LIKE 'LabBot%' AND b.time >= ?""", [start]).fetchone()[0]
print("container rows from bots", cont, "| chest uses counted by bots", tot("chest"))
sys.exit(1 if bad else 0)
