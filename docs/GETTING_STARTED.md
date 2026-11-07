# Get started with Lore Tools

[← Home](../README.md) · [Commands](COMMANDS.md) · [Permissions](../PERMISSIONS.md)

**By [Sqeedys on YouTube](https://www.youtube.com/@Sqeedys).** Lore Tools gives staff ordinary books and items they can use as journals, letters, clues and props.

## 1. Install on the server

Use Minecraft **26.3**, Java **25**, Fabric Loader **0.20.1 or later**, and Fabric API **0.165.2+26.3**. Those exact versions are the tested baseline. Other Minecraft versions are not supported by this release.

Stop the server, back it up, and put [the release JAR](https://github.com/Sqeedys/LoreTools/releases/latest) in `mods/` alongside Fabric API. Start the server again. No Lore Tools client installation is needed.

For LuckPerms, the tested provider is **LuckPerms Fabric 5.5.85**. It is optional. You do not need a separate permission API installation.

## 2. Give yourself access

Check the startup log for `permission backend=builtin` or `permission backend=external`.

### Built-in roles

In the server console, with your player online:

```text
lt permissions grant YourPlayerName admin
```

Names resolve only for online players. For an offline player, provide their verified UUID. Wait for **Saved grant admin**, then run `/lt` in-game.

For a book author or builder, use one of these instead:

```text
lt permissions grant AuthorPlayer author
lt permissions grant BuilderPlayer placer
```

These roles do not grant Minecraft OP. An author may save new templates, but cannot replace or delete existing templates by default.

### LuckPerms

If external mode is active, manage access with LuckPerms. For a quick administrator setup:

```text
lp user YourPlayerName permission set loretools.* true
```

For narrower roles and tested group recipes, follow [Permissions](../PERMISSIONS.md). An effective LuckPerms denial is respected even for operators.

The default `auto` mode remembers its first backend choice. Adding or removing LuckPerms later does not silently switch your grants. Change `permissionMode` explicitly and restart if you want to switch.

## 3. Write, save and sign

1. Write your pages in a **Book and Quill**.
2. Click **Done** to save them.
3. Hold that single book in your **main hand**.
4. Run:

```text
/sign "Example Author" "The Field Journal"
```

You now have a normal Written Book with the supplied author and title. With `/sign Alex`, the default title is `Untitled`. Names and titles are literal text, not account lookups or commands.

## 4. Save a reusable template

Still holding the signed book:

```text
/savebook field_journal
/library journal
```

The saved original is not consumed. The menu searches IDs, titles and authors. Click an entry to inspect its preview, use the page controls as needed, then explicitly choose **Get Copy**. Browsing alone gives nothing.

For command-only use:

```text
/listbooks journal
/lt read field_journal 1
/getbook field_journal
```

Retrieval needs an empty inventory slot. Copies are ordinary Minecraft Written Books and are intended for normal storage, transfer and lectern use; those interactions are part of the remaining manual acceptance checklist.

## 5. Correct or revise a book

For a small correction to the held signed book:

```text
/author Revised Author
/booktitle Revised Journal
```

For editable pages:

```text
/draft
```

The signed original stays in your hand; the editable copy goes into another empty slot. Edit that draft, click Done and sign it again. To replace the saved template:

```text
/updatebook field_journal
```

Review and submit the `/lt confirm <token>` command shown privately. It expires after 60 seconds and checks that both the template revision and held replacement still match. Earlier distributed copies remain unchanged.

## 6. Add item details

```text
/name Surveyor's Compass
/lore add Its needle trembles near the old tower.
/lore add The initials are almost worn away.
/lore set 2 The engraving has faded.
/lore remove 1
```

Line numbers start at **1**. Use `/resetname` to remove the custom name and `/lore clear` to remove descriptions. These changes are cosmetic.

## If something doesn't work

| Symptom | Check |
| --- | --- |
| Server refuses to start | Confirm Java, Minecraft and Fabric API versions; keep only the intended mod/API versions installed. |
| `/lt` works but no tools appear | You need feature permissions; check the active backend and grants. |
| A shortcut belongs to another mod | Use `/lt sign`, `/lt library`, etc.; startup logs show conflicts. |
| “Hold exactly one book” | Split the stack and hold the correct book in your main hand. |
| Get/draft fails with a full inventory | Free one main inventory slot. Items are not dropped to make room. |
| Draft rejects a book | Its formatting, dynamic components or length cannot be preserved in editable form. |
| A confirmation expired or became stale | Request the update/delete again and review the new confirmation. |
| Built-in grants are inactive | External mode is selected. Use LuckPerms or deliberately switch backend and restart. |
