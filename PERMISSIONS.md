# Lore Tools permissions

Copy [examples/loretools.json](examples/loretools.json) to the server's `config/loretools.json`, or let the mod create its default configuration. All new players begin without a built-in role. The role definitions alone do not assign anyone access.

## Disable built-in permissions and use LuckPerms

Set `"permissionMode": "external"` in `config/loretools.json`, then restart with LuckPerms installed. A [complete LuckPerms-only config](examples/loretools-luckperms.json) is provided for new installations; preserve your other settings when editing an existing config.

This disables built-in role management and ignores all stored built-in role assignments. The `roles` section may stay in the config, but has no effect in external mode. Use the LuckPerms commands below to assign individual `loretools.*` nodes or groups. Existing built-in `admin` membership does not become a LuckPerms grant. Missing/unavailable LuckPerms denies player access instead of restoring built-in access.

The default stays `auto` for easy setup: on the first startup it chooses built-in roles when LuckPerms is absent. That choice is persisted; adding LuckPerms later does not switch an existing server automatically. Set `external` explicitly to switch.

## Choose the authority

- `builtin`: Lore Tools uses explicit UUID role assignments. The real console can manage access. Players with Minecraft's OWNER command authority can bootstrap only `loretools.admin.permissions` and `loretools.admin.reload`; they receive no automatic book, item or library powers. Explicit assigned role denials override this bootstrap too.
- `external`: LuckPerms is the sole player permission authority. A cached `TRUE` permits an action. `FALSE`, `UNDEFINED`, an unloaded user or an unavailable provider denies it. OP status adds no player bypass. Built-in role management is inactive. The privileged server console remains available for administration.
- `auto`: on first startup, selects external when the LuckPerms mod is present, otherwise built-in. It records that choice in `local/loretools/permission-backend.json` and retains it across restarts. Installing or removing LuckPerms does not silently switch authorities. To change authority, set an explicit mode and restart.

Changing `permissionMode` or aliases requires restart. `/lt reload` validates the complete replacement first; a rejected reload keeps the working configuration. Role definitions and text limits can reload without restart. Unknown configuration fields, invalid types, duplicate JSON fields and unknown permission names are rejected.

Do not confuse an entityless command source with the console: non-player administration requires Minecraft OWNER authority. Ordinary command blocks do not receive automatic access.

## Built-in setup

Set `permissionMode` to `builtin` and restart. From the console, use `lt permissions grant <UUID> <role>`, `lt permissions revoke <UUID> <role>`, and `lt permissions show <UUID>`. In player chat, prefix commands with `/`. A name is accepted only for a currently online player; otherwise supply the verified UUID. No offline name guessing or network identity lookup occurs.

Wait for the **Saved** response. The initial queued response does not confirm persistence. The mod saves assignments before making them effective, then refreshes the affected online player's command tree. Assignments survive restart and revocation is persisted too.

These cases assume a separate test player with only the stated role and a saved template named `sample_book`. Use a disposable test server when checking inventory changes.

| Case | Console assignment | Expected player behavior |
| --- | --- | --- |
| Unassigned | None | `/lt help` shows no accessible actions; signing, browsing and getting are denied. |
| Viewer | `lt permissions grant <UUID> viewer` | `/lt library` and `/lt list` work; `/lt get sample_book` is denied. Browsing includes read-only page previews. |
| Placer / builder | `lt permissions grant <UUID> placer` | Can browse and get independent copies into an empty inventory slot; cannot save, edit, update or delete templates. |
| Author | `lt permissions grant <UUID> author` | Can sign/edit books, edit item names/lore, browse/get and save new template IDs. Cannot update/delete existing templates. |
| Admin | `lt permissions grant <UUID> admin` | Receives every fixed Lore Tools permission, including confirmed template updates/deletes and built-in access management. |

Roles accumulate. For an isolated case, revoke other assigned roles first. `admin` is an explicit list of the current nodes, so future features are not implicitly granted.

The five administration examples below are entered in the server console after replacing UUID placeholders.

**1. Administrator:** give all Lore Tools capabilities.

```text
lt permissions grant <ADMIN_UUID> admin
```

**2. Author:** allow book/item editing and new-template creation.

```text
lt permissions grant <AUTHOR_UUID> author
```

**3. Builder:** allow browse/get access, without editing or template creation.

```text
lt permissions grant <BUILDER_UUID> placer
```

**4. Revoke:** remove the author's role, then inspect remaining assignments.

```text
lt permissions revoke <AUTHOR_UUID> author
```

Wait for Saved, then run `lt permissions show <AUTHOR_UUID>`.

**5. Deny one sensitive action:** deny only template deletion to an otherwise fully authorized administrator. Add this entry inside the existing configuration's `roles` object:

```json
"no_delete": ["-loretools.library.delete"]
```

Then run:

```text
lt reload
```

Wait for Configuration reloaded, then run:

```text
lt permissions grant <ADMIN_UUID> no_delete
```

After the Saved response, that administrator can still update templates but cannot delete them. Remove just this restriction with `lt permissions revoke <ADMIN_UUID> no_delete`. Other roles may retain access after case 4; inspect the displayed assignments rather than assuming one revoke removes all grants.

For a deny rule, add a role such as this to the `roles` object, reload, then assign it:

```json
"restricted": ["-loretools.library.delete", "-loretools.book.*"]
```

`lt permissions grant <UUID> restricted` denies those actions even if another assigned role grants them. A denial matches either an exact fixed node or a supported namespace wildcard, and takes precedence across all assigned roles. There is no role-order dependency. A denial of either bootstrap administration node also applies to an OWNER player; the console can repair that assignment.

