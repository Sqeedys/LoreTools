package dev.loretools;

import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;

/** Isolated synthetic-player native tests. This is never packaged in the production jar. */
public final class LoreIntegration implements ModInitializer {
    private static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000001263");
    private final ArrayDeque<Step> steps=new ArrayDeque<>();
    private final List<String> passed=new ArrayList<>();
    private final List<String> feedback=new ArrayList<>();
    private MinecraftServer server;
    private LoreTools tools;
    private ServerPlayer player;
    private String phase;
    private Step current;
    private int age;
    private boolean done;
    private ItemStack original;
    private List<ItemStack> savedInventory;
    private String revision;
    private java.util.concurrent.CompletableFuture<?> externalUser;
    private java.util.concurrent.CompletableFuture<?> externalGroups;
    private Object externalApi;
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestions;
    private java.util.concurrent.CompletableFuture<List<net.minecraft.server.network.FilteredText>> delayedBundle;
    private net.minecraft.server.network.TextFilter fixtureFilter;
    private boolean delayNextBundle;
    private java.util.concurrent.CompletableFuture<?> paginationWrite;
    private LibraryMenu paginationMenu;
    private List<ItemStack> paginationInventory;
    private record Step(String name,ThrowingRunnable action,BooleanSupplier ready) {}
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    @Override public void onInitialize() {
        phase=System.getProperty("loretools.integration.phase","");
        if(!Set.of("initial","restart","external","external_restart").contains(phase))throw new IllegalStateException("Integration fixture requires explicit phase");
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(
                net.minecraft.commands.Commands.literal("library").executes(context->73)));
        ServerLifecycleEvents.SERVER_STARTED.register(s->{server=s;});
        ServerTickEvents.END_SERVER_TICK.register(s->{
            if(server==null||done)return;
            try {
                if(player==null){setup();return;}
                if(current==null){current=steps.poll();age=0;if(current==null){finish(null);return;}current.action().run();return;}
                if(current.ready().getAsBoolean()){passed.add(current.name());LoreTools.LOG.info("INTEGRATION PASS {}",current.name());current=null;}
                else if(++age>600)throw new AssertionError("Timed out: "+current.name());
            }catch(Throwable failure){finish(failure);}
        });
    }

    private void setup() throws Exception {
        tools=LoreTools.INSTANCE;
        if(tools==null||tools.library==null)throw new IllegalStateException("Lore Tools not initialized");
        var profile=new GameProfile(ID,"LoreFixture");
        var info=ClientInformation.createDefault();
        fixtureFilter=(net.minecraft.server.network.TextFilter)java.lang.reflect.Proxy.newProxyInstance(
                net.minecraft.server.network.TextFilter.class.getClassLoader(),new Class<?>[]{net.minecraft.server.network.TextFilter.class},
                (proxy,method,args)->{
                    if(method.getName().equals("processMessageBundle")&&delayNextBundle){delayNextBundle=false;return delayedBundle;}
                    return method.invoke(net.minecraft.server.network.TextFilter.DUMMY,args);
                });
        player=new ServerPlayer(server,server.overworld(),profile,info) {
            @Override public boolean hasDisconnected(){return false;}
            @Override public net.minecraft.server.network.TextFilter getTextFilter(){return fixtureFilter==null?net.minecraft.server.network.TextFilter.DUMMY:fixtureFilter;}
        };
        Connection connection=new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {capture(packet);}
            @Override public void send(Packet<?> packet,io.netty.channel.ChannelFutureListener listener) {capture(packet);}
            @Override public void send(Packet<?> packet,io.netty.channel.ChannelFutureListener listener,boolean flush) {capture(packet);}
        };
        player.connection=new ServerGamePacketListenerImpl(server,connection,player,
                CommonListenerCookie.createInitial(profile,false)) {
            @Override public boolean isAcceptingMessages(){return !LoreIntegration.this.done;}
            @Override public void send(Packet<?> packet) {capture(packet); /* No network client. */}
        };
        check(command("library")==73,"conflicting library root preserved");
        check(server.getCommands().getDispatcher().getRoot().getChild("title").getRedirect()==null,"vanilla title root preserved");
        passed.add("preexisting command collisions preserved");
        if(phase.startsWith("external")){external();return;}
        if(!tools.permissions.backend().equals("builtin"))throw new IllegalStateException("This fixture phase requires builtin permissions");
        if(phase.equals("initial")) {
            check(!tools.allowed(player.createCommandSourceStack(),"book.sign"),"unassigned player denied");
            try{command("lt sign Nobody Title");throw new AssertionError("Unauthorized command parsed");}
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){passed.add("unauthorized command denied");}
            step("unassigned command packet decodes",()->server.getCommands().sendCommands(player));
            step("unauthorized root completions loaded",()->requestSuggestions("lt "),()->suggestions.isDone());
            step("unauthorized features omitted from completions",()->{
                Set<String> names=new HashSet<>();suggestions.join().getList().forEach(s->names.add(s.getText()));
                check(Collections.disjoint(names,Set.of("sign","author","title","draft","name","lore","save","get","update","delete","library","list","read","reload","permissions")),"denied commands hidden from completion");
            });
            for(String role:tools.config.roles.keySet())step("grant role "+role,
                    ()->server.getCommands().getDispatcher().execute("lt permissions grant "+ID+" "+role,server.createCommandSourceStack()),
                    ()->{if(!hasRole(role))return false;server.getCommands().sendCommands(player);return true;});
            initial();
        }else restart();
    }

    private void initial() {
        List<String> latest=List.of("Latest\n\nJournal \ud83d\udcda","");
        step("authorized command packet decodes",()->server.getCommands().sendCommands(player));
        step("native edit packet starts delayed page save",()->{
            player.setItemInHand(InteractionHand.MAIN_HAND,draft());
            delayedBundle=new java.util.concurrent.CompletableFuture<>();delayNextBundle=true;
            player.connection.handleEditBook(new net.minecraft.network.protocol.game.ServerboundEditBookPacket(
                    player.getInventory().getSelectedSlot(),latest,Optional.empty()));
            check(!delayNextBundle&&!delayedBundle.isDone(),"native save waits on filter");
            command(alias("sign")+" PacketAuthor PacketTitle");
        });
        step("sign waits for pending native book save",()->{
            check(player.getMainHandItem().getItem()==Items.WRITABLE_BOOK,"pending save prevents premature signing");
            delayedBundle.complete(latest.stream().map(net.minecraft.server.network.FilteredText::passThrough).toList());
        },()->player.getMainHandItem().getItem()==Items.WRITTEN_BOOK);
        step("sign uses latest Unicode blank-line pages",()->{
            check(book().pages().size()==2,"latest saved page count");
            check(book().pages().getFirst().raw().getString().equals(latest.getFirst())&&book().pages().get(1).raw().getString().isEmpty(),"latest Unicode and blank pages preserved");
        });
        step("literal signing through alias",()->{
            player.setItemInHand(InteractionHand.MAIN_HAND,draft());
            command(alias("sign")+" \"Example Author\" \"The Journal\"");
        },()->player.getMainHandItem().getItem()==Items.WRITTEN_BOOK);
        step("signed fields and filters",()->{
            var b=book();check(b.author().equals("Example Author")&&b.title().raw().equals("The Journal"),"sign fields");
            check(b.pages().getFirst().raw().getString().equals("Raw journal"),"raw signing page");
            check(b.pages().getFirst().filtered().orElseThrow().getString().equals("Safe journal"),"filtered signing page");
            command("lt author Revised Author");
        },()->book().author().equals("Revised Author"));
        step("title command",()->command("lt title Updated Journal"),()->book().title().raw().equals("Updated Journal"));
        step("name command is separate",()->command(alias("name")+" Keepsake"),()->player.getMainHandItem().has(DataComponents.CUSTOM_NAME));
        step("lore command",()->{
            check(book().title().raw().equals("Updated Journal"),"cosmetic name does not change book title");
            command("lt lore add First line");
        },()->player.getMainHandItem().has(DataComponents.LORE));
        step("lore line replacement",()->command("lt lore set 1 Second line"),()->player.getMainHandItem().get(DataComponents.LORE).lines().getFirst().getString().equals("Second line"));
        step("draft preserves signed original",()->{original=player.getMainHandItem().copy();command(alias("draft"));},()->count(Items.WRITABLE_BOOK)==1);
        step("draft copy inventory safety",()->{
            check(ItemStack.matches(original,player.getMainHandItem()),"signed original unchanged");
            check(command("lt save integration_keep")==1,"save command accepted");
        },()->tools.library.find("integration_keep")!=null);
        step("duplicate IDs and path traversal denied",()->{
            revision=tools.library.find("integration_keep").revision();
            check(command("lt save integration_keep")==0,"duplicate save denied");
            boolean traversalRejected;
            try{traversalRejected=command("lt save ../escape")==0;}
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){traversalRejected=true;}
            check(traversalRejected,"traversal rejected");
            check(tools.library.find("integration_keep").revision().equals(revision),"duplicate leaves revision");
            check(command("lt get integration_keep")==1,"get succeeds");
            check(count(Items.WRITTEN_BOOK)==2,"get supplies one copy");
        });
        step("full inventory get and draft preserve items",()->{
            savedInventory=inventory();
            for(int i=0;i<36;i++)if(player.getInventory().getItem(i).isEmpty())player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
            original=player.getMainHandItem().copy();
            check(command("lt get integration_keep")==0,"full get denied");
            check(ItemStack.matches(original,player.getMainHandItem()),"full get preserves held");
            command("lt draft");
        });
        step("full draft does not replace original",()->{
            check(ItemStack.matches(original,player.getMainHandItem()),"full draft preserves held");
            check(count(Items.WRITABLE_BOOK)==1,"no extra draft on full inventory");
            restoreInventory(savedInventory);
            menuTests();
        });
        step("held-book changes invalidate confirmation",()->{
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemOperations.title(player.getMainHandItem(),"Candidate"));
            command("lt update integration_keep");String token=confirmation();
            check(tools.library.find("integration_keep").revision().equals(revision),"update awaits confirmation");
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemOperations.title(player.getMainHandItem(),"Final Journal"));
            check(command("lt confirm "+token)==0,"changed book confirmation rejected");
            check(tools.library.find("integration_keep").revision().equals(revision),"failed confirmation preserves entry");
            command("lt update integration_keep");check(command("lt confirm "+confirmation())==1,"valid update accepted");
        },()->!tools.library.find("integration_keep").revision().equals(revision));
        step("updates preserve existing copies",()->{
            check(tools.library.decode(tools.library.find("integration_keep")).get(DataComponents.WRITTEN_BOOK_CONTENT).title().raw().equals("Final Journal"),"updated template");
            check(player.getInventory().getItem(2).get(DataComponents.WRITTEN_BOOK_CONTENT).title().raw().equals("Updated Journal"),"existing retrieved copy unchanged");
            command("lt save integration_delete");
        },()->tools.library.find("integration_delete")!=null);
        step("delete confirmation",()->{
            command("lt delete integration_delete");check(tools.library.find("integration_delete")!=null,"delete awaits confirmation");
            check(command("lt confirm wrong-token")==0,"wrong token denied");
            check(command("lt confirm "+confirmation())==1,"delete confirmed");
        },()->tools.library.find("integration_delete")==null);
        step("malformed primary backup recovery preserves evidence",()->{
            Path directory=FabricLoader.getInstance().getGameDir().resolve("local/integration-recovery-"+UUID.randomUUID());
            Files.createDirectories(directory);
            var envelope=new com.google.gson.JsonObject();envelope.addProperty("schema",1);envelope.addProperty("id","recover");
            envelope.addProperty("revision",UUID.randomUUID().toString());envelope.addProperty("deleted",false);
            envelope.add("item",tools.library.encode(player.getMainHandItem()));
            Files.writeString(directory.resolve("recover.json.bak"),envelope.toString());
            envelope.addProperty("deleted","true");
            byte[] malformed=envelope.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(directory.resolve("recover.json"),malformed);
            try(BookLibrary recovered=new BookLibrary(directory,server)){
                var entry=recovered.find("recover");check(entry!=null,"valid backup becomes readable");
                check(Arrays.equals(malformed,Files.readAllBytes(directory.resolve("recover.json"))),"malformed primary unchanged");
                try{recovered.write("recover",entry.revision(),tools.library.encode(player.getMainHandItem()));throw new AssertionError("Recovered entry unexpectedly writable");}
                catch(IllegalArgumentException expected){check(expected.getMessage().contains("offline repair"),"recovered entry requires explicit offline repair");}
            }
        });
        step("pagination templates saved",()->{
            List<java.util.concurrent.CompletableFuture<?>> writes=new ArrayList<>();
            for(int i=0;i<46;i++)writes.add(tools.library.write(String.format(Locale.ROOT,"page_%02d",i),null,tools.library.encode(player.getMainHandItem())));
            paginationWrite=java.util.concurrent.CompletableFuture.allOf(writes.toArray(java.util.concurrent.CompletableFuture[]::new));
        },()->paginationWrite.isDone());
        step("GUI next and back use stable sorted results",()->{
            paginationWrite.join();paginationInventory=inventory();
            LibraryMenu.open(tools,player,"page_",0);paginationMenu=(LibraryMenu)player.containerMenu;
            check(menuHasId(paginationMenu,0,"page_00")&&menuHasId(paginationMenu,44,"page_44"),"first page stable ID order");
            paginationMenu.clicked(53,0,ContainerInput.PICKUP,player);
            check(menuHasId(paginationMenu,0,"page_45"),"next results page");
            paginationMenu.clicked(45,0,ContainerInput.PICKUP,player);
            check(menuHasId(paginationMenu,0,"page_00"),"previous results page");
            check(ItemStack.listMatches(paginationInventory,inventory()),"pagination creates no items");
            paginationMenu.clicked(0,0,ContainerInput.PICKUP,player);
            var entry=tools.library.find("page_00");
            paginationWrite=tools.library.write("page_00",entry.revision(),tools.library.encode(ItemOperations.title(player.getMainHandItem(),"Changed Page")));
        },()->paginationWrite.isDone());
        step("stale GUI detail cannot retrieve changed template",()->{
            paginationWrite.join();int beforeFeedback=feedback.size();paginationMenu.clicked(49,0,ContainerInput.PICKUP,player);
            check(ItemStack.listMatches(paginationInventory,inventory()),"stale Get Copy creates no item");
            check(feedback.subList(beforeFeedback,feedback.size()).stream().anyMatch(s->s.contains("Template changed")),"stale menu reports refresh requirement");
            player.closeContainer();
        });
    }

    private void restart() {
        step("reconnected command packet decodes",()->server.getCommands().sendCommands(player));
        step("roles survive server restart",()->check(tools.allowed(player.createCommandSourceStack(),"library.get"),"persisted role allows get"));
        step("library save update delete survive restart",()->{
            var entry=tools.library.find("integration_keep");check(entry!=null,"saved template loaded");
            check(tools.library.find("integration_delete")==null,"deleted template remains deleted");
            var book=tools.library.decode(entry).get(DataComponents.WRITTEN_BOOK_CONTENT);
            check(book.title().raw().equals("Final Journal")&&book.author().equals("Revised Author"),"updated metadata persisted");
            check(book.pages().getFirst().filtered().orElseThrow().getString().equals("Safe journal"),"filter metadata persisted");
            command("lt get integration_keep");check(count(Items.WRITTEN_BOOK)==1,"get after restart");
            menuTests();
        });
        for(String role:tools.config.roles.keySet())step("revoke role "+role,
                ()->server.getCommands().getDispatcher().execute("lt permissions revoke "+ID+" "+role,server.createCommandSourceStack()),
                ()->{if(hasRole(role))return false;server.getCommands().sendCommands(player);return true;});
        step("revoked player denied",()->check(!tools.allowed(player.createCommandSourceStack(),"library.get"),"revocation effective"));
        step("revoked template completions loaded",()->requestSuggestions("lt get integration_"),()->suggestions.isDone());
        step("revoked template IDs omitted from completions",()->check(suggestions.join().getList().stream().noneMatch(s->s.getText().contains("integration_keep")),"existing template IDs not leaked"));
    }

    private void external() {
        check(tools.permissions.backend().equals("external"),"external backend selected");
        step("real LuckPerms user loaded",()->{
            Class<?> provider=Class.forName("net.luckperms.api.LuckPermsProvider");
            externalApi=provider.getMethod("get").invoke(null);
            Object manager=Class.forName("net.luckperms.api.LuckPerms").getMethod("getUserManager").invoke(externalApi);
            externalUser=(java.util.concurrent.CompletableFuture<?>)Class.forName("net.luckperms.api.model.user.UserManager")
                    .getMethod("loadUser",UUID.class).invoke(manager,ID);
        },()->externalUser!=null&&externalUser.isDone());
        step("LuckPerms console-created groups loaded",()->{
            Object manager=Class.forName("net.luckperms.api.LuckPerms").getMethod("getGroupManager").invoke(externalApi);
            externalGroups=(java.util.concurrent.CompletableFuture<?>)Class.forName("net.luckperms.api.model.group.GroupManager")
                    .getMethod("loadAllGroups").invoke(manager);
        },()->externalGroups!=null&&externalGroups.isDone());
        step("LuckPerms role commands persisted expected nodes",()->{
            externalGroups.join();
            Object manager=Class.forName("net.luckperms.api.LuckPerms").getMethod("getGroupManager").invoke(externalApi);
            Map<String,Set<String>> expected=Map.of(
                    "loreauthor",Set.of("loretools.book.*","loretools.item.*","loretools.library.browse","loretools.library.get","loretools.library.save"),
                    "lorebuilder",Set.of("loretools.library.browse","loretools.library.get"),
                    "loreadmin",Set.copyOf(LoreConfig.NODES));
            for(var entry:expected.entrySet()){
                Object group=Class.forName("net.luckperms.api.model.group.GroupManager").getMethod("getGroup",String.class).invoke(manager,entry.getKey());
                check(group!=null,"LP group exists: "+entry.getKey());
                Collection<?> nodes=(Collection<?>)Class.forName("net.luckperms.api.model.group.Group").getMethod("getNodes").invoke(group);
                Set<String> granted=new HashSet<>();
                for(Object node:nodes)if((boolean)Class.forName("net.luckperms.api.node.Node").getMethod("getValue").invoke(node))
                    granted.add((String)Class.forName("net.luckperms.api.node.Node").getMethod("getKey").invoke(node));
                check(granted.equals(entry.getValue()),"LP exact role nodes persisted: "+entry.getKey());
            }
        });
        if(phase.equals("external_restart")){
            step("LuckPerms grants persist after restart",()->{
                check(externalState("loretools.item.name").equals("TRUE"),"LP saved grant reloaded");
                check(tools.allowed(player.createCommandSourceStack(),"item.name"),"LP reloaded grant effective");
                check(externalState("loretools.book.sign").equals("UNDEFINED"),"LP revoke persisted");
                check(!externalAllowed("book.sign",true),"OP does not bypass persisted undefined");
                server.getCommands().sendCommands(player);
            });return;
        }
        step("reset synthetic LuckPerms parents",()->console("lp user "+ID+" parent set default"),
                ()->externalState("loretools.library.get").equals("UNDEFINED"));
        step("reset synthetic LuckPerms sign node",()->console("lp user "+ID+" permission unset loretools.book.sign"),
                ()->externalState("loretools.book.sign").equals("UNDEFINED"));
        step("reset synthetic LuckPerms name node",()->console("lp user "+ID+" permission unset loretools.item.name"),
                ()->externalState("loretools.item.name").equals("UNDEFINED"));
        step("reset synthetic LuckPerms delete node",()->console("lp user "+ID+" permission unset loretools.library.delete"),
                ()->externalState("loretools.library.delete").equals("UNDEFINED"));
        step("LuckPerms undefined denies player and OP",()->{
            check(externalState("loretools.book.sign").equals("UNDEFINED"),"fresh LP node undefined");
            check(!externalAllowed("book.sign",false)&&!externalAllowed("book.sign",true),"LP undefined denies both sources");
            console("lp user "+ID+" permission set loretools.book.sign true");
        },()->externalState("loretools.book.sign").equals("TRUE"));
        step("LuckPerms explicit true authorizes player and OP",()->{
            check(externalAllowed("book.sign",false)&&externalAllowed("book.sign",true),"LP explicit true honored");
            console("lp user "+ID+" permission set loretools.book.sign false");
        },()->externalState("loretools.book.sign").equals("FALSE"));
        step("LuckPerms explicit deny overrides OP",()->{
            check(!externalAllowed("book.sign",false)&&!externalAllowed("book.sign",true),"LP explicit false denies both sources");
            console("lp user "+ID+" permission unset loretools.book.sign");
        },()->externalState("loretools.book.sign").equals("UNDEFINED"));
        step("LuckPerms revoke remains denied",()->{
            check(!externalAllowed("book.sign",false)&&!externalAllowed("book.sign",true),"LP revoked grant denied");
        });
        step("documented author parent assigned",()->console("lp user "+ID+" parent add loreauthor"),
                ()->externalState("loretools.book.sign").equals("TRUE"));
        step("documented author permissions and revoke",()->{
            check(externalAllowed("book.sign",false)&&externalAllowed("item.name",false)&&externalAllowed("library.save",false),"author can edit and save");
            check(!externalAllowed("library.update",false)&&!externalAllowed("library.delete",false),"author cannot replace or delete");
            console("lp user "+ID+" parent remove loreauthor");
        },()->externalState("loretools.book.sign").equals("UNDEFINED"));
        step("documented author revocation effective",()->{
            check(!externalAllowed("book.sign",false)&&!externalAllowed("item.name",false),"author role removed");
            console("lp user "+ID+" parent add lorebuilder");
        },()->externalState("loretools.library.get").equals("TRUE"));
        step("documented builder scope",()->{
            check(externalAllowed("library.browse",false)&&externalAllowed("library.get",false),"builder can browse and get");
            check(!externalAllowed("book.sign",false)&&!externalAllowed("item.name",false)&&!externalAllowed("library.save",false)&&!externalAllowed("library.delete",false),"builder has no editing or template mutations");
            console("lp user "+ID+" parent remove lorebuilder");
        },()->externalState("loretools.library.get").equals("UNDEFINED"));
        step("documented admin parent assigned",()->console("lp user "+ID+" parent add loreadmin"),
                ()->externalState("loretools.library.delete").equals("TRUE"));
        step("documented fixed-node administrator scope",()->{
            check(externalAllowed("library.delete",false),"non-OP admin granted");
            server.getCommands().sendCommands(player);
            for(String node:LoreConfig.NODES)check(tools.permissions.allowed(player.createCommandSourceStack(),node),"admin fixed node "+node);
            check(command("lt permissions grant "+ID+" author")==0,"external mode rejects builtin role management");
            console("lp user "+ID+" permission set loretools.library.delete false");
        },()->externalState("loretools.library.delete").equals("FALSE"));
        step("documented sensitive-action deny overrides inheritance and OP",()->{
            check(!externalAllowed("library.delete",false)&&!externalAllowed("library.delete",true),"explicit delete deny beats inherited admin and OP");
            check(externalAllowed("library.update",true),"other inherited admin powers remain");
            console("lp user "+ID+" permission unset loretools.library.delete");
        },()->externalState("loretools.library.delete").equals("TRUE"));
        step("removing deny restores inherited administrator permission",()->{
            check(externalAllowed("library.delete",false),"delete restored from admin parent");
            console("lp user "+ID+" parent remove loreadmin");
        },()->externalState("loretools.library.delete").equals("UNDEFINED"));
        step("save independent name grant for restart",()->{
            check(!externalAllowed("book.sign",false),"all authoring parents removed");
            console("lp user "+ID+" permission set loretools.item.name true");
        },()->externalState("loretools.item.name").equals("TRUE"));
        step("LuckPerms permission governs actual command",()->{
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE,13));
            command("lt name Permission Fixture");
        },()->player.getMainHandItem().has(DataComponents.CUSTOM_NAME));
        step("LuckPerms command preserves count",()->check(player.getMainHandItem().getCount()==13,"external-authorized transform count"));
    }

    private boolean externalAllowed(String suffix,boolean owner) {
        try {
            boolean actual=player.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER);
            if(actual!=owner){
                var method=Arrays.stream(server.getPlayerList().getClass().getMethods())
                        .filter(m->m.getName().equals(owner?"op":"deop")&&m.getParameterCount()==1).findFirst().orElseThrow();
                Class<?> parameter=method.getParameterTypes()[0];
                Object identity;
                if(parameter==GameProfile.class)identity=new GameProfile(ID,"LoreFixture");
                else {
                    try{identity=parameter.getConstructor(GameProfile.class).newInstance(new GameProfile(ID,"LoreFixture"));}
                    catch(NoSuchMethodException ignored){identity=parameter.getConstructor(UUID.class,String.class).newInstance(ID,"LoreFixture");}
                }
                method.invoke(server.getPlayerList(),identity);
            }
            check(player.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER)==owner,"fixture actual operator state");
            return tools.allowed(player.createCommandSourceStack(),suffix);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private String externalState(String node) {
        try {
            Object user=externalUser.join();
            Object cached=Class.forName("net.luckperms.api.model.user.User").getMethod("getCachedData").invoke(user);
            Object permission=Class.forName("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getPermissionData").invoke(cached);
            return Class.forName("net.luckperms.api.cacheddata.CachedPermissionData").getMethod("checkPermission",String.class).invoke(permission,node).toString();
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private int console(String command) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return server.getCommands().getDispatcher().execute(command,server.createCommandSourceStack());
    }

    private void menuTests() {
        var oldMode=player.gameMode();player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        LibraryMenu.open(tools,player,"integration_keep",0);
        check(player.containerMenu instanceof LibraryMenu,"native library menu opens");
        LibraryMenu menu=(LibraryMenu)player.containerMenu;
        List<ItemStack> before=inventory();ItemStack cursor=menu.getCarried().copy();
        for(ContainerInput action:ContainerInput.values())if(action!=ContainerInput.PICKUP){
            for(int slot:new int[]{-999,0,49,54})menu.clicked(slot,0,action,player);
            check(ItemStack.listMatches(before,inventory())&&ItemStack.matches(cursor,menu.getCarried()),"menu inert for "+action);
        }
        menu.clicked(0,1,ContainerInput.PICKUP,player);menu.clicked(54,0,ContainerInput.PICKUP,player);
        check(ItemStack.listMatches(before,inventory()),"right and inventory clicks inert");
        check(menu.quickMoveStack(player,0).isEmpty()&&!menu.canDragTo(menu.slots.getFirst())&&!menu.canTakeItemForPickAll(new ItemStack(Items.BOOK),menu.slots.getFirst()),"menu transfer hooks inert");
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36,new ItemStack(Items.DIAMOND,64)));
        check(ItemStack.listMatches(before,inventory()),"native creative inventory packet blocked while menu open");
        menu.clicked(0,0,ContainerInput.PICKUP,player);
        check(ItemStack.listMatches(before,inventory()),"preview supplies no item");
        int copies=count(Items.WRITTEN_BOOK);menu.clicked(49,0,ContainerInput.PICKUP,player);
        check(count(Items.WRITTEN_BOOK)==copies+1,"explicit Get Copy gives exactly one");
        player.closeContainer();restoreInventory(before);player.setGameMode(oldMode);
    }
    private boolean menuHasId(LibraryMenu menu,int slot,String id){var lore=menu.slots.get(slot).getItem().get(DataComponents.LORE);return lore!=null&&lore.lines().stream().anyMatch(line->line.getString().equals("ID: "+id));}
    private void capture(Packet<?> packet){
        if(packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat)feedback.add(chat.content().getString());
        if(packet instanceof net.minecraft.network.protocol.game.ClientboundCommandsPacket commands){
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                net.minecraft.network.protocol.game.ClientboundCommandsPacket.STREAM_CODEC.encode(buffer,commands);
                net.minecraft.network.protocol.game.ClientboundCommandsPacket.STREAM_CODEC.decode(buffer);
                check(!buffer.isReadable(),"command packet fully decoded");
            } finally {buffer.release();}
        }
    }

    private String confirmation() throws Exception {
        var field=LoreTools.class.getDeclaredField("confirmations");field.setAccessible(true);
        Object pending=((Map<?,?>)field.get(tools)).get(ID);check(pending!=null,"pending confirmation exists");
        var token=pending.getClass().getDeclaredMethod("token");token.setAccessible(true);return (String)token.invoke(pending);
    }
    private boolean hasRole(String role) {
        try {
            var field=LorePermissions.class.getDeclaredField("assignments");field.setAccessible(true);
            Object assigned=((Map<?,?>)field.get(tools.permissions)).get(ID);
            return assigned instanceof Set<?> roles&&roles.contains(role);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private String alias(String name){return tools.config.aliases.getOrDefault(name,"lt "+name);}
    private void requestSuggestions(String input){var dispatcher=server.getCommands().getDispatcher();suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse(input,player.createCommandSourceStack()));}
    private int command(String command) throws com.mojang.brigadier.exceptions.CommandSyntaxException{return server.getCommands().getDispatcher().execute(command,player.createCommandSourceStack());}
    private WrittenBookContent book(){return player.getMainHandItem().get(DataComponents.WRITTEN_BOOK_CONTENT);}
    private ItemStack draft(){ItemStack s=new ItemStack(Items.WRITABLE_BOOK);s.set(DataComponents.WRITABLE_BOOK_CONTENT,new WritableBookContent(List.of(new Filterable<>("Raw journal",Optional.of("Safe journal")))));return s;}
    private List<ItemStack> inventory(){List<ItemStack> result=new ArrayList<>();for(int i=0;i<player.getInventory().getContainerSize();i++)result.add(player.getInventory().getItem(i).copy());return result;}
    private void restoreInventory(List<ItemStack> values){for(int i=0;i<values.size();i++)player.getInventory().setItem(i,values.get(i).copy());}
    private int count(Item item){int result=0;for(ItemStack s:inventory())if(s.getItem()==item)result+=s.getCount();return result;}
    private void step(String name,ThrowingRunnable action){step(name,action,()->true);}
    private void step(String name,ThrowingRunnable action,BooleanSupplier ready){steps.add(new Step(name,action,ready));}
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
    private void finish(Throwable failure) {
        done=true;
        try {
            Path receipt=FabricLoader.getInstance().getGameDir().resolve("local/loretools-integration-"+phase+".txt");Files.createDirectories(receipt.getParent());
            String result=(failure==null?"PASS":"FAIL")+" phase="+phase+" steps="+passed.size()+"\n"+String.join("\n",passed)+"\n";
            if(failure!=null){result+=failure+"\nFeedback:\n"+String.join("\n",feedback.subList(Math.max(0,feedback.size()-15),feedback.size()))+"\n";LoreTools.LOG.error("INTEGRATION FAILED at "+(current==null?"setup":current.name()),failure);}
            Files.writeString(receipt,result);LoreTools.LOG.info("INTEGRATION {} phase={} steps={}",failure==null?"PASS":"FAIL",phase,passed.size());
        }catch(Exception receiptError){LoreTools.LOG.error("Integration receipt failed",receiptError);}
        server.halt(false);
    }
}
