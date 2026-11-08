"""CoreProtect SQLite vs DuckDB test bed. Run `python lab.py` for the command list."""
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

LAB = Path(__file__).resolve().parent
SERVER = LAB / os.environ.get("LAB_SERVER", "server")  # LAB_SERVER: run a second, separate test server
FORK = Path(os.environ.get("LAB_FORK", LAB.parent))  # the repo root; LAB_FORK: build from another worktree
PAPER_JAR = "paper-26.3-237.jar"
JAVA_ARGS = ["java", "-Xms2G", "-Xmx2G", "-Dcpbench=true", "-jar", PAPER_JAR, "--nogui"]
CP_DIR = SERVER / "plugins" / "CoreProtect"
DB_FILES = {"sqlite": "database.db", "duckdb": "database.duckdb"}
SIDECARS = {"sqlite": ["-journal", "-wal", "-shm"], "duckdb": [".wal"]}
PID_FILE = SERVER / "server.pid"

USAGE = """commands:
  setup                      write eula, server.properties (random RCON password), CoreProtect + bStats config
  build                      build the plugin (repo root) and the bench plugin (JDK 25), copy both jars into server/plugins
  db <sqlite|duckdb> [FILE|fresh]
                             server stopped: set database-type; FILE copies a database in, fresh deletes the current one
  start                      start the server in the background, wait for "Done", print RSS
  stop                       send "stop" over RCON and wait for the process to exit
  rcon <command...>          send one console command over RCON
  rss                        print the server process working set
  bench [N]                  print the last N [cp-bench] lines of logs/latest.log (default all)"""


def props():
    text = (SERVER / "server.properties").read_text(encoding="utf-8")
    return dict(re.findall(r"^([\w.\-]+)=(.*)$", text, re.M))


def pid():
    if not PID_FILE.exists():
        return None
    value = int(PID_FILE.read_text())
    if os.name != "nt":
        try:  # an exited child not yet reaped by its parent (state Z) counts as stopped
            return value if Path(f"/proc/{value}/stat").read_text().rsplit(") ", 1)[1][0] != "Z" else None
        except (FileNotFoundError, IndexError):
            return None
    out = subprocess.run(["tasklist", "/FI", f"PID eq {value}", "/FO", "CSV", "/NH"], capture_output=True, text=True).stdout
    return value if f'"{value}"' in out else None


def rss_mb(process_id):
    if os.name != "nt":
        return int(re.search(r"VmRSS:\s+(\d+)", Path(f"/proc/{process_id}/status").read_text()).group(1)) // 1024
    out = subprocess.run(["tasklist", "/FI", f"PID eq {process_id}", "/FO", "CSV", "/NH"], capture_output=True, text=True).stdout
    kb = int(re.sub(r"\D", "", out.strip().split('","')[-1]))  # "Mem Usage" column = working set in KB
    return kb // 1024


def rcon(command):
    p = props()

    def read(sock, n):
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            data += chunk
        return data

    def send(sock, request_id, kind, body):
        payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\0\0"
        sock.sendall(struct.pack("<i", len(payload)) + payload)

    def receive(sock):
        length = struct.unpack("<i", read(sock, 4))[0]
        request_id = struct.unpack("<ii", read(sock, 8))[0]
        return request_id, read(sock, length - 8)[:-2].decode("utf-8", "replace")

    with socket.create_connection(("127.0.0.1", int(p["rcon.port"])), timeout=60) as sock:
        send(sock, 1, 3, p["rcon.password"])
        if receive(sock)[0] == -1:
            sys.exit("RCON login failed")
        send(sock, 2, 2, command)
        # Reads only the first response packet (4 KB); bench output goes to the log anyway
        return re.sub("\u00a7.", "", receive(sock)[1])


