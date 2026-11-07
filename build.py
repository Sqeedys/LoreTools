"""Pinned standalone Java 25 build for the unobfuscated Minecraft 26.3 server.

Downloads stay in ignored local storage. No game, loader, or dependency classes
are bundled into the resulting mod. Does not launch a server or accept its EULA.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile


PROJECT = Path(__file__).resolve().parent
REPOSITORY = PROJECT
GAME_SHA256 = "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6"
BUNDLE_SHA1 = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c"
ARTIFACTS = (
    ("loader.jar", "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar",
     "d0c5a082e07410aaff9de7ab196aa6b4236002870f9ba3ad5c1260fcce5110ad"),
    ("api.jar", "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.2+26.3/fabric-api-0.165.2+26.3.jar",
     "2612a078a5daa06d9a14cabad40fe66de36b8cb22ee8cdf346d5d9894478c392"),
    ("LuckPerms-Fabric-5.5.85.jar", "https://cdn.modrinth.com/data/Vebnzrzj/versions/DzQPkkXY/LuckPerms-Fabric-5.5.85.jar",
     "18598fcb95a83f6723d8bc68a905500ed5ecac92e9909ce1f25409cdebe2df49"),
    ("sponge-mixin.jar", "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar",
     "03f0065023bfc3045d71bfb99872451d9ad5a0800a5a0dac34ab8c7d5c134f5d"),
    ("asm.jar", "https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
     "ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb"),
    ("asm-analysis.jar", "https://maven.fabricmc.net/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar",
     "dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc"),
    ("asm-commons.jar", "https://maven.fabricmc.net/org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar",
     "6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895"),
    ("asm-tree.jar", "https://maven.fabricmc.net/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar",
     "3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b"),
    ("asm-util.jar", "https://maven.fabricmc.net/org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar",
     "1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c"),
)


def digest(path: Path, algorithm: str = "sha256") -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def download(path: Path, url: str, expected: str, algorithm: str = "sha256") -> None:
    if path.exists():
        if digest(path, algorithm) != expected:
            raise ValueError(f"Dependency hash mismatch: {path.name}; remove the damaged cache file explicitly")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".download", delete=False) as temporary:
        staging = Path(temporary.name)
    try:
        urllib.request.urlretrieve(url, staging)
        if digest(staging, algorithm) != expected:
            raise ValueError(f"Downloaded dependency hash mismatch: {path.name}")
        staging.replace(path)
    finally:
        staging.unlink(missing_ok=True)


def prepare_runtime(cache: Path) -> Path:
    """Extract only pinned server/libraries, verifying the bundler's hashes."""
    bundle = cache / "minecraft-26.3-server.jar"
    download(bundle, f"https://piston-data.mojang.com/v1/objects/{BUNDLE_SHA1}/server.jar", BUNDLE_SHA1, "sha1")
    runtime = cache / "runtime-26.3"
    runtime.mkdir(parents=True, exist_ok=True)
    hashes = {}
    with zipfile.ZipFile(bundle) as archive:
        for table, prefix in (("versions", "META-INF/versions/"), ("libraries", "META-INF/libraries/")):
            rows = archive.read(f"META-INF/{table}.list").decode("utf-8").splitlines()
            if table == "versions" and len(rows) != 1:
                raise ValueError("Unexpected server bundle versions")
            for index, row in enumerate(rows):
                expected, _, member = row.split("\t")
                data = archive.read(prefix + member)
                if hashlib.sha256(data).hexdigest() != expected:
                    raise ValueError("Server bundle member hash mismatch")
                relative = "game.jar" if table == "versions" else f"lib/{index:04d}.jar"
                target = runtime / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists() and digest(target) != expected:
                    raise ValueError(f"Existing runtime differs: {relative}")
                if not target.exists():
                    target.write_bytes(data)
                hashes[relative] = expected
    (runtime / "runtime.json").write_text(json.dumps({"minecraft_version": "26.3", "artifacts": hashes}, indent=2) + "\n", encoding="utf-8")
    return runtime


def validate_runtime(runtime: Path) -> list[Path]:
    manifest = json.loads((runtime / "runtime.json").read_text(encoding="utf-8"))
    if manifest.get("minecraft_version") != "26.3" or digest(runtime / "game.jar") != GAME_SHA256:
        raise ValueError("Exact unmodified Minecraft 26.3 runtime required")
    libraries = sorted((runtime / "lib").glob("*.jar"))
    if not libraries:
        raise ValueError("Runtime contains no libraries")
    for path in libraries:
        relative = path.relative_to(runtime).as_posix()
        expected = manifest.get("artifacts", {}).get(relative)
        if not expected or digest(path) != expected:
            raise ValueError(f"Runtime library hash mismatch or missing manifest entry: {relative}")
    return [runtime / "game.jar", *libraries]


