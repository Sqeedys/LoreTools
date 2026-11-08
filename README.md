<p align="center"><img src="assets/banner.svg" alt="Lore Tools — Books, clues, and stories" width="100%"></p>

<p align="center"><strong>Minecraft 26.3 · Fabric · Java 25 · Server-side · MIT</strong></p>

<p align="center"><a href="https://github.com/Sqeedys/LoreTools/releases/latest"><strong>Download the mod</strong></a> &nbsp; · &nbsp; <a href="docs/GETTING_STARTED.md">Get started</a> &nbsp; · &nbsp; <a href="docs/COMMANDS.md">Commands</a> &nbsp; · &nbsp; <a href="PERMISSIONS.md">Permissions</a></p>

Lore Tools helps server staff make journals, letters, named objects and other readable clues using ordinary Minecraft items. Sign a book as a fictional author, keep reusable templates in a searchable library, and give your builders exactly the tools they need.

**Created by [Sqeedys](https://www.youtube.com/@Sqeedys) — [watch on YouTube ↗](https://www.youtube.com/@Sqeedys).**

**Upgrading from 0.1.0:** replace the old JAR with 0.1.1. Version 0.1.0 could disconnect permitted players when synchronizing commands. Keep your existing configuration and library data.

## What you can do

| Books | Library | Items & access |
| --- | --- | --- |
| Sign with any author name | Save reusable book templates | Rename items without an anvil |
| Correct real author/title fields | Search and browse an in-game menu | Add and edit description lines |
| Make editable draft copies | Preview before getting a copy | Give staff roles without OP |
| Preserve saved and filtered pages | Confirm updates and deletions | Use built-in roles or LuckPerms |

## Install in a few steps

1. Stop your **Fabric 26.3** server and back up its files.
2. Install **Fabric Loader 0.20.1+**, **Java 25**, and **Fabric API 0.165.2+26.3**. An older Fabric API such as 0.162.0 will not satisfy this mod's requirement.
3. Download **`loretools-0.1.1.jar`** from [Releases](https://github.com/Sqeedys/LoreTools/releases/latest) and put it in the server's `mods` folder. The source ZIP is for developers; the integration-test JAR is not for normal servers.
4. Start the server. Players do **not** install Lore Tools on their clients.
5. With the built-in backend active, run this in the **server console**, replacing the name with an online player:

```text
lt permissions grant YourPlayerName admin
```

Wait for the **Saved** message, then type **`/lt`** in-game. If LuckPerms is installed on first startup, the default `auto` mode selects it instead; use the [LuckPerms setup](PERMISSIONS.md#luckperms-setup).

## Your first lore book

Write a Book and Quill, click **Done**, and hold it in your main hand:

```text
/sign "Example Author" "The Field Journal"
/savebook field_journal
/library
```

Select the book to preview it, then choose **Get Copy**. Keep an empty inventory slot. The original and saved template are independent of retrieved copies.

```text
/name Surveyor's Compass
/lore add Its needle trembles near the old tower.
```

Need to revise a signed book? Use `/draft`, edit and sign the new copy, then `/updatebook field_journal`. The generated confirmation applies only to that template revision; books already distributed stay unchanged.

## Pick the right role

| Built-in role | Can do |
| --- | --- |
| `viewer` | Browse and preview |
| `placer` | Browse and obtain copies |
| `author` | Edit books/items and save new templates |
| `admin` | All tools, confirmed template updates/deletions and role management |

Players start without access. Every operation has its own permission node. [Full setup recipes, revocation and explicit denials →](PERMISSIONS.md)

## Guides

- [Getting started](docs/GETTING_STARTED.md) — install, configure access and make your first book.
- [Command reference](docs/COMMANDS.md) — every command, shortcut and permission.
- [Configuration & storage](docs/CONFIGURATION.md) — defaults, aliases, backups and recovery.
- [Permissions](PERMISSIONS.md) — built-in and LuckPerms recipes.
- [Build & dependencies](DEPENDENCIES.md) — reproducible standalone builds and third-party attribution.
- [Testing & limitations](TESTING.md) — what was exercised and what still needs in-game verification.

## A few things to know

- Edit one book at a time. Item naming/lore can operate on a whole stack without changing its count.
- `/name` changes the item display name; `/booktitle` changes the actual written-book title.
- Rich or dynamic book text cannot always become editable text. Draft conversion rejects unsupported content rather than stripping it.
- Library previews use plain text. Browsing never inserts an item into your hand.
- Shortcut conflicts are skipped and logged. The matching `/lt` command remains available.
- This initial release has automated and native-server coverage. Graphical-client, real multiplayer/lectern and large-library load checks are not yet established; see [the test record](TESTING.md).

## Build from source

With Python 3 and a JDK 25 installation:

```text
python build.py --java-home <JDK-25-directory> --prepare-runtime
```

Output: `build/libs/loretools-0.1.1.jar`. Dependencies are downloaded with checksum verification and are **not** bundled in the mod.

## Contributing

Bug reports and focused improvements are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before sharing logs or test data. The mod's source and original artwork are available under the [MIT License](LICENSE). Minecraft and third-party dependencies retain their own terms; this is not an official Minecraft product and is not endorsed by Mojang or Microsoft.
