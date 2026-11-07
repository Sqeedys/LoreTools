# Contributing

Keep changes focused on staff book tools, the reusable library, cosmetic item text and permission handling.

## Report a bug

Include Minecraft, Java, Fabric Loader, Fabric API and Lore Tools versions, the active permission backend, and short reproduction steps using an invented book or player name. Say whether the problem happens through `/lt`, a shortcut or the menu.

Before sharing logs, remove IP addresses, player names/UUIDs, credentials, private book text and filesystem paths. Do not upload worlds, live configuration, role assignments or entire server folders. Reproduce with a disposable server and fictional data whenever possible.

## Build and verify

```text
python build.py --java-home <JDK-25-directory> --prepare-runtime --integration
```

Read [TESTING.md](TESTING.md) for the native fixture workflow and manual checklist. Never install `loretools-integration.jar` on a real server. Test-only jars and all runtime data belong in ignored directories.

Use literal components for new user text, preserve filtered variants, check permissions again when actions execute, and validate all operations before changing inventory. Keep disk writes off the gameplay thread. New dependencies need exact-version and redistribution review.

Please include the behavior changed, why it is needed, checks actually run and any remaining limitations in pull requests. Avoid committing generated outputs or personal information.