def dependencies(cache: Path) -> list[Path]:
    paths = []
    for name, url, expected in ARTIFACTS:
        path = cache / name
        download(path, url, expected)
        paths.append(path)
        if name == "api.jar":
            with zipfile.ZipFile(path) as archive:
                for member in sorted(archive.namelist()):
                    if member.startswith("META-INF/jars/") and member.endswith(".jar"):
                        target = cache / "fabric-modules" / Path(member).name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(archive.read(member))
                        paths.append(target)
    return paths


def compile_sources(javac: Path, classpath: str, output: Path, sources: list[Path], work: Path) -> None:
    # javac argument files avoid Windows' command-length limit and preserve spaces.
    arguments = ["--release", "25", "-encoding", "UTF-8", "-g:source,lines", "-proc:none", "-cp", classpath, "-d", str(output), *map(str, sources)]
    argfile = work / f"{output.name}.args"
    argfile.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in arguments), encoding="utf-8")
    subprocess.run([str(javac), "@" + str(argfile)], check=True)


def package(classes: Path, resources: Path, staged: Path) -> None:
    with zipfile.ZipFile(staged, "w", zipfile.ZIP_DEFLATED) as archive:
        for base in (classes, resources):
            for path in sorted(base.rglob("*")):
                if path.is_file():
                    info = zipfile.ZipInfo(path.relative_to(base).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o100644 << 16
                    archive.writestr(info, path.read_bytes())
        license_file = PROJECT / "LICENSE"
        if license_file.exists():
            info = zipfile.ZipInfo("LICENSE", date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, license_file.read_bytes())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True, help="JDK 25 installation")
    runtime_options = parser.add_mutually_exclusive_group(required=True)
    runtime_options.add_argument("--runtime", type=Path, help="Prepared runtime with game.jar, lib/, runtime.json")
    runtime_options.add_argument("--prepare-runtime", action="store_true", help="Download/extract pinned official server into cache")
    parser.add_argument("--cache", type=Path, default=REPOSITORY / "local/lore-deps")
    parser.add_argument("--prepare-only", action="store_true", help="Verify/download build dependencies without compilation")
    parser.add_argument("--integration", action="store_true", help="Also build the separate disposable-server integration harness")
    args = parser.parse_args()
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    suffix = ".exe" if os.name == "nt" else ""
    javac = args.java_home.resolve() / "bin" / ("javac" + suffix)
    java = args.java_home.resolve() / "bin" / ("java" + suffix)
    version = subprocess.run([str(javac), "-version"], text=True, capture_output=True, check=True)
    if not re.search(r"javac 25(?:\.|\s|$)", version.stdout + version.stderr):
        raise ValueError("JDK 25 is required for this pinned build")
    runtime = prepare_runtime(cache) if args.prepare_runtime else args.runtime.resolve()
    classpath = os.pathsep.join(map(str, [*validate_runtime(runtime), *dependencies(cache)]))
    if args.prepare_only:
        print("Verified Minecraft 26.3, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, LuckPerms 5.5.85")
        return
    sources = sorted((PROJECT / "src/main/java").rglob("*.java"))
    if not sources:
        raise ValueError("No main Java sources")
    build = PROJECT / "build"
    build.mkdir(parents=True, exist_ok=True)
    # Every build uses fresh class directories, preventing stale classes in the jar.
    with tempfile.TemporaryDirectory(prefix="compile-", dir=build) as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        compile_sources(javac, classpath, classes, sources, work)
        test_root = PROJECT / "src/test/java"
        tests = sorted(test_root.rglob("*.java"))
        if tests:
            testclasses = work / "test-classes"
            testclasses.mkdir()
            testclasspath = os.pathsep.join([str(classes), classpath])
            compile_sources(javac, testclasspath, testclasses, tests, work)
            for test in sorted(test_root.rglob("*Test.java")):
                testname = ".".join(test.relative_to(test_root).with_suffix("").parts)
                subprocess.run([str(java), "-ea", "-cp", os.pathsep.join([str(testclasses), testclasspath]), testname], check=True)
        resources = PROJECT / "src/main/resources"
        metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
        output = build / "libs" / f"{metadata['id']}-{metadata['version']}.jar"
        output.parent.mkdir(parents=True, exist_ok=True)
        staged = work / "mod.jar"
        package(classes, resources, staged)
        staged.replace(output)
        if args.integration:
            integration = PROJECT / "src/integration"
            integrationsources = sorted((integration / "java").rglob("*.java"))
            if not integrationsources or not (integration / "resources/fabric.mod.json").exists():
                raise ValueError("Integration harness sources/resources are missing")
            integrationclasses = work / "integration-classes"
            integrationclasses.mkdir()
            compile_sources(javac, os.pathsep.join([str(classes), classpath]), integrationclasses, integrationsources, work)
            package(integrationclasses, integration / "resources", staged)
            integrationoutput = build / "libs/loretools-integration.jar"
            staged.replace(integrationoutput)
            print(integrationoutput)
    print(output)
    print("sha256", digest(output))


if __name__ == "__main__":
    main()
