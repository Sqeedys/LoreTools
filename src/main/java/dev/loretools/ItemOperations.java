package dev.loretools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/** Pure inventory transforms. Callers must authorize and filter new text before replacing a slot. */
public final class ItemOperations {
    private static final int MAX_PAGES = 100;
    private static final int PAGE_CHARS = 1024;
    private static final int TITLE_CHARS = 32;
    private static final int AUTHOR_CHARS = 128;

    private ItemOperations() {}

    public static ItemStack sign(ItemStack input, String author, String title) {
        requireBook(input, Items.WRITABLE_BOOK, "a writable book");
        plain(author, "Author", AUTHOR_CHARS, false, false);
        plain(title, "Title", TITLE_CHARS, false, false);
        WritableBookContent content = input.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (content == null) throw invalid("The writable book has no page content.");
        if (content.pages().size() > MAX_PAGES) throw invalid("Books may have at most 100 pages.");
        List<Filterable<Component>> pages = new ArrayList<>();
        for (Filterable<String> page : content.pages()) {
            pages.add(page.map(text -> Component.literal(plain(text, "Page", PAGE_CHARS, true, true))));
        }
        ItemStack result = input.transmuteCopy(Items.WRITTEN_BOOK, 1);
        result.remove(DataComponents.WRITABLE_BOOK_CONTENT);
        result.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(title), author, 0, List.copyOf(pages), true));
        return result;
    }

    public static ItemStack author(ItemStack input, String author) {
        WrittenBookContent content = written(input);
        plain(author, "Author", AUTHOR_CHARS, false, false);
        ItemStack result = input.copy();
        result.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(content.title(), author,
                content.generation(), content.pages(), content.resolved()));
        return result;
    }

    public static ItemStack title(ItemStack input, String title) {
        WrittenBookContent content = written(input);
        plain(title, "Title", TITLE_CHARS, false, false);
        ItemStack result = input.copy();
        result.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title),
                content.author(), content.generation(), content.pages(), content.resolved()));
        return result;
    }

    public static ItemStack draft(ItemStack input) {
        WrittenBookContent content = written(input);
        if (content.pages().size() > MAX_PAGES) throw invalid("Books may have at most 100 pages.");
        List<Filterable<String>> pages = new ArrayList<>();
        for (Filterable<Component> page : content.pages()) pages.add(page.map(ItemOperations::draftPage));
        ItemStack result = input.transmuteCopy(Items.WRITABLE_BOOK, 1);
        result.remove(DataComponents.WRITTEN_BOOK_CONTENT);
        result.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.copyOf(pages)));
        return result;
    }

    public static ItemStack name(ItemStack input, String name, int maxChars) {
        requireItem(input);
        requireLimit(maxChars, "Name limit");
        plain(name, "Name", maxChars, false, false);
        ItemStack result = input.copy();
        result.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return result;
    }

    public static ItemStack resetName(ItemStack input) {
        requireItem(input);
        ItemStack result = input.copy();
        result.remove(DataComponents.CUSTOM_NAME);
        return result;
    }

    /** add appends; set/remove use one-based indices; clear removes the lore component. */
    public static ItemStack lore(ItemStack input, String action, int oneBasedLine, String text,
                                 int maxLines, int maxLineChars, int maxTotalChars) {
        requireItem(input);
        requireLimit(maxLines, "Lore line limit");
        requireLimit(maxLineChars, "Lore line character limit");
        requireLimit(maxTotalChars, "Lore total character limit");
        if (maxLines > ItemLore.MAX_LINES) throw invalid("Lore line limit exceeds Minecraft's maximum.");
        ItemLore old = input.get(DataComponents.LORE);
        List<Component> lines = new ArrayList<>(old == null ? List.of() : old.lines());
        if (action == null) throw invalid("Lore action is required.");
        switch (action) {
            case "add" -> lines.add(Component.literal(plain(text, "Lore line", maxLineChars, true, false)));
            case "set" -> {
                requireLine(oneBasedLine, lines.size());
                lines.set(oneBasedLine - 1, Component.literal(plain(text, "Lore line", maxLineChars, true, false)));
            }
            case "remove" -> {
                requireLine(oneBasedLine, lines.size());
                lines.remove(oneBasedLine - 1);
            }
            case "clear" -> lines.clear();
            default -> throw invalid("Unknown lore action; use add, set, remove, or clear.");
        }
        if (lines.size() > maxLines) throw invalid("Lore exceeds the limit of " + maxLines + " lines.");
        long total = 0;
        for (Component line : lines) {
            String visible = line.getString();
            if (visible.length() > maxLineChars) throw invalid("Existing lore exceeds the per-line character limit.");
            total += visible.length();
        }
        if (total > maxTotalChars) throw invalid("Lore exceeds the total character limit of " + maxTotalChars + ".");
        ItemStack result = input.copy();
        if (lines.isEmpty()) result.remove(DataComponents.LORE);
        else result.set(DataComponents.LORE, new ItemLore(List.copyOf(lines)));
        return result;
    }

    private static WrittenBookContent written(ItemStack input) {
        requireBook(input, Items.WRITTEN_BOOK, "a signed book");
        WrittenBookContent content = input.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) throw invalid("The signed book has no written content.");
        return content;
    }

    private static String draftPage(Component page) {
        // Iterative traversal avoids overflowing the stack on imported component trees.
        ArrayDeque<Component> pending = new ArrayDeque<>();
        pending.push(page);
        StringBuilder text = new StringBuilder();
        int nodes = 0;
        while (!pending.isEmpty()) {
            Component current = pending.pop();
            if (++nodes > 4096) throw invalid("The page component tree is too complex to convert safely.");
            if (!current.getStyle().isEmpty() || !(current.getContents() instanceof PlainTextContents literal)) {
                throw invalid("Draft conversion requires plain, unstyled literal pages; formatting or dynamic content would be lost.");
            }
            text.append(literal.text());
            if (text.length() > PAGE_CHARS) throw invalid("A draft page exceeds 1024 characters.");
            List<Component> siblings = current.getSiblings();
            if (siblings.size() + pending.size() > 4096) throw invalid("The page component tree is too complex to convert safely.");
            for (int i = siblings.size() - 1; i >= 0; i--) pending.push(siblings.get(i));
        }
        return plain(text.toString(), "Page", PAGE_CHARS, true, true);
    }

    private static String plain(String text, String label, int maxChars, boolean allowEmpty, boolean multiline) {
        if (text == null) throw invalid(label + " is required.");
        if (!allowEmpty && text.isBlank()) throw invalid(label + " cannot be blank.");
        if (text.length() > maxChars) throw invalid(label + " exceeds " + maxChars + " UTF-16 characters.");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw invalid(label + " contains invalid Unicode.");
            } else if (Character.isLowSurrogate(c)) {
                throw invalid(label + " contains invalid Unicode.");
            } else if (c == '\u00a7' || (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t')))
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) {
                throw invalid(label + " contains unsupported control or formatting characters.");
            }
        }
        return text;
    }

    private static void requireItem(ItemStack input) {
        if (input == null || input.isEmpty()) throw invalid("Hold a nonempty item stack.");
    }

    private static void requireBook(ItemStack input, Item item, String description) {
        requireItem(input);
        if (input.getItem() != item) throw invalid("Hold " + description + ".");
        if (input.getCount() != 1) throw invalid("Hold exactly one book; split the stack first.");
    }

    private static void requireLimit(int limit, String name) {
        if (limit < 1) throw invalid(name + " must be positive.");
    }

    private static void requireLine(int line, int count) {
        if (line < 1 || line > count) throw invalid("Lore line must be between 1 and " + count + ".");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
