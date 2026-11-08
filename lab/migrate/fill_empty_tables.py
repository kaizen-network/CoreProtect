"""Put synthetic rows into the empty tables of a CoreProtect SQLite working copy (server stopped).

Usage: python migrate/fill_empty_tables.py <database.db>

The lab data has no rows in sign, skull, entity, entity_spawn, entity_container, entity_interaction,
entity_map and art_map, so a migration test would never touch DOUBLE and FLOAT columns, the UNIQUE
constraints or the whole-table copy of entity_spawn. Only tables with 0 rows are filled. Never run it on
a file in out/ or on a live server's database.
"""
import random
import sqlite3
import sys
import time
import uuid

ROWS = {"co_entity_spawn": 20000, "co_entity_container": 5000, "co_entity_interaction": 5000, "co_sign": 5000,
        "co_skull": 1000, "co_entity": 2000, "co_entity_map": 20, "co_art_map": 20}
SMALL = {"action", "rolled_back", "removed", "waxed", "face"}  # TINYINT in DuckDB
TEXTS = ["", "stone", "Ünïcødé ✓", "line with spaces", "→ arrow", "x" * 40]

random.seed(7)
con = sqlite3.connect(sys.argv[1])
now = int(time.time())
spawn_ids = []
for table, count in ROWS.items():
    if con.execute(f"SELECT count(*) FROM {table}").fetchone()[0]:
        print(f"{table}: has rows, skipped")
        continue
    cols = [(r[1], r[2]) for r in con.execute(f"PRAGMA table_info({table})") if not r[5]]  # skip INTEGER PRIMARY KEY
    kill_ids = random.sample(range(1, 10 * count), count)
    rows = []
    for i in range(count):
        row = []
        for name, typ in cols:
            if name == "time":
                value = now - random.randint(0, 86400 * 30)
            elif name == "uuid":
                value = str(uuid.UUID(int=random.getrandbits(128)))
            elif name == "kill_rowid":
                value = kill_ids[i] if random.random() < 0.3 else None
            elif name == "entity_spawn_rowid":
                value = random.choice(spawn_ids)
            elif name == "id":
                value = i + 1
            elif name in ("wid", "current_wid"):
                value = random.randint(1, 4)
            elif name == "user":
                value = random.randint(1, 1000)
            elif name in SMALL or (table == "co_sign" and name == "data"):
                value = random.randint(0, 1)
            elif typ == "INTEGER":
                value = random.randint(-30000, 30000)
            elif typ == "REAL":
                value = random.uniform(-30000, 30000)
            elif typ == "TEXT":
                value = random.choice(TEXTS + [None])
            else:  # BLOB
                value = random.choice([None, random.randbytes(random.randint(0, 64))])
            row.append(value)
        rows.append(row)
    con.executemany(f"INSERT INTO {table} ({', '.join(c for c, _ in cols)}) VALUES ({', '.join('?' * len(cols))})", rows)
    if table == "co_entity_spawn":
        con.execute("UPDATE co_entity_spawn SET removed = 1")  # keep the plugin's live entity tracking away from them
        spawn_ids = [r[0] for r in con.execute("SELECT rowid FROM co_entity_spawn")]
    print(f"{table}: {count} rows")
con.commit()
con.close()
