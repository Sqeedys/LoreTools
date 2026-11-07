package dev.loretools.mixin;

import dev.loretools.BookEdits;
import dev.loretools.LibraryMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BookSaveMixin {
    @Shadow public ServerPlayer player;
    @Redirect(method = "handleEditBook", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private <T> CompletableFuture<Void> loretools$waitForSave(CompletableFuture<T> future, Consumer<? super T> save, Executor executor) {
        return BookEdits.track(player.getUUID(), future, save, executor);
    }
    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER), cancellable = true)
    private void loretools$protectMenu(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (player.containerMenu instanceof LibraryMenu) {
            player.inventoryMenu.broadcastFullState();
            player.containerMenu.broadcastFullState();
            ci.cancel();
        }
    }
}
