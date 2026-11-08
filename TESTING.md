# Testing and limitations

This record distinguishes compilation, automated checks, native-server fixtures and actual client testing. A passing build does not prove every gameplay interaction works.

## Login regression — 2026-11-08

Version 0.1.0's tests suppressed outgoing packets, so they did not validate the command tree received by a client. A subsequent login report exposed this gap. Re-running the original release with the native command packet encoder/decoder reproduced `IllegalStateException: Server sent an impossible command tree` after granting the built-in admin role.

Same-name executable leaf shortcuts could compare equal to their redirect targets. Packet node deduplication could therefore produce a self-redirect. Version 0.1.1 removes those redirect edges while sharing canonical handlers, requirements and children. The integration harness now encodes and decodes outgoing command packets, including permission updates and reconnect-equivalent synchronization. This validates the failing codec path; it is still distinct from a graphical-client connection.

Version 0.1.1 compiled and passed all 206 focused checks and **70 native-server steps**. Each phase produced a fresh PASS receipt and server exit code 0 for the artifact below.

| Backend / phase | Steps | Result |
| --- | ---: | --- |
| Built-in / initial | 31 | PASS |
| Built-in / restart | 11 | PASS |
| LuckPerms / external | 23 | PASS |
| LuckPerms / external restart | 5 | PASS |

The new command-packet checks cover unassigned access, every built-in role grant/revocation, persisted roles at restart, LuckPerms administrator access and LuckPerms restart. Native command packet serialization and decoding succeeded in each checked state. All runtime evidence remains local and ignored.

```text
loretools-0.1.1.jar
SHA-256 d3feda2a56c034fc0cf10610425a63d3e0b3b6fe8b44c0c1d4a03d6c2ad28008
```

## Historical 0.1.0 build checks — 2026-11-07

The standalone source compiled with Temurin JDK 25.0.5+8 against Minecraft 26.3, Fabric Loader 0.20.1 and Fabric API 0.165.2+26.3. All **206 focused checks passed**:

| Suite | Checks | Coverage |
| --- | ---: | --- |
| BookEditsTest | 31 | Asynchronous save/filter coordination and preview pagination |
| ItemOperationsTest | 71 | Book fields, raw/filtered pages, drafts, names/lore and invalid-input preservation |
| LibraryStorageTest | 34 | IDs, revisions, persistence, copies and recovery behavior |
| LorePermissionsTest | 70 | Roles, denials, backend selection, UUID storage and reload validation |

Production artifact: `loretools-0.1.0.jar`

```text
SHA-256 fb04f0e6188e1cfbd7e9fbcefe7f7f482fbcc26dfaaee42bdfc1d4cd77c8df0a
```

The production JAR contains only mod classes/resources and its MIT notice. Dependencies and the separate integration harness are not bundled.

## Historical 0.1.0 native-server fixture

On 2026-11-07, the artifact hash above passed **67 named native-server steps** across four fresh phase receipts. Each server exited successfully; paired restart phases used the corresponding saved fixture.

| Backend / phase | Steps | Result |
| --- | ---: | --- |
| Built-in / initial | 29 | PASS |
| Built-in / restart | 10 | PASS |
| LuckPerms / external | 23 | PASS |
| LuckPerms / external restart | 5 | PASS |

The integration harness uses synthetic players on isolated loopback Fabric servers. It exercises native commands, components, menus and selected incoming packet handlers, but suppresses outgoing packets. It is not an actual connected-client test.

The scenarios cover the delayed vanilla book-save path, Unicode/blank pages, aliases and a simulated command collision, book corrections, names/lore, drafts, full inventories, revision confirmations, independent copies, library pagination/search/preview/retrieval, inert menu transfer actions, creative packet rejection, revocation, restart persistence and malformed-primary recovery. The external phases exercise real LuckPerms 5.5.85, documented group recipes, non-operator grants, unset nodes, explicit denials and persisted revocation.

See [the disposable-server instructions](DEPENDENCIES.md#disposable-native-server) to reproduce these phases. Keep the integration JAR out of normal servers. Server startup alone is not a passing test: the helper requires a fresh phase receipt and records the exact artifact hash locally.

## Not yet verified

- A real unmodded graphical client connecting, rendering menus and exercising every click/keyboard gesture.
- Real multiplayer transfer, reading and lectern placement of the resulting books.
- Client disconnect/reconnect races, network latency and interactions with arbitrary other mods.
- Power loss during writes, unsupported filesystems and large-library performance.

Before using valuable books, test the full staff workflow on a disposable server: write pages, click **Done**, immediately sign, save, browse, retrieve, draft, edit and update. Connect a client without Lore Tools installed; check reading, transfers and lecterns. Revoke permissions while a menu is open and verify subsequent actions fail. Test with a full inventory and confirm no items move or disappear on failure.

## Intentional limits

- Drafts reject styled/dynamic or oversized text that writable books cannot preserve; they never silently flatten it.
- Previews are plain read-only text and respect the viewer's filtered variant.
- Retrieval and drafts require an empty main-inventory slot, even when stacking might otherwise be possible.
- Library entries are loaded in memory. Per-entry and entry-count bounds exist, but there is no aggregate heap quota or validated large-library capacity claim.
- Backups support deliberate recovery; they are not a replacement for server backups.
- No client-required content is registered, but actual client acceptance remains a separate check listed above.
