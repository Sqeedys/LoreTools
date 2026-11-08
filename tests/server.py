"""Isolated loopback Fabric server fixture; never targets an existing world.

prepare creates a marked ignored directory; run logs to server-console.log.
command uses authenticated loopback RCON, including `command stop` for shutdown.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess


PROJECT = Path(__file__).resolve().parents[1]
REPOSITORY = PROJECT
MARKER = ".loretools-test-server.json"


def read_marker(directory: Path) -> dict:
    if not directory.is_relative_to((REPOSITORY / "local").resolve()):
        raise ValueError("Test server directory must stay under this repository's ignored local directory")
    marker = json.loads((directory / MARKER).read_text(encoding="utf-8"))
    if marker.get("fixture") != "loretools-loopback-26.3":
        raise ValueError("Not a Lore Tools test server")
    return marker


def builder():
    spec = importlib.util.spec_from_file_location("loretools_build", PROJECT / "build.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_stopped(marker: dict) -> None:
    for port in (marker["port"], marker["rcon_port"]):
        with socket.socket() as connection:
            if connection.connect_ex(("127.0.0.1", port)) == 0:
                raise ValueError(f"Local fixture port {port} is in use; stop the server first")


def prepare(args) -> None:
    if not args.accept_eula:
        raise ValueError("Explicit --accept-eula required for this Minecraft test fixture")
    if not (1024 <= args.port <= 65535 and 1024 <= args.rcon_port <= 65535) or args.port == args.rcon_port:
        raise ValueError("Choose distinct unprivileged TCP ports")
    directory = args.server_dir.resolve()
    if directory.exists() and any(directory.iterdir()):
        raise ValueError("prepare requires an empty/new directory; existing fixture/world is preserved")
    # Keep every generated artifact in the ignored local test tree.
    if not directory.is_relative_to((REPOSITORY / "local").resolve()):
        raise ValueError("Test server directory must be under this repository's ignored local directory")
    marker = {"fixture": "loretools-loopback-26.3", "port": args.port, "rcon_port": args.rcon_port,
              "rcon_password": secrets.token_hex(24)}
    require_stopped(marker)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / MARKER).write_text(json.dumps(marker, indent=2) + "\n", encoding="utf-8")
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    properties = {
        "server-ip": "127.0.0.1", "server-port": args.port, "online-mode": "false",
        "enable-rcon": "true", "rcon.port": args.rcon_port, "rcon.password": marker["rcon_password"],
        "broadcast-rcon-to-ops": "false", "enable-query": "false", "enable-status": "true",
        "level-name": "loretools-fixture-v1", "level-seed": "263", "level-type": "minecraft:flat",
        "generator-settings": json.dumps({"biome": "minecraft:plains", "layers": [
            {"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:dirt", "height": 2},
            {"block": "minecraft:grass_block", "height": 1}]}, separators=(",", ":")),
        "generate-structures": "false", "view-distance": "3", "simulation-distance": "3",
        "pause-when-empty-seconds": "0",
        "max-players": "8", "spawn-protection": "0", "gamemode": "creative", "difficulty": "peaceful",
        "allow-flight": "true", "enforce-secure-profile": "false", "sync-chunk-writes": "true",
        "motd": "Disposable Lore Tools integration fixture",
    }
    (directory / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")
    print("Prepared isolated loopback Minecraft 26.3 fixture")


def run(args) -> None:
    directory = args.server_dir.resolve()
    marker = read_marker(directory)
    require_stopped(marker)
    properties = dict(line.split("=", 1) for line in (directory / "server.properties").read_text(encoding="utf-8").splitlines()
                      if "=" in line and not line.startswith("#"))
    expected = {"server-ip": "127.0.0.1", "server-port": str(marker["port"]),
                "rcon.port": str(marker["rcon_port"]), "rcon.password": marker["rcon_password"],
                "online-mode": "false", "level-name": "loretools-fixture-v1"}
    if any(properties.get(key) != value for key, value in expected.items()):
        raise ValueError("Fixture network/world settings differ from the isolated setup")
    build = builder()
    cache = args.cache.resolve()
    runtime = args.runtime.resolve()
    game_libraries = build.validate_runtime(runtime)
    build.dependencies(cache)
    metadata = json.loads((PROJECT / "src/main/resources/fabric.mod.json").read_text(encoding="utf-8"))
    artifact = PROJECT / "build/libs" / f"{metadata['id']}-{metadata['version']}.jar"
    if not artifact.exists():
        raise ValueError("Build the mod before starting this test server")
    mods = directory / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(artifact, mods / artifact.name)
    shutil.copy2(cache / "api.jar", mods / "fabric-api.jar")
    provider = mods / "LuckPerms-Fabric-5.5.85.jar"
    if args.with_luckperms:
        shutil.copy2(cache / provider.name, provider)
    elif provider.exists():
        raise ValueError("This fixture already contains LuckPerms; use a separate fixture for provider-absent tests")
    harness = mods / "loretools-integration.jar"
    if args.integration_phase:
        shutil.copy2(PROJECT / "build/libs/loretools-integration.jar", harness)
    elif harness.exists():
        raise ValueError("This fixture has an integration harness; supply its phase or use a separate fixture")
    extra = [cache / name for name, _, _ in build.ARTIFACTS if name == "loader.jar" or name == "sponge-mixin.jar" or name.startswith("asm")]
    java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    command = [str(java), "-Xms256M", "-Xmx1536M", "-Dfile.encoding=UTF-8",
               "-Dfabric.gameJarPath=" + str(runtime / "game.jar"), "-cp",
               os.pathsep.join(map(str, [*game_libraries, *extra])),
               "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
    if args.integration_phase:
        command.insert(1, "-Dloretools.integration.phase=" + args.integration_phase)
    log = directory / "server-console.log"
    receipt = directory / "local" / f"loretools-integration-{args.integration_phase}.txt"
    previous_receipt_time = receipt.stat().st_mtime_ns if receipt.exists() else None
    artifact_hash = build.digest(mods / artifact.name)
    harness_hash = build.digest(harness) if args.integration_phase else None
    print("Running loopback test server; output is in server-console.log", flush=True)
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
    if args.integration_phase:
        current = receipt.read_text(encoding="utf-8") if receipt.exists() else ""
        fresh = receipt.exists() and receipt.stat().st_mtime_ns != previous_receipt_time
        passed = result.returncode == 0 and fresh and current.startswith(f"PASS phase={args.integration_phase} ")
        record = {"phase": args.integration_phase, "passed": passed, "server_exit": result.returncode,
                  "fresh_receipt": fresh, "mod_sha256": artifact_hash, "harness_sha256": harness_hash,
                  "completed_utc": datetime.now(timezone.utc).isoformat(), "receipt": current}
        (directory / f"integration-run-{args.integration_phase}.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(log, directory / f"server-console-{args.integration_phase}.log")
        print(current.splitlines()[0] if current and fresh else "FAIL: no fresh integration receipt")
        if not passed:
            raise SystemExit(1)
    raise SystemExit(result.returncode)


def receive(connection: socket.socket, length: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < length:
        chunk = connection.recv(length - len(chunks))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        chunks.extend(chunk)
    return bytes(chunks)


def packet(connection: socket.socket) -> tuple[int, int, str]:
    length, = struct.unpack("<i", receive(connection, 4))
    if not 10 <= length <= 4 * 1024 * 1024:
        raise ValueError("Invalid RCON response size")
    data = receive(connection, length)
    request_id, kind = struct.unpack("<ii", data[:8])
    return request_id, kind, data[8:-2].decode("utf-8", errors="replace")


def send_packet(connection: socket.socket, request_id: int, kind: int, value: str) -> None:
    body = struct.pack("<ii", request_id, kind) + value.encode("utf-8") + b"\0\0"
    connection.sendall(struct.pack("<i", len(body)) + body)


def command(args) -> None:
    marker = read_marker(args.server_dir.resolve())
    with socket.create_connection(("127.0.0.1", marker["rcon_port"]), timeout=10) as connection:
        send_packet(connection, 1, 3, marker["rcon_password"])
        request_id, kind, _ = packet(connection)
        if request_id != 1 or kind != 2:
            raise ValueError("RCON authentication failed")
        send_packet(connection, 2, 2, args.command)
        request_id, kind, response = packet(connection)
        if request_id != 2 or kind != 0:
            raise ValueError("Unexpected RCON command response")
        print(response)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", type=Path, default=REPOSITORY / "local/lore-server")
    subparsers = parser.add_subparsers(dest="action", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--accept-eula", action="store_true")
    prepare_parser.add_argument("--port", type=int, default=25576)
    prepare_parser.add_argument("--rcon-port", type=int, default=25586)
    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("--java-home", type=Path, required=True)
    run_parser.add_argument("--runtime", type=Path, default=REPOSITORY / "local/lore-deps/runtime-26.3")
    run_parser.add_argument("--cache", type=Path, default=REPOSITORY / "local/lore-deps")
    run_parser.add_argument("--with-luckperms", action="store_true")
    run_parser.add_argument("--integration-phase", choices=("initial", "restart", "external", "external_restart"))
    command_parser = subparsers.add_parser("command")
    command_parser.add_argument("command")
    args = parser.parse_args()
    {"prepare": prepare, "run": run, "command": command}[args.action](args)


if __name__ == "__main__":
    main()
