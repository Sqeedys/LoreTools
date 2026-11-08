# Changelog

## 0.1.1 — 2026-11-08

- Fix invalid command packets that could disconnect permitted players on join or after an access grant. Shortcuts now share command handlers and children without redirect edges.
- Round-trip native command packets in integration checks, including permission grants, revocation, restart and LuckPerms admin access.
- Add a complete LuckPerms-only config example and clarify how `permissionMode: external` disables built-in role assignments and management.
- Select the test-server artifact from mod metadata when versions change.

Replace the old JAR rather than installing both versions. Existing library entries, configuration and role assignments remain compatible.

## 0.1.0 — 2026-11-07

First public release of Lore Tools.

- Sign books with fictional authors and correct their actual author/title fields.
- Save reusable templates, browse a vanilla-compatible library menu and retrieve independent copies.
- Create editable drafts without changing signed originals.
- Rename items and edit descriptive lore.
- Discover commands through `/lt`, with configurable standalone shortcuts.
- Assign scoped built-in roles or use LuckPerms, including explicit denials.

Targets Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Java 25. See [testing and limitations](TESTING.md) before installation.
