package dev.loretools;

import java.nio.file.*;
import java.util.*;

/** Standalone checks; the server integration suite covers command sources and the loaded LP provider. */
public final class LorePermissionsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("loretools-permission-test-");
        Path configPath = directory.resolve("config.json");
        LoreConfig defaults = LoreConfig.load(configPath);
        check(defaults.defaultTitle.equals("Untitled"), "default book title");
        check(defaults.maxNameChars == 256 && defaults.maxLoreLines == 32 && defaults.maxLoreTotalChars == 4096, "safe text limits");
        check(defaults.aliases.get("title").equals("booktitle") && defaults.aliases.get("reload").equals("ltreload"), "default aliases");
        check(LoreConfig.load(configPath).roles.equals(defaults.roles), "config round trip");
        rejectConfig(configPath, "{\"version\":2}");
        rejectConfig(configPath, "{\"permissionMode\":\"typo\"}");
        rejectConfig(configPath, "{\"guiEnabled\":\"true\"}");
        rejectConfig(configPath, "{\"maxLoreLines\":\"32\"}");
        rejectConfig(configPath, "{\"maxLoreLines\":0}");
        rejectConfig(configPath, "{\"maxNameChars\":2147483648}");
        rejectConfig(configPath, "{\"maxLoreLines\":1.5}");
        rejectConfig(configPath, "{\"unknown\":true}");
        rejectConfig(configPath, "{\"version\":1,\"version\":1}");
        rejectConfig(configPath, "{\"aliases\":{\"sign\":\"same\",\"draft\":\"same\"}}");
        rejectConfig(configPath, "{\"aliases\":{\"sign\":\"loretools\"}}");
        rejectConfig(configPath, "{\"aliases\":{\"typo\":\"somecommand\"}}");
        rejectConfig(configPath, "{\"roles\":{\"admin\":[\"loretools.typo\"]}}");
        rejectConfig(configPath, "{\"roles\":{\"admin\":[\"*\"]}}");
        rejectConfig(configPath, "{\"roles\":{\"admin\":[true]}}");
        rejectConfig(configPath, "{\"defaultTitle\":null}");
        rejectConfig(configPath, "{\"defaultTitle\":\"line\\nline\"}");
        rejectConfig(configPath, "{\"defaultTitle\":\"" + "😀".repeat(17) + "\"}");
        rejectConfig(configPath, "{\"defaultTitle\":\"\\ud800\"}");
        rejectConfig(configPath, "{\"defaultTitle\":\"\\u00a7color\"}");
        rejectConfig(configPath, "{} {}");
        check(!allow(defaults, Set.of(), "library.browse"), "unassigned player denied");
        check(allow(defaults, Set.of("viewer"), "library.browse"), "viewer browse");
        check(!allow(defaults, Set.of("viewer"), "library.get"), "viewer cannot get");
        check(allow(defaults, Set.of("placer"), "library.get"), "placer get");
        check(!allow(defaults, Set.of("placer"), "library.save"), "placer cannot save");
        check(allow(defaults, Set.of("author"), "book.sign") && allow(defaults, Set.of("author"), "item.lore") && allow(defaults, Set.of("author"), "library.save"), "author permissions");
        check(!allow(defaults, Set.of("author"), "library.update") && !allow(defaults, Set.of("author"), "library.delete"), "author cannot overwrite/delete");
        for (String node : LoreConfig.NODES) check(LorePermissions.roleAllows(defaults.roles, Set.of("admin"), node), "admin " + node);
        check(!allow(defaults, Set.of("admin"), "future.unknown"), "fixed node universe");
        LoreConfig denied = new LoreConfig(); denied.roles.put("restricted", Set.of("-loretools.book.*", "-loretools.library.delete")); denied.validate();
        check(!allow(denied, Set.of("admin", "restricted"), "book.sign"), "wildcard deny overrides admin grant");
        check(!allow(denied, Set.of("restricted", "admin"), "library.delete"), "exact deny overrides grant independent of role order");
        check(allow(denied, Set.of("admin", "restricted"), "library.update"), "unrelated permission retained");
        check(LorePermissions.builtinAllows(defaults.roles, Set.of(), "loretools.admin.permissions", true, true), "owner bootstrap permissions");
        check(LorePermissions.builtinAllows(defaults.roles, Set.of(), "loretools.admin.reload", true, false), "owner can repair unhealthy storage configuration");
        check(!LorePermissions.builtinAllows(defaults.roles, Set.of(), "loretools.library.get", true, true), "owner bootstrap grants no ordinary actions");
        LoreConfig bootstrapDenied = new LoreConfig(); bootstrapDenied.roles.put("blocked", Set.of("-loretools.admin.permissions", "-loretools.admin.reload")); bootstrapDenied.validate();
        check(!LorePermissions.builtinAllows(bootstrapDenied.roles, Set.of("blocked"), "loretools.admin.permissions", true, true), "explicit denial overrides owner bootstrap");
        check(!LorePermissions.builtinAllows(bootstrapDenied.roles, Set.of("admin", "blocked"), "loretools.admin.reload", true, true), "explicit denial overrides owner and admin role");
        LoreConfig wildcardDenied = new LoreConfig(); wildcardDenied.roles.put("blocked", Set.of("-loretools.admin.*")); wildcardDenied.validate();
        check(!LorePermissions.builtinAllows(wildcardDenied.roles, Set.of("blocked"), "loretools.admin.permissions", true, true), "wildcard denial overrides owner bootstrap");
        check(!LorePermissions.builtinAllows(defaults.roles, Set.of("admin"), "loretools.library.get", false, false), "storage failure denies assigned ordinary permission");
        Path marker = directory.resolve("marker.json");
        check(LorePermissions.selectBackend(marker, "auto", true).equals("external"), "auto discovers external");
        check(LorePermissions.selectBackend(marker, "auto", false).equals("external"), "missing external never falls back");
        check(LorePermissions.selectBackend(marker, "builtin", false).equals("builtin"), "explicit builtin switch");
        check(LorePermissions.selectBackend(marker, "auto", true).equals("builtin"), "auto retains explicit prior backend");
        check(Files.exists(directory.resolve("marker.json.bak")), "marker backup");
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Map<UUID, Set<String>> state = LorePermissions.changedAssignments(Map.of(), "grant", uuid, "author");
        state = LorePermissions.changedAssignments(state, "grant", uuid, "viewer");
        Path assignments = directory.resolve("roles.json");
        LoreConfig.writeAtomic(assignments, LorePermissions.serializeAssignments(state), true);
        check(LorePermissions.loadAssignments(assignments).equals(state), "UUID assignment round trip");
        state = LorePermissions.changedAssignments(state, "revoke", uuid, "author");
        LoreConfig.writeAtomic(assignments, LorePermissions.serializeAssignments(state), true);
        check(LorePermissions.loadAssignments(assignments).get(uuid).equals(Set.of("viewer")), "revoke retained after restart");
        check(LorePermissions.loadAssignments(directory.resolve("roles.json.bak")).get(uuid).equals(Set.of("author", "viewer")), "backup retains last complete state");
        state = LorePermissions.changedAssignments(state, "revoke", uuid, "viewer"); check(state.isEmpty(), "last revoke removes empty assignment");
        Files.writeString(assignments, "{\"version\":1,\"players\":{\"not-a-uuid\":[\"admin\"]}}");
        expectFailure(() -> LorePermissions.loadAssignments(assignments), "malformed UUID rejected");
        Files.writeString(assignments, "{\"version\":1,\"players\":{\"00000000-0000-0000-0000-000000000001\":[\"admin\",\"admin\"]}}");
        expectFailure(() -> LorePermissions.loadAssignments(assignments), "duplicate role rejected");
        try (LorePermissions permissions = new LorePermissions(directory.resolve("runtime"), defaults, false)) {
            check(permissions.backend().equals("builtin"), "initial built-in selection");
            LoreConfig replacement = new LoreConfig(); replacement.permissionMode = "external";
            expectFailure(() -> permissions.reload(replacement), "mode change needs restart");
            check(permissions.backend().equals("builtin"), "failed reload retains authority");
        }
        System.out.println("LorePermissionsTest: " + checks + " checks passed");
        // Only the unique directory created above is removed; no user/runtime files are involved.
        try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private static boolean allow(LoreConfig config, Set<String> roles, String suffix) { return LorePermissions.roleAllows(config.roles, roles, "loretools." + suffix); }
    private static void rejectConfig(Path path, String json) throws Exception { Files.writeString(path, json); expectFailure(() -> LoreConfig.load(path), "invalid config rejected: " + json); }
    private static void check(boolean condition, String description) { if (!condition) throw new AssertionError(description); checks++; }
    private static void expectFailure(Action action, String description) throws Exception { try { action.run(); } catch (IllegalArgumentException e) { checks++; return; } throw new AssertionError(description); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
