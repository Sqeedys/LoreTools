package dev.loretools;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/** Direct native-runtime tests; no server, player, world, or mock item components. */
public final class ItemOperationsTest {
    private static int checks;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        bookRoundTrip();
        bookValidation();
        namesAndLore();
        System.out.println("ItemOperationsTest: " + checks + " checks passed");
    }

    private static void bookRoundTrip() {
        ItemStack original = writable(new Filterable<>("Raw \ud83d\udcda\nnext", Optional.of("Filtered\ttext")));
        original.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
        original.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Owned"))));
        ItemStack snapshot = original.copy();
        ItemStack signed = ItemOperations.sign(original, "Writer \u00e9", "A \ud83d\udcda tale");
        check(signed != original && ItemStack.matches(snapshot, original), "sign leaves original unchanged");
        check(signed.getItem() == Items.WRITTEN_BOOK && signed.getCount() == 1, "signed item type/count");
        check(!signed.has(DataComponents.WRITABLE_BOOK_CONTENT), "old content removed");
        WrittenBookContent content = signed.get(DataComponents.WRITTEN_BOOK_CONTENT);
        check(content.author().equals("Writer \u00e9") && content.title().raw().equals("A \ud83d\udcda tale"), "actual book fields");
        check(content.resolved() && content.generation() == 0, "new original literal book");
        check(content.pages().getFirst().raw().getString().equals("Raw \ud83d\udcda\nnext"), "raw page preserved");
        check(content.pages().getFirst().filtered().orElseThrow().getString().equals("Filtered\ttext"), "filtered page preserved");
        check(content.pages().getFirst().raw().getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents, "literal page");
        check(signed.get(DataComponents.CUSTOM_NAME).equals(original.get(DataComponents.CUSTOM_NAME)), "custom name retained");
        check(signed.get(DataComponents.LORE).equals(original.get(DataComponents.LORE)), "lore retained");
        check(WrittenBookContent.CODEC.encodeStart(JsonOps.INSTANCE, content).isSuccess(), "native written codec accepts");
        ItemStack draft = ItemOperations.draft(signed);
        check(draft.getItem() == Items.WRITABLE_BOOK && !draft.has(DataComponents.WRITTEN_BOOK_CONTENT), "draft replaces signed fields");
        check(draft.get(DataComponents.WRITABLE_BOOK_CONTENT).equals(original.get(DataComponents.WRITABLE_BOOK_CONTENT)), "filterable round trip");
        check(ItemStack.matches(original, snapshot), "round trip original untouched");
        check(WritableBookContent.CODEC.encodeStart(JsonOps.INSTANCE, draft.get(DataComponents.WRITABLE_BOOK_CONTENT)).isSuccess(), "native writable codec accepts");

        WrittenBookContent imported = new WrittenBookContent(new Filterable<>("Raw title", Optional.of("Safe title")),
                "Old", 2, List.of(new Filterable<>(Component.literal("One").append(Component.literal(" two")),
                Optional.of(Component.literal("Safe")))), false);
        signed.set(DataComponents.WRITTEN_BOOK_CONTENT, imported);
        ItemStack changedAuthor = ItemOperations.author(signed, "New");
        WrittenBookContent author = changedAuthor.get(DataComponents.WRITTEN_BOOK_CONTENT);
        check(author.title().equals(imported.title()) && author.pages().equals(imported.pages()), "author keeps filter metadata");
        check(author.generation() == 2 && !author.resolved(), "author keeps flags");
        WrittenBookContent title = ItemOperations.title(signed, "New title").get(DataComponents.WRITTEN_BOOK_CONTENT);
        check(title.author().equals("Old") && title.pages().equals(imported.pages()) && title.generation() == 2 && !title.resolved(), "title keeps other book metadata");
        check(title.title().raw().equals("New title") && title.title().filtered().isEmpty(), "title replaces obsolete filter value");
        check(signed.get(DataComponents.WRITTEN_BOOK_CONTENT).equals(imported), "metadata transforms leave original unchanged");
        check(ItemOperations.draft(signed).get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw().equals("One two"), "nested literal siblings flatten in order");
    }

    private static void bookValidation() {
        ItemStack book = writable(Filterable.passThrough("Page"));
        reject(() -> ItemOperations.sign(new ItemStack(Items.STONE), "A", "T"), "wrong item");
        reject(() -> ItemOperations.sign(book.copyWithCount(2), "A", "T"), "stacked writable");
        reject(() -> ItemOperations.sign(book, "A", "x".repeat(33)), "title limit");
        reject(() -> ItemOperations.sign(book, "x".repeat(129), "T"), "author limit");
        reject(() -> ItemOperations.sign(book, "\ud800", "T"), "unpaired surrogate");
        reject(() -> ItemOperations.sign(book, "A", "\u00a7cRed"), "section formatting");
        reject(() -> ItemOperations.sign(book, "A", "bad\u0000title"), "control character");
        reject(() -> ItemOperations.sign(book, "A", "   "), "blank title");
        reject(() -> ItemOperations.sign(book, "A", "\ud83d\udcda".repeat(17)), "title UTF16 length");
        check(ItemOperations.sign(book, "A".repeat(128), "\ud83d\udcda".repeat(16)) != null, "exact author/title limits");
        ItemStack longPage = writable(new Filterable<>("fine", Optional.of("x".repeat(1025))));
        reject(() -> ItemOperations.sign(longPage, "A", "T"), "filtered page length");
        ItemStack hundred = new ItemStack(Items.WRITABLE_BOOK);
        hundred.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(java.util.Collections.nCopies(100, Filterable.passThrough("x".repeat(1024)))));
        check(ItemOperations.draft(ItemOperations.sign(hundred, "A", "T")).get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size() == 100, "100 pages at exact maximum");
        ItemStack signed = ItemOperations.sign(book, "A", "T");
        reject(() -> ItemOperations.author(signed.copyWithCount(2), "B"), "stacked author");
        reject(() -> ItemOperations.title(signed.copyWithCount(2), "B"), "stacked title");
        reject(() -> ItemOperations.draft(signed.copyWithCount(2)), "stacked draft");
        reject(() -> ItemOperations.draft(book), "unsigned draft");
        for (Component unsafe : List.of(Component.literal("red").withStyle(ChatFormatting.RED),
                Component.translatable("item.minecraft.book"), Component.keybind("key.jump"),
                Component.literal("plain").append(Component.literal("bold").withStyle(ChatFormatting.BOLD)),
                Component.literal("insert").setStyle(Style.EMPTY.withInsertion("insertion")),
                Component.literal("x".repeat(1025)))) {
            signed.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("T"), "A", 0,
                    List.of(new Filterable<>(Component.literal("raw"), Optional.of(unsafe))), true));
            WrittenBookContent before = signed.get(DataComponents.WRITTEN_BOOK_CONTENT);
            reject(() -> ItemOperations.draft(signed), "unsafe filtered page");
            check(signed.get(DataComponents.WRITTEN_BOOK_CONTENT).equals(before), "rejected draft unchanged");
        }
    }

    private static void namesAndLore() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD, 17);
        stack.set(DataComponents.DAMAGE, 12);
        ItemStack named = ItemOperations.name(stack, "{\"text\":\"literal\"}", 64);
        check(named.getCount() == 17 && named.get(DataComponents.DAMAGE) == 12, "name preserves stack and metadata");
        check(named.getCustomName().getString().equals("{\"text\":\"literal\"}"), "name never parses JSON");
        check(!stack.has(DataComponents.CUSTOM_NAME), "name input unchanged");
        check(!ItemOperations.resetName(named).has(DataComponents.CUSTOM_NAME) && named.has(DataComponents.CUSTOM_NAME), "reset only copy");
        reject(() -> ItemOperations.name(ItemStack.EMPTY, "name", 64), "empty held stack");
        reject(() -> ItemOperations.name(stack, "bad\nname", 64), "multiline name");
        reject(() -> ItemOperations.name(stack, "bad\udc00", 64), "low surrogate");
        check(ItemOperations.name(stack, "\ud83d\udc69\u200d\ud83d\udcbb", 64) != null, "emoji ZWJ accepted");
        MutableComponent old = Component.literal("Old").withStyle(ChatFormatting.GOLD);
        stack.set(DataComponents.LORE, new ItemLore(List.of(old)));
        ItemStack added = ItemOperations.lore(stack, "add", 0, "Second", 4, 12, 20);
        check(added.getCount() == 17 && added.get(DataComponents.DAMAGE) == 12, "lore keeps count and damage");
        check(added.get(DataComponents.LORE).lines().getFirst().equals(old), "existing styled lore preserved");
        check(stack.get(DataComponents.LORE).lines().size() == 1, "add original untouched");
        ItemStack set = ItemOperations.lore(added, "set", 2, "Changed", 4, 12, 20);
        check(set.get(DataComponents.LORE).lines().get(1).getString().equals("Changed"), "set one based");
        ItemStack removed = ItemOperations.lore(set, "remove", 1, null, 4, 12, 20);
        check(removed.get(DataComponents.LORE).lines().size() == 1 && removed.get(DataComponents.LORE).lines().getFirst().getString().equals("Changed"), "remove one based");
        check(!ItemOperations.lore(set, "clear", 0, null, 4, 12, 20).has(DataComponents.LORE), "clear removes component");
        reject(() -> ItemOperations.lore(stack, "set", 0, "x", 4, 12, 20), "zero line");
        reject(() -> ItemOperations.lore(stack, "remove", 2, null, 4, 12, 20), "missing line");
        reject(() -> ItemOperations.lore(stack, "add", 0, "x", 1, 12, 20), "line count maximum");
        reject(() -> ItemOperations.lore(stack, "add", 0, "1234567890", 4, 12, 12), "total includes original");
        reject(() -> ItemOperations.lore(stack, "add", 0, "x", 4, 2, 20), "existing line over limit");
        reject(() -> ItemOperations.lore(stack, "add", 0, "x\nY", 4, 12, 20), "lore newlines");
        reject(() -> ItemOperations.lore(stack, "unknown", 0, null, 4, 12, 20), "unknown action");
        check(stack.get(DataComponents.LORE).lines().equals(List.of(old)), "rejected lore leaves original unchanged");
    }

    private static ItemStack writable(Filterable<String> page) {
        ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
        stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(page)));
        return stack;
    }

    private static void reject(Runnable operation, String label) {
        try { operation.run(); } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(), label + " clear message");
            return;
        }
        throw new AssertionError("Expected rejection: " + label);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
