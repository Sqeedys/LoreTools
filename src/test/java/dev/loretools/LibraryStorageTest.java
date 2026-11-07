package dev.loretools;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class LibraryStorageTest {
    private static int checks;
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    public static void main(String[] args) throws Exception {
        var envelope=com.google.gson.JsonParser.parseString("{\"schema\":1,\"id\":\"fixture\",\"revision\":\"00000000-0000-4000-8000-000000000001\",\"deleted\":true}").getAsJsonObject();
        BookLibrary.validateEnvelope(envelope,"fixture");checks++;
        for(String malformed:new String[]{"\"1\"","1.5","1.0","null","true"}){
            var broken=envelope.deepCopy();broken.add("schema",com.google.gson.JsonParser.parseString(malformed));
            boolean rejected=false;try{BookLibrary.validateEnvelope(broken,"fixture");}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        }
        for(String malformed:new String[]{"\"true\"","\"false\"","null","1"}){
            var broken=envelope.deepCopy();broken.add("deleted",com.google.gson.JsonParser.parseString(malformed));
            boolean rejected=false;try{BookLibrary.validateEnvelope(broken,"fixture");}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        }
        var extra=envelope.deepCopy();extra.addProperty("unknown",true);
        boolean extraRejected=false;try{BookLibrary.validateEnvelope(extra,"fixture");}catch(IllegalArgumentException e){extraRejected=true;}check(extraRejected);
        for(String id:new String[]{"sample_letter","a","0","a".repeat(64)}){BookLibrary.validateId(id);checks++;}
        for(String id:new String[]{"../a","a/b","a\\b","A","_hidden","","a".repeat(65),"nul","com1","a:b","a.json","é"}){
            boolean rejected=false;try{BookLibrary.validateId(id);}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        }
        Path dir=Files.createTempDirectory("loretools-storage-test-");Path file=dir.resolve("fixture.json");
        try {
            BookLibrary.safeWrite(file,"first".getBytes(StandardCharsets.UTF_8));check(Files.readString(file).equals("first"));
            BookLibrary.safeWrite(file,"second 航海".getBytes(StandardCharsets.UTF_8));check(Files.readString(file).equals("second 航海"));
            check(Files.readString(dir.resolve("fixture.json.bak")).equals("first"));check(!Files.exists(dir.resolve("fixture.json.tmp")));
            Files.writeString(dir.resolve("fixture.json.tmp"),"interrupted");check(Files.readString(file).equals("second 航海"));
            BookLibrary.safeWrite(file,"third".getBytes(StandardCharsets.UTF_8));check(Files.readString(file).equals("third"));check(Files.readString(dir.resolve("fixture.json.bak")).equals("second 航海"));
        }finally{try(var files=Files.list(dir)){for(Path p:files.toList())Files.delete(p);}Files.delete(dir);}
        System.out.println("LibraryStorageTest: "+checks+" checks passed");
    }
}
