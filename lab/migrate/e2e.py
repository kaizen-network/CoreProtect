"""Drive one live migration on a running lab server and record what happened.

Usage (server running on SQLite, worlds loaded):
  LAB_SERVER=server-mig python migrate/e2e.py "<writer command or ->" [migrate options...]

Sends the writer command (for example `migwrite 40000 200 migbench`, which keeps logging during phase 1, the pause
and after the switch), then `co migrate-duckdb <options>`. With `--test-hold-pause <ms>` in the options it also sends
`migwrite 2000 2000 migbench` once the pause has started. With WRITER_AT=verify in the environment the writer
command is sent when phase 1's copy ends instead (about 10 s before the pause). Samples the server RSS every 2 s and `mspt` every 10 s
until the migration logs Done, Failed or Cancelled, then prints the migration lines and the samples.
"""
import os
import re
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import lab  # noqa: E402

writer = sys.argv[1]
options = " ".join(sys.argv[2:])
LOG = lab.SERVER / "logs" / "latest.log"
start_line = len(LOG.read_text(errors="replace").splitlines())
rss, mspt, stop = [], [], threading.Event()


def new_lines():
    return LOG.read_text(errors="replace").splitlines()[start_line:]


def sample():
    process_id, last_mspt = lab.pid(), 0
    while not stop.is_set():
        rss.append((time.time(), lab.rss_mb(process_id)))
        if time.time() - last_mspt >= 10:
            reply = re.sub("§.", "", lab.rcon("mspt"))
            match = re.search(r"([\d,]+)/([\d,]+)/([\d,]+), ([\d,]+)/([\d,]+)/([\d,]+)", reply)
            if match:
                mspt.append((time.time(), match.group(1).replace(",", "."), match.group(3).replace(",", ".")))
            last_mspt = time.time()
        time.sleep(2)


def wait_for(pattern, timeout=3600):
    started = time.time()
    while time.time() - started < timeout:
        hits = [line for line in new_lines() if re.search(pattern, line)]
        if hits:
            return hits[-1]
        time.sleep(0.2)
    raise SystemExit(f"timeout waiting for {pattern!r}")


threading.Thread(target=sample, daemon=True).start()
time.sleep(4)
writer_at_verify = os.environ.get("WRITER_AT") == "verify"
if writer != "-" and not writer_at_verify:
    lab.rcon(writer)
time.sleep(2)
t0 = time.time()
print("rcon:", lab.rcon(f"co migrate-duckdb {options}".strip()).strip())
if writer != "-" and writer_at_verify:
    if "copied" in wait_for(r"migrate-duckdb: (Phase 1 copied|Failed|Cancelled)"):
        lab.rcon(writer)
        print(f"writer sent at +{time.time() - t0:.1f} s")
if "--test-hold-pause" in options:
    if "holding" in wait_for(r"migrate-duckdb: (Test option: holding|Failed|Cancelled)"):
        lab.rcon("migwrite 2000 2000 migbench")
        print(f"pause started at +{time.time() - t0:.1f} s, sent migwrite 2000 during it")
wait_for(r"migrate-duckdb: (Done in|Failed|Cancelled)")
time.sleep(6)
stop.set()

for line in new_lines():
    if ("migrate-duckdb" in line and "% of row IDs" not in line) or "[cp-bench]" in line or "mig-writer" in line or "/ERROR]" in line:
        print(line)
before = [mb for t, mb in rss if t < t0]
during = [mb for t, mb in rss if t >= t0]
print(f"rss MB before: {before[-1] if before else '?'}, during: min {min(during)} max {max(during)}, last {during[-1]}")
for t, avg, peak in mspt:
    print(f"mspt {t - t0:+7.1f} s: 5 s avg {avg} ms, 5 s max {peak} ms")
