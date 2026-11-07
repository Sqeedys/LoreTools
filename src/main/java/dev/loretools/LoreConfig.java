package dev.loretools;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned, strictly validated configuration. Construct a replacement before publishing a reload. */
public final class LoreConfig {
    public static final Set<String> NODES = Set.of(
        "loretools.book.sign", "loretools.book.author", "loretools.book.title", "loretools.book.draft",
        "loretools.library.browse", "loretools.library.get", "loretools.library.save", "loretools.library.update", "loretools.library.delete",
        "loretools.item.name", "loretools.item.lore", "loretools.admin.reload", "loretools.admin.permissions");
    public static final Set<String> CANONICAL_COMMANDS = Set.of("sign", "author", "title", "draft", "name", "resetname", "lore", "save", "get", "library", "list", "update", "delete", "reload", "help", "permissions");
    private static final Set<String> FIELDS = Set.of("version", "defaultTitle", "guiEnabled", "maxNameChars", "maxLoreLines", "maxLoreLineChars", "maxLoreTotalChars", "permissionMode", "roles", "aliases");
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public int version = 1;
    public String defaultTitle = "Untitled";
    public boolean guiEnabled = true;
    public int maxNameChars = 256;
    public int maxLoreLines = 32;
    public int maxLoreLineChars = 256;
    public int maxLoreTotalChars = 4096;
    public String permissionMode = "auto";
    public Map<String, Set<String>> roles = defaults();
    public Map<String, String> aliases = defaultAliases();

