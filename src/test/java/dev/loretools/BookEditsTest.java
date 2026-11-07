package dev.loretools;

import java.util.*;
import java.util.concurrent.*;

public final class BookEditsTest {
    private static int checks;
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    public static void main(String[] args) {
        UUID id=UUID.randomUUID();List<String> saved=new ArrayList<>();Queue<Runnable> tasks=new ArrayDeque<>();
        CompletableFuture<String> first=new CompletableFuture<>(),second=new CompletableFuture<>();
        var a=BookEdits.track(id,first,saved::add,tasks::add);
        var b=BookEdits.track(id,second,saved::add,tasks::add);
        check(!BookEdits.pending(id).isDone());second.complete("latest\n\n航海 🧭");check(tasks.isEmpty());
        first.complete("previous");check(!BookEdits.pending(id).isDone());check(tasks.size()==1);
        tasks.remove().run();check(a.isDone());check(!b.isDone());check(saved.equals(List.of("previous")));
        tasks.remove().run();check(b.isDone());check(saved.equals(List.of("previous","latest\n\n航海 🧭")));check(BookEdits.pending(id).isDone());
        var failure=BookEdits.track(id,CompletableFuture.<String>failedFuture(new IllegalStateException("filter unavailable")),saved::add,tasks::add);
        check(failure.isCompletedExceptionally());check(BookEdits.pending(id).isCompletedExceptionally());check(saved.size()==2);
        check(String.join("",LibraryMenu.previewParts("\n".repeat(2000)+"航海 🧭")).equals("\n".repeat(2000)+"航海 🧭"));
        for(String part:LibraryMenu.previewParts("\n".repeat(2000)))check(part.split("\n",-1).length<=161);
        for(String part:LibraryMenu.split("🧭".repeat(100),65))check(!Character.isHighSurrogate(part.charAt(part.length()-1)));
        System.out.println("BookEditsTest: "+checks+" checks passed");
    }
}
