# Commands and shortcuts

[← Home](../README.md) · [Get started](GETTING_STARTED.md) · [Permissions](../PERMISSIONS.md)

Run `/lt` to see only the commands available to you. Click a help example to fill your chat input.


| Canonical command | Shortcut | Purpose / permission suffix |
| --- | --- | --- |
| `/lt`, `/lt help [command]` | — | Discover individually permitted features; no blanket root grant |
| `/lt sign <author> [title]` | `/sign` | Sign one main-hand Book and Quill; `book.sign` |
| `/lt author <author>` | `/author` | Correct actual author; `book.author` |
| `/lt title <title>` | `/booktitle` | Correct actual title; `book.title` |
| `/lt draft` | `/draft` | Independent editable copy; `book.draft` |
| `/lt save <id>` | `/savebook` | New template, duplicate IDs rejected; `library.save` |
| `/lt get <id>` | `/getbook` | Independent copy into an empty slot; `library.get` |
| `/lt library [search]` | `/library` | Browse/search/preview; `library.browse` |
| `/lt list [search]` | `/listbooks` | Search results in chat; `library.browse` |
| `/lt read <id> <page>` | — | Plain read-only page in private chat; `library.browse` |
| `/lt update <id>` | `/updatebook` | Request explicit replacement; `library.update` |
| `/lt delete <id>` | `/deletebook` | Request deletion; `library.delete` |
| `/lt confirm <token>` | — | Confirm the specific revision/action within 60 seconds; same update/delete permission |
| `/lt name <text>` | `/name` | Cosmetic custom name; `item.name` |
| `/lt resetname` | `/resetname` | Remove custom name only; `item.name` |
| `/lt lore add <text>` | `/lore add` | Append description; `item.lore` |
| `/lt lore set <line> <text>` | `/lore set` | Replace 1-based line; `item.lore` |
| `/lt lore remove <line>` | `/lore remove` | Remove 1-based line; `item.lore` |
| `/lt lore clear` | `/lore clear` | Remove item lore only; `item.lore` |
| `/lt reload` | `/ltreload` | Validate config replacement; `admin.reload` |
| `/lt permissions grant <player-or-uuid> <role>` | — | Built-in role assignment; `admin.permissions` |
| `/lt permissions revoke <player-or-uuid> <role>` | — | Remove one role; `admin.permissions` |
| `/lt permissions show <player-or-uuid>` | — | Inspect UUID roles; `admin.permissions` |

Every permission suffix above has the prefix **`loretools.`**. Both aliases and canonical commands invoke the same implementation and permission checks. Commands requiring held items reject console/non-player sources. Suggestions do not reveal template IDs without browse permission. Retrieval permission alone does not confer browsing or destructive access.

Aliases are a configurable canonical-command-to-shortcut map. Remove an entry to disable its shortcut. **Alias and permission-mode changes require a restart**; reload rejects these changes and preserves the complete previous configuration. Registration checks existing root nodes and skips conflicts without merging. Vanilla `/title`, `/list`, `/reload` and `/give` are untouched. A conflict with `/lt` itself aborts initialization instead of hijacking another mod's namespace.


Chat listings show up to ten matches; narrow the search or use the paginated library menu. Book changes require a single book in the main hand. Names/lore preserve the held stack count.
