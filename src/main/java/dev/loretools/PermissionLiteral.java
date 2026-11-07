package dev.loretools;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.*;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import java.util.concurrent.CompletableFuture;

/** Brigadier completion does not itself apply requirements to every suggested child. */
final class PermissionLiteral extends LiteralCommandNode<CommandSourceStack> {
    PermissionLiteral(LiteralCommandNode<CommandSourceStack> original) {
        super(original.getLiteral(),original.getCommand(),original.getRequirement(),original.getRedirect(),original.getRedirectModifier(),original.isFork());
        original.getChildren().forEach(this::addChild);
    }
    @Override public CompletableFuture<Suggestions> listSuggestions(CommandContext<CommandSourceStack> context,SuggestionsBuilder builder) {
        return canUse(context.getSource())?super.listSuggestions(context,builder):Suggestions.empty();
    }
}
