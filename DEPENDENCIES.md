# Pinned build dependencies

Build target: Minecraft 26.3 and Java 25. Downloaded dependencies and test data stay in ignored local storage; the installable JAR contains only Lore Tools code and resources.

| Component | Exact build target | Primary evidence | License / distribution |
| --- | --- | --- | --- |
| Minecraft Java | 26.3, Java 25 | [Official version metadata](https://piston-meta.mojang.com/v1/packages/80608ff6753705b6a82e2cb86a5937d4d233abd4/26.3.json) | Mojang terms; download separately, never package game classes |
| Fabric Loader | 0.20.1 | [Published artifact](https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar), embedded metadata | Apache-2.0; separately installed |
| Fabric API | 0.165.2+26.3 | [Published artifact](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.2+26.3/fabric-api-0.165.2+26.3.jar), embedded metadata | Apache-2.0; separately installed |
| LuckPerms Fabric | 5.5.85 | [Author's release](https://modrinth.com/plugin/luckperms/version/DzQPkkXY), [version API](https://api.modrinth.com/v2/version/DzQPkkXY) | [MIT](https://github.com/LuckPerms/LuckPerms/blob/master/LICENSE.txt); optional separately installed provider |
| Fabric Permissions API | 0.7.0 | Embedded in the above LuckPerms jar | MIT; do not install a duplicate copy |
| Sponge Mixin (Fabric build) | 0.17.5+mixin.0.8.7 | [Exact Loader metadata](https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/server/json) | MIT; provided by Fabric Loader runtime |
| ASM modules | 9.10.1 | Same Loader metadata; asm, analysis, commons, tree, util | BSD-3-Clause; provided by Fabric Loader runtime |

The exact downloaded LuckPerms jar advertises Minecraft >=26.1; its author-published release list explicitly includes 26.3. Fabric API's own metadata requires Java >=25, Loader >=0.19.3 and Minecraft ~26.3-. Loader 0.20.1 satisfies these declarations. Advertised compatibility alone is not a server boot or multiplayer verification result; see this module's validation record for executed checks.

The local build/native verification runtime reports Temurin OpenJDK `25.0.5+8-LTS` (2026-10-20). A JDK is supplied separately; this module neither downloads nor redistributes it.

The build verifies these SHA-256 pins:

```text
Minecraft game.jar  a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6
Fabric Loader       d0c5a082e07410aaff9de7ab196aa6b4236002870f9ba3ad5c1260fcce5110ad
Fabric API          2612a078a5daa06d9a14cabad40fe66de36b8cb22ee8cdf346d5d9894478c392
LuckPerms Fabric    18598fcb95a83f6723d8bc68a905500ed5ecac92e9909ce1f25409cdebe2df49
```

The official server bundle is pinned by Mojang's SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`; each extracted game/library entry is also checked against the bundle's SHA-256 table. The game hash above is checked separately. No decompiled source is committed.

## Build

Install Python 3 and JDK 25, then run from the repository root, substituting your JDK path:

```powershell
python build.py --java-home <jdk25> --prepare-runtime
```

This downloads the pinned vanilla server bundle and dependencies, extracts an ignored compile runtime, compiles with `--release 25`, runs each Java test main named `*Test.java`, and produces `build/libs/loretools-0.1.0.jar`. It does not start Minecraft or accept its EULA. Use `--prepare-only` to download/verify prerequisites only. A previously prepared runtime can be supplied with `--runtime <runtime-directory>` instead of `--prepare-runtime`; it must contain `game.jar`, `lib/*.jar` and a `runtime.json` manifest with Minecraft version and artifact hashes.

Fresh temporary class directories prevent removed source classes from surviving into a jar. Sorted entries, fixed jar timestamps and UTF-8 source compilation make repeated builds reproducible with the same JDK, sources and dependencies. `--cache` selects dependency storage; keep it outside tracked source.

The exact unobfuscated game needs no mappings or remap step. Mixin annotation processing is disabled for this build; runtime mixin application must still be checked against the pinned server. The build includes hash-pinned Loader Mixin/ASM dependencies for annotation compilation and isolated runtime testing. None are copied into the mod jar.

## LuckPerms integration contract

LuckPerms remains optional. Its API classes are already in its server jar and are compile-only dependencies here; do not shade them into Lore Tools. Direct `LuckPermsProvider`/cached-user integration avoids converting explicit denials into an operator-level fallback through a boolean-only adapter. [Provider API source](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/LuckPermsProvider.java).

For a loaded player, evaluate cached permission data with current query options. LuckPerms resolves inheritance and applicable contexts. Distinguish `TRUE`, `FALSE` and `UNDEFINED`: external-provider mode authorizes only `TRUE`; a denial or unset node must not become an operator grant. Provider unavailability and an unloaded user also fail closed in external mode. The separately configured built-in owner bootstrap is limited to permission/reload administration. [Cached permission API](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/cacheddata/CachedPermissionData.java), [Tristate semantics](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/util/Tristate.java).

Online users are normally loaded by LuckPerms. Do not call `loadUser(...).join()` or another blocking future operation on the server thread. Permission predicates should use the loaded cached user; an unavailable record is not an unset permission. Check authorization again at execution, not only while constructing or presenting the command tree. [User manager contract](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/model/user/UserManager.java).

LuckPerms 5.5.85 declares API-base, command-v2, lifecycle-v1, networking-v1, entity-events-v1 and permission-v1 Fabric modules plus its bundled fabric-permissions-api-v0 >=0.7.0. Install the complete pinned Fabric API jar alongside it. This module does not register client-required content or bundle provider/networking code.

## Disposable native server

`tests/server.py` prepares a new fixture strictly under ignored `local/`, refuses populated directories, binds Minecraft/RCON to loopback, and uses an independently generated flat world named `loretools-fixture-v1`. It does not load any supplied map or installed world. Offline authentication is suitable only for this isolated local fixture. Explicit EULA acceptance is required:

```powershell
python tests/server.py prepare --accept-eula
python tests/server.py run --java-home <jdk25> --with-luckperms
```

The run command stays active and records output in the ignored fixture's `server-console.log`; wait for the server's `Done` and RCON-listening messages before sending commands from another shell:

```powershell
python tests/server.py command "list"
python tests/server.py command "stop"
```

The defaults use game port 25576 and RCON port 25586. Preparation permits other ports and refuses occupied ones. RCON authentication is generated into ignored local state and is never printed. For a provider-absent test, prepare a separate directory using `--server-dir local/lore-server-no-provider` before the subcommand, choose different ports, and omit `--with-luckperms`. The helper preserves an existing fixture rather than deleting its provider or world. Stop servers before rebuilding/relaunching; never point this fixture helper at a production directory.

`build.py --integration` additionally compiles the separate `src/integration` test mod to `build/libs/loretools-integration.jar`. It is never included in the main artifact. The server helper's `--integration-phase initial`, `restart`, `external` or `external_restart` installs that test mod and passes its phase as a JVM property. Test phases stop their own disposable server and emit an ignored local receipt; successful startup alone is not a passing gameplay receipt. The runner requires a fresh PASS receipt, exits nonzero otherwise, and records mod/harness SHA-256 values alongside the result and per-phase console log. Keep the integration jar out of production servers.

Run `initial` on a fresh built-in fixture, followed by `restart` on the same saved fixture. External phases require a separate LuckPerms fixture with `permissionMode: external` and the exact fresh `loreadmin`, `loreauthor` and `lorebuilder` group recipes in [PERMISSIONS.md](PERMISSIONS.md) applied before the harness is installed; then run `external` followed by `external_restart`. Tests use synthetic UUIDs and preserve their fixture state between the paired phases.
