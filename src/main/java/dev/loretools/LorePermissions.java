package dev.loretools;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** One permission authority, selected at startup; never silently fall back from external. */
public final class LorePermissions implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("loretools-permissions");
    private final Path assignmentsFile;
    private final String backend;
    private final String configuredMode;
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("loretools-permissions-io").factory());
    private volatile LoreConfig config;
    private volatile Map<UUID, Set<String>> assignments;
    private volatile boolean persistenceFailed;
    private volatile boolean closed;

    public LorePermissions(Path runtimeDir, LoreConfig config) {
        this(runtimeDir, config, FabricLoader.getInstance().isModLoaded("luckperms"));
    }
    // Deterministic startup testing without a Fabric launch.
    LorePermissions(Path runtimeDir, LoreConfig config, boolean externalPresent) {
        config.validate(); this.config = config; configuredMode = config.permissionMode;
        assignmentsFile = runtimeDir.resolve("roles.json");
        try {
            Files.createDirectories(runtimeDir);
            backend = selectBackend(runtimeDir.resolve("permission-backend.json"), config.permissionMode, externalPresent);
            assignments = loadAssignments(assignmentsFile);
        } catch (IOException e) { writer.shutdown(); throw LoreConfig.invalid("Cannot initialize Lore Tools permissions: " + e.getMessage()); }
    }
    static String selectBackend(Path marker, String mode, boolean externalPresent) throws IOException {
        String previous = null;
        if (Files.exists(marker)) {
            JsonObject obj = LoreConfig.readObject(marker);
            if (!obj.keySet().equals(Set.of("version", "backend")) || !obj.get("version").isJsonPrimitive() || !obj.get("version").getAsString().equals("1") || !obj.get("backend").isJsonPrimitive() || !obj.getAsJsonPrimitive("backend").isString()) throw LoreConfig.invalid("Invalid permission backend marker; repair explicitly");
            previous = obj.get("backend").getAsString();
            if (!Set.of("builtin", "external").contains(previous)) throw LoreConfig.invalid("Invalid permission backend marker");
        }
        String selected = mode.equals("auto") ? (previous == null ? (externalPresent ? "external" : "builtin") : previous) : mode;
        if (!selected.equals(previous)) {
            JsonObject obj = new JsonObject(); obj.addProperty("version", 1); obj.addProperty("backend", selected);
            LoreConfig.writeAtomic(marker, LoreConfig.JSON.toJson(obj), true);
        }
        return selected;
    }
    public String backend() { return backend; }
    public void reload(LoreConfig replacement) {
        replacement.validate();
        if (!configuredMode.equals(replacement.permissionMode)) throw LoreConfig.invalid("Changing permissionMode requires a server restart");
        config = replacement;
    }
    public boolean allowed(CommandSourceStack source, String node) {
        if (!LoreConfig.NODES.contains(node) || source == null) return false;
        ServerPlayer player = source.getPlayer();
        // A source captured before an async callback can retain a now-revoked OP level.
        boolean owner = (player == null ? source.permissions() : player.createCommandSourceStack().permissions())
            .hasPermission(Permissions.COMMANDS_OWNER);
        // An entityless command block is not trusted: only explicit OWNER authority qualifies.
        if (player == null) return source.getEntity() == null && owner;
        if (backend.equals("external")) {
            try { return LuckPermsBridge.allowed(player, node); }
            catch (LinkageError | RuntimeException e) { return false; }
        }
        return builtinAllows(config.roles, assignments.getOrDefault(player.getUUID(), Set.of()), node, owner, !persistenceFailed);
    }
    static boolean builtinAllows(Map<String, Set<String>> roles, Set<String> assigned, String node, boolean owner, boolean storageHealthy) {
        int decision = roleDecision(roles, assigned, node);
        if (decision < 0 || !LoreConfig.NODES.contains(node)) return false;
        if (owner && (node.equals("loretools.admin.permissions") || node.equals("loretools.admin.reload"))) return true;
        return storageHealthy && decision > 0;
    }
    static boolean roleAllows(Map<String, Set<String>> roles, Set<String> assigned, String node) {
        return roleDecision(roles, assigned, node) > 0;
    }
    private static int roleDecision(Map<String, Set<String>> roles, Set<String> assigned, String node) {
        if (!LoreConfig.NODES.contains(node)) return -1;
        boolean granted = false;
        for (String role : assigned) for (String raw : roles.getOrDefault(role, Set.of())) {
            boolean deny = raw.startsWith("-"); String pattern = deny ? raw.substring(1) : raw;
            if (LoreConfig.matches(pattern, node)) { if (deny) return -1; granted = true; }
        }
        return granted ? 1 : 0;
    }
    public int manage(CommandSourceStack source, String operation, String target, String role) {
        if (!allowed(source, "loretools.admin.permissions")) throw LoreConfig.invalid("Permission denied");
        if (!backend.equals("builtin")) throw LoreConfig.invalid("Built-in roles are inactive; manage permissions with LuckPerms");
        if (!Set.of("grant", "revoke", "show").contains(operation)) throw LoreConfig.invalid("Expected grant, revoke or show");
        UUID uuid = resolveTarget(source, target);
        if (operation.equals("show")) {
            source.sendSuccess(() -> Component.literal(uuid + ": " + new TreeSet<>(assignments.getOrDefault(uuid, Set.of()))), false);
            return 1;
        }
        if (role == null || !config.roles.containsKey(role)) throw LoreConfig.invalid("Unknown role");
        if (closed || persistenceFailed) throw LoreConfig.invalid("Permission storage is unavailable; inspect server log and restart after repair");
        String actor = source.getPlayer() == null ? "console" : source.getPlayer().getUUID().toString();
        try { writer.execute(() -> {
            if (!config.roles.containsKey(role)) {
                source.getServer().execute(() -> source.sendFailure(Component.literal("Permission change cancelled: the role was removed by configuration reload.")));
                return;
            }
            if (persistenceFailed) {
                source.getServer().execute(() -> source.sendFailure(Component.literal("Permission change cancelled: an earlier storage operation failed.")));
                return;
            }
            Map<UUID, Set<String>> changed = changedAssignments(assignments, operation, uuid, role);
            String serialized = serializeAssignments(changed);
            if (serialized.getBytes(StandardCharsets.UTF_8).length + 1 > 2_000_000) {
                source.getServer().execute(() -> source.sendFailure(Component.literal("Permission change cancelled: the assignment file size limit would be exceeded.")));
                return;
            }
            try {
                LoreConfig.writeAtomic(assignmentsFile, serialized, true);
            } catch (IOException | RuntimeException e) {
                persistenceFailed = true;
                LOG.error("Lore Tools permission persistence failed; built-in role checks now fail closed", e);
                source.getServer().execute(() -> source.sendFailure(Component.literal("Permission change was not confirmed. Role checks disabled until storage is repaired and server restarted.")));
                return;
            }
            assignments = changed;
            LOG.info("Lore Tools permission action={} actor={} target={} role={} time={}", operation, actor, uuid, role, Instant.now());
            source.getServer().execute(() -> {
                ServerPlayer changedPlayer = source.getServer().getPlayerList().getPlayer(uuid);
                if (changedPlayer != null) source.getServer().getCommands().sendCommands(changedPlayer);
                source.sendSuccess(() -> Component.literal("Saved " + operation + " " + role + " for " + uuid), false);
            });
        }); } catch (RejectedExecutionException e) { throw LoreConfig.invalid("Permission storage queue is full or stopping; retry shortly"); }
        source.sendSuccess(() -> Component.literal("Permission change queued; waiting for durable storage."), false);
        return 1;
    }
    static Map<UUID, Set<String>> changedAssignments(Map<UUID, Set<String>> current, String operation, UUID uuid, String role) {
        Map<UUID, Set<String>> out = new HashMap<>(current); Set<String> assigned = new HashSet<>(out.getOrDefault(uuid, Set.of()));
        if (operation.equals("grant")) assigned.add(role); else if (operation.equals("revoke")) assigned.remove(role); else throw LoreConfig.invalid("Invalid role operation");
        if (assigned.isEmpty()) out.remove(uuid); else out.put(uuid, Set.copyOf(assigned));
        return Map.copyOf(out);
    }
    private static UUID resolveTarget(CommandSourceStack source, String target) {
        if (target == null) throw LoreConfig.invalid("Specify an online player name or UUID");
        try { UUID uuid = UUID.fromString(target); if (uuid.toString().equalsIgnoreCase(target)) return uuid; } catch (IllegalArgumentException ignored) { }
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(target);
        if (player == null) throw LoreConfig.invalid("Player is not online; specify the verified UUID explicitly");
        return player.getUUID();
    }
    static Map<UUID, Set<String>> loadAssignments(Path file) throws IOException {
        if (!Files.exists(file)) return Map.of();
        JsonObject root = LoreConfig.readObject(file);
        if (!root.keySet().equals(Set.of("version", "players")) || !root.get("version").isJsonPrimitive() || !root.get("version").getAsString().equals("1") || !root.get("players").isJsonObject()) throw LoreConfig.invalid("Invalid role assignments file");
        Map<UUID, Set<String>> out = new HashMap<>();
        for (var entry : root.getAsJsonObject("players").entrySet()) {
            UUID uuid = UUID.fromString(entry.getKey());
            if (!uuid.toString().equals(entry.getKey()) || !entry.getValue().isJsonArray()) throw LoreConfig.invalid("Invalid assignment UUID or roles");
            Set<String> roles = new HashSet<>();
            for (JsonElement value : entry.getValue().getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("[a-z][a-z0-9_-]{0,31}") || !roles.add(value.getAsString())) throw LoreConfig.invalid("Invalid assigned role");
            }
            if (roles.size() > 128) throw LoreConfig.invalid("Too many assigned roles");
            out.put(uuid, Set.copyOf(roles));
        }
        return Map.copyOf(out);
    }
    static String serializeAssignments(Map<UUID, Set<String>> assignments) {
        JsonObject root = new JsonObject(); root.addProperty("version", 1); JsonObject players = new JsonObject();
        assignments.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonArray roles = new JsonArray(); new TreeSet<>(entry.getValue()).forEach(roles::add); players.add(entry.getKey().toString(), roles);
        });
        root.add("players", players); return LoreConfig.JSON.toJson(root);
    }
    @Override public void close() {
        closed = true; writer.shutdown();
        try { if (!writer.awaitTermination(30, TimeUnit.SECONDS)) LOG.error("Permission writes did not finish before shutdown timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
