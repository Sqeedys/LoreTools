package dev.loretools;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread index; immutable serialized snapshots; one bounded asynchronous disk writer. */
public final class BookLibrary implements AutoCloseable {
    public static final int MAX_ENTRY_BYTES = 2_000_000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public record Summary(String title, String filteredTitle, String author, int pages, String preview, String filteredPreview) {
        public String title(boolean filtered){return filtered?filteredTitle:title;}
        public String preview(boolean filtered){return filtered?filteredPreview:preview;}
    }
    public record Entry(String id, String revision, JsonElement data, Summary summary) {
        public Entry { data = data.deepCopy(); }
        @Override public JsonElement data() { return data.deepCopy(); }
    }
    private final Path directory;
    private final RegistryOps<JsonElement> ops;
    private final MinecraftServer server;
    private final NavigableMap<String, Entry> entries = new TreeMap<>();
    private final Set<String> blocked = new HashSet<>(), busy = new HashSet<>(), knownIds = new HashSet<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("LoreTools-library").factory());

    public BookLibrary(Path directory, MinecraftServer server) throws IOException {
        this.directory = directory; this.server = server;
        this.ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Files.createDirectories(directory);
        Set<String> names = new TreeSet<>();
        try (var files = Files.list(directory)) {
            files.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".json")) names.add(n.substring(0, n.length()-5));
                else if (n.endsWith(".json.bak")) names.add(n.substring(0, n.length()-9));
            });
        }
        if (names.size() > 10000) throw new IOException("Library exceeds 10000 entries; startup refused without changing data.");
        knownIds.addAll(names);
        for (String id : names) {
            try { validateId(id); load(id, directory.resolve(id+".json")); }
            catch (Exception broken) {
                blocked.add(id);
                LoreTools.LOG.error("Library entry {} requires offline repair; original preserved", id);
                try { load(id, directory.resolve(id+".json.bak")); }
                catch (Exception backup) { LoreTools.LOG.error("No usable backup for entry {}", id); }
            }
        }
    }
    public static void validateId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}") || Set.of("con","prn","aux","nul","com1","com2","com3","com4","com5","com6","com7","com8","com9","lpt1","lpt2","lpt3","lpt4","lpt5","lpt6","lpt7","lpt8","lpt9").contains(id))
            throw new IllegalArgumentException("ID must be 1–64 lowercase letters, digits, _ or -, starting with a letter/digit; reserved filenames are excluded.");
    }
    private void load(String id, Path file) throws IOException {
        validateId(id);
        if (Files.size(file) > MAX_ENTRY_BYTES) throw new IOException("Oversized entry");
        JsonObject doc = LoreConfig.readObject(file);
        validateEnvelope(doc,id);
        String revision = UUID.fromString(doc.get("revision").getAsString()).toString();
        if (doc.get("deleted").getAsBoolean()) return;
        Entry entry = entry(id, revision, doc.get("item"));
        validateBook(decode(entry)); entries.put(id, entry);
    }
    static void validateEnvelope(JsonObject doc,String id) {
        if(!doc.has("schema")||!doc.get("schema").isJsonPrimitive()||!doc.getAsJsonPrimitive("schema").isNumber()||!doc.get("schema").getAsString().equals("1"))
            throw new IllegalArgumentException("Unsupported library schema.");
        for(String field:List.of("id","revision"))if(!doc.has(field)||!doc.get(field).isJsonPrimitive()||!doc.getAsJsonPrimitive(field).isString())
            throw new IllegalArgumentException("Invalid library "+field+".");
        if(!doc.get("id").getAsString().equals(id)||!UUID.fromString(doc.get("revision").getAsString()).toString().equals(doc.get("revision").getAsString()))
            throw new IllegalArgumentException("Invalid library identity.");
        if(!doc.has("deleted")||!doc.get("deleted").isJsonPrimitive()||!doc.getAsJsonPrimitive("deleted").isBoolean())
            throw new IllegalArgumentException("Invalid deletion marker.");
        Set<String> fields=doc.get("deleted").getAsBoolean()?Set.of("schema","id","revision","deleted"):Set.of("schema","id","revision","deleted","item");
        if(!doc.keySet().equals(fields)||(!doc.get("deleted").getAsBoolean()&&!doc.get("item").isJsonObject()))throw new IllegalArgumentException("Invalid library envelope fields.");
    }
    public static void validateBook(ItemStack stack) {
        if (stack.getItem()!=Items.WRITTEN_BOOK || stack.getCount()!=1 || stack.get(DataComponents.WRITTEN_BOOK_CONTENT)==null)
            throw new IllegalArgumentException("Hold exactly one signed Written Book in your main hand.");
        ItemStack.validateStrict(stack).getOrThrow();
    }
    public JsonElement encode(ItemStack stack) {
        validateBook(stack);
        JsonElement data = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        validateDepth(data,0);
        if (data.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_BYTES-4096)
            throw new IllegalArgumentException("Book metadata exceeds the library's 2 MB entry limit.");
        return data;
    }
    public ItemStack decode(Entry entry) { return ItemStack.CODEC.parse(ops, entry.data()).getOrThrow(); }
    private Entry entry(String id,String revision,JsonElement data) {
        validateDepth(data,0);
        ItemStack stack=ItemStack.CODEC.parse(ops,data).getOrThrow();validateBook(stack);
        WrittenBookContent b=stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        String raw=b.pages().isEmpty()?"[No pages]":b.pages().getFirst().raw().getString();
        String filtered=b.pages().isEmpty()?"[No pages]":b.pages().getFirst().get(true).getString();
        return new Entry(id,revision,data,new Summary(b.title().raw(),b.title().get(true),b.author(),b.pages().size(),LibraryMenu.shortText(raw,100),LibraryMenu.shortText(filtered,100)));
    }
    private static void validateDepth(JsonElement value,int depth) {
        // Match the strict persisted-envelope reader's bound, including the outer envelope.
        if(depth>14)throw new IllegalArgumentException("Book component nesting exceeds the library format limit.");
        if(value.isJsonObject())for(var e:value.getAsJsonObject().entrySet())validateDepth(e.getValue(),depth+1);
        else if(value.isJsonArray())for(var e:value.getAsJsonArray())validateDepth(e,depth+1);
    }
    public Entry find(String id) { validateId(id); return entries.get(id); }
    public List<Entry> search(String search, boolean filtered) {
        String q = search.toLowerCase(Locale.ROOT);
        return entries.values().stream().filter(e -> {
            Summary b = e.summary();
            return (e.id()+"\n"+b.title(filtered)+"\n"+b.author()).toLowerCase(Locale.ROOT).contains(q);
        }).toList();
    }
    public boolean current(Entry e) { Entry now = entries.get(e.id()); return now != null && now.revision().equals(e.revision()); }

    public CompletableFuture<Entry> write(String id, String expectedRevision, JsonElement item) {
        validateId(id);
        if (blocked.contains(id)) throw new IllegalArgumentException("This entry needs offline repair; see the server log. No data changed.");
        if (busy.contains(id)) throw new IllegalArgumentException("This entry has a pending write; retry shortly.");
        if (busy.size() >= 128) throw new IllegalArgumentException("Library write queue is full; retry shortly.");
        Entry before = entries.get(id);
        if (!Objects.equals(expectedRevision, before == null ? null : before.revision())) throw new IllegalArgumentException("Template changed; request a new confirmation.");
        if (expectedRevision==null && item==null) throw new IllegalArgumentException("No such template.");
        if (!knownIds.contains(id) && knownIds.size()>=10000) throw new IllegalArgumentException("Library ID limit reached, including deleted IDs. Reuse a deleted ID or archive entries offline.");
        Entry after = item==null ? null : entry(id, UUID.randomUUID().toString(), item);
        if (after!=null) validateBook(decode(after));
        JsonObject doc = new JsonObject(); doc.addProperty("schema",1); doc.addProperty("id",id);
        doc.addProperty("revision",after==null?UUID.randomUUID().toString():after.revision()); doc.addProperty("deleted",after==null);
        if(after!=null) doc.add("item",after.data());
        byte[] bytes = JSON.toJson(doc).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_ENTRY_BYTES) throw new IllegalArgumentException("Serialized entry exceeds 2 MB.");
        busy.add(id); knownIds.add(id);
        CompletableFuture<Entry> result = new CompletableFuture<>();
        writer.execute(() -> {
            Throwable failure = null;
            try { safeWrite(directory.resolve(id+".json"),bytes); } catch(Throwable ex) { failure=ex; }
            Throwable error = failure;
            server.execute(() -> {
                busy.remove(id);
                if(error!=null) { result.completeExceptionally(error); return; }
                if(after==null) entries.remove(id); else entries.put(id,after);
                result.complete(after);
            });
        });
        return result;
    }
    public static void safeWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName()+".tmp");
        try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        if(Files.exists(target)) {
            Path backupTmp=target.resolveSibling(target.getFileName()+".bak.tmp");
            Files.copy(target,backupTmp,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel channel=FileChannel.open(backupTmp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(backupTmp,target.resolveSibling(target.getFileName()+".bak"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        // Unsupported atomic moves fail visibly; never degrade to a delete-then-write sequence.
        Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void close() {
        writer.shutdown();
        try { if(!writer.awaitTermination(30,TimeUnit.SECONDS)) LoreTools.LOG.error("Library writes still pending at shutdown; inspect .tmp files before recovery."); }
        catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
