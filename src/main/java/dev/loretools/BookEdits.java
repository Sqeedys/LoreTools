package dev.loretools;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Tracks vanilla's asynchronous filtered save, without reading or replacing its content. */
public final class BookEdits {
    private static final ConcurrentMap<UUID, CompletableFuture<Void>> PENDING = new ConcurrentHashMap<>();
    private BookEdits() {}
    public static <T> CompletableFuture<Void> track(UUID player, CompletableFuture<T> filtered,
            Consumer<? super T> save, Executor server) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> previous = PENDING.put(player, done);
        CompletableFuture<Void> preceding = previous == null ? CompletableFuture.completedFuture(null) : previous;
        // Preserve arrival order even when a filtering service answers requests out of order.
        preceding.handle((v, e) -> null).thenCompose(v -> filtered).thenAcceptAsync(save, server)
            .whenComplete((v, error) -> {
                if (error == null) done.complete(null); else done.completeExceptionally(error);
                // Keep a failed save visible until a newer save succeeds; signing stale pages is unsafe.
                if (error == null) PENDING.remove(player, done);
            });
        return done;
    }
    public static CompletableFuture<Void> pending(UUID player) {
        return PENDING.getOrDefault(player, CompletableFuture.completedFuture(null));
    }
}