def setup():
    SERVER.mkdir(exist_ok=True)
    (SERVER / "eula.txt").write_text("eula=true\n")
    if not (SERVER / "server.properties").exists():
        (SERVER / "server.properties").write_text("\n".join([
            "online-mode=false", "server-ip=127.0.0.1", "server-port=25565",
            "enable-rcon=true", "rcon.port=25575", f"rcon.password={secrets.token_urlsafe(24)}",
            "level-type=minecraft\\:flat", "generate-structures=false", "view-distance=2",
            "simulation-distance=2", "spawn-protection=0", "max-players=2", "motd=cp-lab", ""]))
    CP_DIR.mkdir(parents=True, exist_ok=True)
    if not (CP_DIR / "config.yml").exists():
        (CP_DIR / "config.yml").write_text("database-type: sqlite\ncheck-updates: false\n")
    (SERVER / "plugins" / "bStats").mkdir(exist_ok=True)
    (SERVER / "plugins" / "bStats" / "config.yml").write_text("enabled: false\n")
    print("server dir ready; download", PAPER_JAR, "into it (see README.md)")


def build():
    # JAVA_HOME must point at a JDK 25: paper-api 26.3 needs JDK 25 to compile
    mvn = shutil.which("mvn")
    subprocess.run([mvn, "-B", "-q", "-Dproject.branch=fork", "clean", "package"], cwd=FORK, check=True)
    subprocess.run([mvn, "-B", "-q", "clean", "package"], cwd=LAB / "bench-plugin", check=True)
    plugins = SERVER / "plugins"
    shutil.copyfile(FORK / "target" / "CoreProtect-25.1.jar", plugins / "CoreProtect.jar")
    shutil.copyfile(LAB / "bench-plugin" / "target" / "CpBench-1.0.jar", plugins / "CpBench.jar")
    print("copied CoreProtect.jar and CpBench.jar into", plugins)


def db(engine, source=None):
    if pid():
        sys.exit("stop the server first")
    if engine not in DB_FILES:
        sys.exit("engine must be sqlite or duckdb")
    target = CP_DIR / DB_FILES[engine]
    if source:
        # Remove the old file and its sidecars, or the engine may replay a stale WAL onto the new file.
        for path in [target] + [Path(str(target) + s) for s in SIDECARS[engine]]:
            path.unlink(missing_ok=True)
        if source != "fresh":
            shutil.copyfile(source, target)
            for s in SIDECARS[engine]:
                if Path(source + s).exists():
                    shutil.copyfile(source + s, str(target) + s)
    config = CP_DIR / "config.yml"
    text = config.read_text(encoding="utf-8") if config.exists() else "check-updates: false\n"
    if re.search(r"^database-type:", text, re.M):
        text = re.sub(r"^database-type:.*$", f"database-type: {engine}", text, flags=re.M)
    else:
        text = f"database-type: {engine}\n" + text
    config.write_text(text, encoding="utf-8")
    print(f"database-type: {engine}", f"({target.name} {'present' if target.exists() else 'absent'})")


def start():
    if pid():
        sys.exit("server already running")
    console = SERVER / "console.out"
    with open(console, "w") as out:
        process = subprocess.Popen(JAVA_ARGS, cwd=SERVER, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
                                   start_new_session=os.name != "nt")
    PID_FILE.write_text(str(process.pid))
    started = time.time()
    while "Done (" not in console.read_text(errors="replace"):
        if process.poll() is not None:
            sys.exit(f"server exited with code {process.returncode}, see {console}")
        if time.time() - started > 600:
            sys.exit("no Done line after 600 s")
        time.sleep(1)
    time.sleep(5)  # let plugin startup threads settle before measuring
    print(f"started pid={process.pid} in {time.time() - started:.0f}s, rss={rss_mb(process.pid)} MB")


def stop():
    process_id = pid()
    if not process_id:
        sys.exit("server not running")
    rcon("stop")
    for _ in range(300):
        if not pid():
            PID_FILE.unlink(missing_ok=True)
            print("stopped")
            return
        time.sleep(1)
    sys.exit("server still running after 300 s")


def bench(count=None):
    lines = [line for line in (SERVER / "logs" / "latest.log").read_text(errors="replace").splitlines() if "[cp-bench]" in line]
    print("\n".join(lines[-int(count):] if count else lines))


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        sys.exit(USAGE)
    command, rest = args[0], args[1:]
    if command == "rcon":
        print(rcon(" ".join(rest)))
    elif command == "rss":
        process_id = pid() or sys.exit("server not running")
        print(f"pid={process_id} rss={rss_mb(process_id)} MB")
    elif command in ("setup", "build", "start", "stop", "db", "bench"):
        globals()[command](*rest)
    else:
        sys.exit(USAGE)
