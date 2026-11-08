# Configuration and storage

[← Home](../README.md) · [Permissions](../PERMISSIONS.md)

The first startup creates `config/loretools.json`. Start with the [complete example](../examples/loretools.json).

| Setting | Default | Purpose |
| --- | --- | --- |
| `defaultTitle` | `Untitled` | Title used when signing without a title argument |
| `guiEnabled` | `true` | Enable the library menu; commands still work when disabled |
| `maxNameChars` | `256` | Custom item-name limit |
| `maxLoreLines` | `32` | Maximum description lines |
| `maxLoreLineChars` | `256` | Maximum UTF-16 units per description line |
| `maxLoreTotalChars` | `4096` | Combined description limit |
| `permissionMode` | `auto` | `builtin`, `external` or the persisted automatic choice |
| `roles` | viewer / placer / author / admin | Built-in permissions, including optional negative nodes |
| `aliases` | See example | Canonical command to standalone shortcut |

To disable built-in roles and use only LuckPerms, set `"permissionMode": "external"` and restart. Stored built-in assignments are ignored, built-in role management is disabled, and player access fails closed if LuckPerms is unavailable. See the [complete LuckPerms-only example](../examples/loretools-luckperms.json) and [setup recipes](../PERMISSIONS.md). The default remains `auto`.

Use `/lt reload` after editing. The whole replacement must validate before it is applied. Failed reloads keep the working configuration. Changing aliases or `permissionMode` requires a restart.

Templates are stored under `local/loretools/books/` relative to the server working directory. Role assignments and the backend marker are under `local/loretools/`. Back up this directory with your server. Do not publish it: it contains your book content and staff UUID assignments. Mutation audits use actor/action/item or template identifiers; they do not deliberately record entire books.

## Book and item limits


Book-changing operations require a single book. Item name/lore operations preserve the stack count and unrelated components. Retrieval and drafts require an **empty** main inventory slot, even if a stack could otherwise merge; this deliberately makes delivery atomic and predictable. No items are dropped or overwritten on failure.

Native limits are 32 UTF-16 code units for a written-book title and 100 pages of 1,024 UTF-16 code units for a writable book. The author's 128-unit limit is a Lore Tools policy. Configurable defaults limit names to 256, lore to 32 lines, 256 units per line and 4,096 units total. Inputs exceeding limits are rejected without truncation. Unicode is preserved; invalid surrogate sequences, control characters and legacy section-sign formatting are rejected for new command text.

Signing waits for vanilla's pending filtered save and preserves both raw and filtered saved-page variants. New command text passes through the player's server text filter; rejected or unavailable filtering leaves the item unchanged. A narrow server Mixin tracks the otherwise asynchronous vanilla save. Another hook rejects creative inventory packets while the library menu is active. Neither changes normal player identity or broadcasts staff authorship.

Writable books cannot represent arbitrary rich written-book components. Draft conversion only accepts unstyled literal text (including plain literal siblings) that fits writable limits, checking both raw and filtered variants. Styled, translated, selector, interactive or oversized content is rejected rather than flattened. Library copies retain the complete compatible item codec data; menu/chat previews are explicitly plain text, use the viewer's filtered variant, and never execute component interactions. Previewing does not put books in the player's hand or inventory.

Library entries are version-1 JSON envelopes containing ID, random revision and the native item codec representation. IDs use lowercase ASCII letters/digits, `_` and `-`, up to 64 characters; reserved filenames and path syntax are rejected. Entries are bounded to 2 MB and 10,000 distinct disk IDs including deletion tombstones. Up to 128 writes may be pending. Writes force a temporary file and atomically replace the entry after preserving `.bak`; filesystems without atomic replacement fail visibly. There is no destructive fallback.

Malformed entries are logged individually. A usable backup may be loaded for reading, but that ID stays write-blocked until an administrator repairs its primary file **offline**. Originals and malformed evidence are not deleted. Deletions persist tombstones so a backup cannot silently resurrect a deleted template. On interrupted writes, stop the server, preserve the entire directory, compare primary/backup/temporary files and restore a chosen valid envelope deliberately; restart and verify. Never remove runtime data just to suppress an error.

The menu uses stable ID ordering, 45 entries per page, title/author/ID search and paged read-only text previews. Shift clicks, drag, number/offhand swaps, clone, throw and double-click transfers are inert. Click actions recheck permissions and the captured template revision. Revoking browse closes the menu on its next validity check; stale entries must be selected again. Previously received information cannot be recalled from a client, and creative players' general vanilla ability to manufacture items is outside permission enforcement.