## LuckPerms setup

Install the compatible LuckPerms Fabric build alongside Lore Tools, set `permissionMode` to `external`, then restart. This integration uses the LuckPerms API directly; a separate Fabric permissions bridge is not required. Use the LuckPerms console commands below and wait for their result before testing. Replace `<UUID>` with the test player's verified UUID.

| Case | Console command / setup | Expected result |
| --- | --- | --- |
| 1. Undefined | Fresh test user with no direct, inherited or contextual grant for `loretools.book.sign` | Signing is denied for both non-OP and OWNER players. |
| 2. Explicit grant | `lp user <UUID> permission set loretools.book.sign true` | Signing is allowed for both non-OP and OWNER players. |
| 3. Explicit deny | `lp user <UUID> permission set loretools.book.sign false` | Signing is denied, including for OWNER players. |
| 4. Revoke | `lp user <UUID> permission unset loretools.book.sign` | With no inherited/contextual grant, the result becomes undefined and signing is denied again. Unsetting a direct node does not remove group grants. |
| 5. Persistence | `lp user <UUID> permission set loretools.item.name true`, then restart | `/lt name Permission Fixture` works on a held item after restart; the revoked signing node remains denied. |

The five corresponding setup examples follow. Run the commands from each block in the console, replacing UUID placeholders, and wait for each command's success response before the next. These group names are examples; use fresh groups for an isolated test.

**1. Administrator:** grant every current fixed node explicitly, then assign the group.

```text
lp creategroup loreadmin
lp group loreadmin permission set loretools.book.sign true
lp group loreadmin permission set loretools.book.author true
lp group loreadmin permission set loretools.book.title true
lp group loreadmin permission set loretools.book.draft true
lp group loreadmin permission set loretools.library.browse true
lp group loreadmin permission set loretools.library.get true
lp group loreadmin permission set loretools.library.save true
lp group loreadmin permission set loretools.library.update true
lp group loreadmin permission set loretools.library.delete true
lp group loreadmin permission set loretools.item.name true
lp group loreadmin permission set loretools.item.lore true
lp group loreadmin permission set loretools.admin.reload true
lp group loreadmin permission set loretools.admin.permissions true
lp user <ADMIN_UUID> parent add loreadmin
```

The built-in grant/revoke commands remain inactive in external mode even when `loretools.admin.permissions` is granted; use LuckPerms to manage player access.

**2. Author:** permit book/item edits, browsing, getting and saving new templates, while omitting update/delete.

```text
lp creategroup loreauthor
lp group loreauthor permission set loretools.book.* true
lp group loreauthor permission set loretools.item.* true
lp group loreauthor permission set loretools.library.browse true
lp group loreauthor permission set loretools.library.get true
lp group loreauthor permission set loretools.library.save true
lp user <AUTHOR_UUID> parent add loreauthor
```

**3. Builder:** permit only library browsing and copies.

```text
lp creategroup lorebuilder
lp group lorebuilder permission set loretools.library.browse true
lp group lorebuilder permission set loretools.library.get true
lp user <BUILDER_UUID> parent add lorebuilder
```

**4. Revoke an author's role:** remove the group assignment. Any other direct or inherited grants remain; check those separately if editing is still allowed.

```text
lp user <AUTHOR_UUID> parent remove loreauthor
```

**5. Deny one sensitive action:** explicitly deny deletion to the administrator from case 1, leaving their other grants in place.

```text
lp user <ADMIN_UUID> permission set loretools.library.delete false
```

To remove only that explicit denial and restore the inherited grant:

```text
lp user <ADMIN_UUID> permission unset loretools.library.delete
```

LuckPerms resolves its own groups, contexts and node precedence; Lore Tools uses the resulting permission value. Read the [LuckPerms API documentation](https://github.com/LuckPerms/wiki/blob/master/pages/Developer-API.md) and [PlayerAdapter API](https://javadoc.io/static/net.luckperms/api/5.5/net/luckperms/api/platform/PlayerAdapter.html) for its cached contextual permission behavior.

## Nodes and recovery

The fixed nodes are `loretools.book.sign`, `.book.author`, `.book.title`, `.book.draft`, `.library.browse`, `.library.get`, `.library.save`, `.library.update`, `.library.delete`, `.item.name`, `.item.lore`, `.admin.reload` and `.admin.permissions`, each with the full `loretools` prefix. Renaming a shortcut never changes its permission.

Built-in assignments live in `local/loretools/roles.json`, outside source control, with a previous complete `.bak` file. Writes use a bounded asynchronous queue, forced file contents and atomic replacement. A real storage failure disables built-in role grants until the storage is repaired and the server restarted; it never reports a successful write. Stop the server before manually repairing runtime files, preserve the damaged original, and validate the intended backup before restoring it. There is no automatic fallback to stale role grants.

The standalone `LorePermissionsTest` passed 70 checks covering configuration rejection, all default roles, deny precedence including OWNER bootstrap, AUTO latching, UUID round trips, backups and reload rejection. Native disposable-server checks exercised built-in grants/revocations and the LuckPerms permission-state cases above. The exact documented LuckPerms groups were created by console commands, their stored node sets were checked after restart, and parent assignment/removal plus sensitive-node denial/restoration were tested against the provider. Built-in custom-role denial and reload validation have focused automated evidence; graphical setup by a real staff client remains untested. See [TESTING.md](TESTING.md) for artifact-bound results and limits.
