"""Run the same benchmark sequence against one engine on the local Paper server.

Usage: python -u bench/compare.py <sqlite|duckdb> <database file> <label> [file to pick targets from]

Needs the lab venv (duckdb) and a built server (see README.md). Writes out/results-<label>-<engine>.json.
Users, spots and time windows are picked from the data itself (pass the DuckDB copy as the last
argument: the GROUP BY queries take minutes on a big SQLite file), so no player names live in this file:
top user and a mid user by block rows, the busiest chunk's centre, the last hour of data.
"""
import json, os, re, sqlite3, sys, time
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))
import lab  # noqa: E402

engine, dbfile, label = sys.argv[1], sys.argv[2], sys.argv[3]
LOG = lab.SERVER / "logs" / "latest.log"


def facts(path):
    """Pick benchmark targets from the database (both engines hold the same rows)."""
    if path.endswith(".duckdb"):
        import duckdb
        c = duckdb.connect(path, read_only=True, config={"memory_limit": "2GB", "threads": 2})  # spills to disk above this
        uc = '"user"'
    else:
        c = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        uc = "user"
    one = lambda s: c.execute(s).fetchone()
    tmin, tmax = one("SELECT min(time), max(time) FROM co_block")
    ranked = c.execute(f"SELECT {uc}, count(*) n FROM co_block WHERE {uc} IN (SELECT rowid FROM co_user WHERE {uc} NOT LIKE '#%') GROUP BY 1 ORDER BY n DESC").fetchall()
    top, mid = ranked[0], ranked[len(ranked) // 2]
    recent = one(f"SELECT {uc}, count(*) n FROM co_block WHERE time > {tmax - 3600} AND {uc} <> {top[0]} "
                 f"AND {uc} IN (SELECT rowid FROM co_user WHERE {uc} NOT LIKE '#%') GROUP BY 1 ORDER BY n DESC LIMIT 1")
    name = lambda uid: one(f"SELECT {uc} FROM co_user WHERE rowid = {uid}")[0]
    wid, cx, cz = one("SELECT wid, x >> 4, z >> 4 FROM co_block GROUP BY 1, 2, 3 ORDER BY count(*) DESC LIMIT 1")
    x, y, z = one(f"SELECT CAST(avg(x) AS INTEGER), CAST(avg(y) AS INTEGER), CAST(avg(z) AS INTEGER) FROM co_block WHERE wid={wid} AND x >> 4 = {cx} AND z >> 4 = {cz}")
    bw, bx, by, bz = one("SELECT wid, x, y, z FROM co_block GROUP BY 1, 2, 3, 4 ORDER BY count(*) DESC LIMIT 1")
    worlds = dict(c.execute("SELECT id, world FROM co_world").fetchall())
    out = dict(tmin=tmin, tmax=tmax, top=name(top[0]), top_rows=top[1], mid=name(mid[0]), mid_rows=mid[1],
               recent=name(recent[0]), recent_rows=recent[1], spot=(worlds[wid], x, y, z),
               block=(worlds[bw], bx, by, bz), worlds=sorted(worlds.values()))
    c.close()
    return out


def log_lines():
    return LOG.read_text(errors="replace").splitlines() if LOG.exists() else []


def run(command, expect, timeout=1800):
    """Send a console command and wait for the next log line containing `expect`."""
    before = len(log_lines())
    reply = (lab.rcon(command) or "").strip()
    if reply:
        print("  rcon:", re.sub(r"§.", "", reply)[:300], flush=True)
    started, seen = time.time(), 0
    while time.time() - started < timeout:
        new = log_lines()[before:]
        hits = [l for l in new if expect in l]
        if hits:
            print(hits[-1], flush=True)
            return hits[-1]
        errors = [l for l in new if "Exception" in l or "/ERROR]" in l]
        for line in errors[seen:]:
            print("  error:", line, flush=True)
        seen = len(errors)
        if any("Command exception" in l for l in errors):
            return None
        time.sleep(0.5)
    print(f"  timeout waiting for {expect!r} after {command!r}", flush=True)
    return None


def ms(line):
    m = re.search(r"ms=([\d.]+)", line or "")
    return float(m.group(1)) if m else None


def size(path):
    return sum(os.path.getsize(p) for p in [path, path + ".wal", path + "-wal"] if os.path.exists(p))


f = facts(sys.argv[4] if len(sys.argv) > 4 else dbfile)
anon = {k: v for k, v in f.items() if k not in ("top", "mid", "recent")}  # keep player names out of result files
print(json.dumps(anon))
res = {"engine": engine, "source": dbfile, "facts": anon, "steps": []}
target = str(lab.CP_DIR / lab.DB_FILES[engine])

lab.db(engine, dbfile)
t = time.time()
lab.start()
res["start_s"] = round(time.time() - t, 1)
res["rss_start_mb"] = lab.rss_mb(lab.pid())
for w in f["worlds"]:
    if w not in ("world", "world_nether", "world_the_end"):
        run(f"cpbench world {w}", "[cp-bench] world ")

w0 = f["spot"][0]
line = run(f"cpbench write 100000 {w0}", "[cp-bench] write ", 900)
m = re.search(r"queued_ms=(\d+) ms=(\d+) rows_per_s=(\d+)", line or "")
res["steps"].append({"step": "write 100k placements via API until stored", "first_ms": float(m.group(2)) if m else None,
                     "rows_per_s": int(m.group(3)) if m else None})

all_time = 99999999
last_hour = int(time.time()) - (f["tmax"] - 3600)
w, x, y, z = f["spot"]
bw, bx, by, bz = f["block"]
scenarios = [
    ("radius r=10 all time", f"radius {w} {x} {y} {z} 10 {all_time} limit=1000"),
    ("radius r=50 all time", f"radius {w} {x} {y} {z} 50 {all_time} limit=1000"),
    ("block inspector", f"block {bw} {bx} {by} {bz}"),
    (f"user top ({f['top_rows']:,} rows) all time", f"user {f['top']} {all_time} limit=1000"),
    (f"user mid ({f['mid_rows']:,} rows) all time", f"user {f['mid']} {all_time} limit=1000"),
    ("global last hour", f"time {last_hour} limit=1000"),
]
for title, args in scenarios:
    first = ms(run(f"cpbench {args}", f"[cp-bench] {args.split()[0]} "))
    rep = run(f"cpbench repeat 5 {args}", "[cp-bench] repeat ")
    med = re.search(r"median=([\d.]+)", rep or "")
    res["steps"].append({"step": title, "first_ms": first, "median_ms": float(med.group(1)) if med else None})
res["rss_after_lookups_mb"] = lab.rss_mb(lab.pid())

commands = [
    ("co lookup user mid all time", f"co lookup u:{f['mid']} t:{all_time}s", "[cp-bench] lookup "),
    ("co lookup user top all time", f"co lookup u:{f['top']} t:{all_time}s", "[cp-bench] lookup "),
    (f"co rollback recent user last hour ({f['recent_rows']:,} rows)", f"co rollback u:{f['recent']} t:{last_hour}s r:#global", "[cp-bench] rollback "),
    ("co restore same", f"co restore u:{f['recent']} t:{last_hour}s r:#global", "[cp-bench] restore "),
]
for title, command, expect in commands:
    res["steps"].append({"step": title, "first_ms": ms(run(command, expect))})

keep = int(time.time()) - int(f["tmax"] - 0.78 * (f["tmax"] - f["tmin"]))  # keep the newest 78% of the time range
res["size_before_purge_mb"] = round(size(target) / 1048576, 1)
for attempt in range(24):  # the restore's operation lock is released a moment after its timing line
    before = len(log_lines())
    line = run(f"co purge t:{keep}s", "[cp-bench] purge", 7200)
    if not any("already in progress" in l for l in log_lines()[before:]):
        break
    time.sleep(5)
res["steps"].append({"step": "co purge (drops oldest 22% of the time range)", "first_ms": ms(line)})
res["rss_end_mb"] = lab.rss_mb(lab.pid())
res["spatial_index_load_ms"] = [int(m) for line in log_lines() for m in re.findall(r"spatial-index load ms=(\d+)", line)]
lab.stop()
res["size_after_purge_mb"] = round(size(target) / 1048576, 1)
if engine == "sqlite":
    c = sqlite3.connect(f"file:{target}?mode=ro", uri=True)
else:
    import duckdb
    c = duckdb.connect(target, read_only=True)
res["block_rows_after_purge"] = c.execute("SELECT count(*) FROM co_block").fetchone()[0]
c.close()
out = LAB / "out" / f"results-{label}-{engine}.json"
out.write_text(json.dumps(res, indent=1))
print(json.dumps(res, indent=1))