    private static Map<String, Set<String>> defaults() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        out.put("viewer", Set.of("loretools.library.browse"));
        out.put("placer", Set.of("loretools.library.browse", "loretools.library.get"));
        out.put("author", Set.of("loretools.book.*", "loretools.item.*", "loretools.library.browse", "loretools.library.get", "loretools.library.save"));
        out.put("admin", NODES);
        return out;
    }
    private static Map<String, String> defaultAliases() {
        Map<String, String> out = new LinkedHashMap<>();
        String[] pairs = {"sign", "sign", "author", "author", "title", "booktitle", "draft", "draft", "name", "name", "resetname", "resetname", "lore", "lore", "save", "savebook", "get", "getbook", "library", "library", "list", "listbooks", "update", "updatebook", "delete", "deletebook", "reload", "ltreload"};
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }
    public static LoreConfig load(Path configFile) {
        try {
            if (!Files.exists(configFile)) {
                LoreConfig defaults = new LoreConfig(); defaults.validate();
                writeAtomic(configFile, JSON.toJson(defaults), false);
                return defaults;
            }
            JsonObject root = readObject(configFile);
            for (String key : root.keySet()) if (!FIELDS.contains(key)) throw invalid("Unknown config field: " + key);
            // Gson alone coerces strings to booleans/numbers; require JSON's declared types first.
            for (String key : List.of("version", "maxNameChars", "maxLoreLines", "maxLoreLineChars", "maxLoreTotalChars"))
                if (root.has(key)) requireInteger(root.get(key), key);
            for (String key : List.of("defaultTitle", "permissionMode")) if (root.has(key)) requireString(root.get(key), key);
            if (root.has("guiEnabled") && (!root.get("guiEnabled").isJsonPrimitive() || !root.getAsJsonPrimitive("guiEnabled").isBoolean())) throw invalid("guiEnabled must be boolean");
            if (root.has("roles")) {
                if (!root.get("roles").isJsonObject()) throw invalid("roles must be an object");
                for (var role : root.getAsJsonObject("roles").entrySet()) {
                    if (!role.getValue().isJsonArray()) throw invalid("Role permissions must be arrays");
                    for (var node : role.getValue().getAsJsonArray()) requireString(node, "role permission");
                }
            }
            if (root.has("aliases")) {
                if (!root.get("aliases").isJsonObject()) throw invalid("aliases must be an object");
                for (var alias : root.getAsJsonObject("aliases").entrySet()) requireString(alias.getValue(), "alias");
            }
            LoreConfig loaded = JSON.fromJson(root, LoreConfig.class);
            loaded.validate(); return loaded;
        } catch (IOException | JsonParseException e) { throw invalid("Cannot load Lore Tools config: " + e.getMessage()); }
    }
    public void validate() {
        if (version != 1) throw invalid("Unsupported config version");
        if (defaultTitle == null || defaultTitle.isBlank() || defaultTitle.length() > 32) throw invalid("defaultTitle must contain 1–32 UTF-16 characters");
        for (int i = 0; i < defaultTitle.length(); i++) {
            char c = defaultTitle.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= defaultTitle.length() || !Character.isLowSurrogate(defaultTitle.charAt(i))) throw invalid("defaultTitle contains invalid Unicode");
            } else if (Character.isLowSurrogate(c) || c == '\u00a7' || Character.isISOControl(c)
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) throw invalid("defaultTitle contains unsupported control or formatting characters");
        }
        bounded(maxNameChars, 1, 1024, "maxNameChars"); bounded(maxLoreLines, 1, 256, "maxLoreLines");
        bounded(maxLoreLineChars, 1, 1024, "maxLoreLineChars"); bounded(maxLoreTotalChars, 1, 16384, "maxLoreTotalChars");
        if (!Set.of("auto", "builtin", "external").contains(permissionMode == null ? "" : permissionMode)) throw invalid("permissionMode must be auto, builtin or external");
        if (roles == null || roles.size() > 128) throw invalid("Invalid roles");
        for (var role : roles.entrySet()) {
            if (!role.getKey().matches("[a-z][a-z0-9_-]{0,31}") || role.getValue() == null || role.getValue().size() > 128) throw invalid("Invalid role definition");
            for (String raw : role.getValue()) {
                if (raw == null) throw invalid("Null permission");
                String node = raw.startsWith("-") ? raw.substring(1) : raw;
                if (!validPattern(node)) throw invalid("Unknown permission: " + raw);
            }
        }
        if (aliases == null || aliases.size() > CANONICAL_COMMANDS.size()) throw invalid("Invalid aliases");
        Set<String> shortcuts = new HashSet<>();
        for (var alias : aliases.entrySet()) {
            if (!CANONICAL_COMMANDS.contains(alias.getKey())) throw invalid("Unknown alias command: " + alias.getKey());
            if (alias.getValue() == null || !alias.getValue().matches("[a-z][a-z0-9_-]{0,31}") || alias.getValue().equals("loretools") || alias.getValue().equals("lt") || !shortcuts.add(alias.getValue())) throw invalid("Invalid or duplicate shortcut: " + alias.getValue());
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        roles.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        roles = Collections.unmodifiableMap(immutable); aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
    }
    static boolean matches(String pattern, String node) {
        return pattern.equals(node) || (pattern.endsWith(".*") && node.startsWith(pattern.substring(0, pattern.length() - 1)));
    }
    private static boolean validPattern(String node) { return NODES.contains(node) || (node.endsWith(".*") && NODES.stream().anyMatch(n -> matches(node, n))); }
    private static void bounded(int value, int min, int max, String key) { if (value < min || value > max) throw invalid(key + " must be " + min + "–" + max); }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static void requireString(JsonElement e, String key) { if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(key + " must be a string"); }
    private static void requireInteger(JsonElement e, String key) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !e.getAsString().matches("-?(0|[1-9][0-9]*)")) throw invalid(key + " must be an integer");
        try { Integer.parseInt(e.getAsString()); } catch (NumberFormatException ex) { throw invalid(key + " is out of range"); }
    }
    static JsonObject readObject(Path file) throws IOException {
        if (Files.size(file) > 2_000_000) throw invalid("JSON file exceeds size limit");
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = readValue(reader, 0);
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Expected exactly one JSON object");
            return root.getAsJsonObject();
        }
    }
    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw invalid("JSON nesting exceeds limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> { JsonObject out = new JsonObject(); reader.beginObject(); while (reader.hasNext()) { String key = reader.nextName(); if (out.has(key)) throw invalid("Duplicate JSON field: " + key); out.add(key, readValue(reader, depth + 1)); } reader.endObject(); return out; }
            case BEGIN_ARRAY -> { JsonArray out = new JsonArray(); reader.beginArray(); while (reader.hasNext()) out.add(readValue(reader, depth + 1)); reader.endArray(); return out; }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new JsonPrimitive(new java.math.BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw invalid("Malformed JSON");
        }
    }
    static void writeAtomic(Path file, String data, boolean backup) throws IOException {
        if (data.getBytes(StandardCharsets.UTF_8).length + 1 > 2_000_000) throw new IOException("JSON file exceeds size limit");
        Path absolute = file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".loretools-", ".tmp");
        Path backupTemporary = null;
        try {
            Files.writeString(temporary, data + "\n", StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (backup && Files.exists(absolute)) {
                backupTemporary = Files.createTempFile(absolute.getParent(), ".loretools-backup-", ".tmp");
                Files.copy(absolute, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel channel = FileChannel.open(backupTemporary, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(backupTemporary, absolute.resolveSibling(absolute.getFileName() + ".bak"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            // Fail rather than degrade to a non-atomic replacement on unsupported storage.
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); if (backupTemporary != null) Files.deleteIfExists(backupTemporary); }
    }
}
