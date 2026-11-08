package dev.loretools;

import com.google.gson.JsonElement;
import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.slf4j.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import static net.minecraft.commands.Commands.*;
import static com.mojang.brigadier.arguments.StringArgumentType.*;

public final class LoreTools implements ModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("loretools");
    public static LoreTools INSTANCE;
    public LoreConfig config;
    public LorePermissions permissions;
    public BookLibrary library;
    private Path configFile;
    private MinecraftServer server;
    private CommandDispatcher<CommandSourceStack> aliasedDispatcher;
    private final Map<String,String> shortcuts=new HashMap<>();
    private final Map<UUID,Pending> confirmations=new HashMap<>();
    private final Map<UUID,Set<String>> sentPermissions=new HashMap<>();
    private final ExecutorService configReader=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("LoreTools-config").factory());
    private int ticks;
    private record Pending(String token,String action,BookLibrary.Entry previous,JsonElement replacement,long expires) {}
    private record Help(String category,String command,String node,String arguments,String description,String example) {}
    private static final List<Help> HELP=List.of(
        new Help("Books","sign","book.sign","<author> [title]","Sign the saved main-hand draft","sign \"Example Author\" \"The Field Journal\""),
        new Help("Books","author","book.author","<author>","Correct a signed book's author","author Example Author"),
        new Help("Books","title","book.title","<title>","Correct the actual book title","title The Field Journal"),
        new Help("Books","draft","book.draft","","Make a separate editable copy","draft"),
        new Help("Library","save","library.save","<id>","Save a new template","save field_journal"),
        new Help("Library","get","library.get","<id>","Get an independent copy","get field_journal"),
        new Help("Library","library","library.browse","[search]","Browse and preview the book library","library journal"),
        new Help("Library","list","library.browse","[search]","List searchable templates in chat","list journal"),
        new Help("Library","update","library.update","<id>","Confirm replacing a template from your held book","update field_journal"),
        new Help("Library","delete","library.delete","<id>","Confirm removing a template","delete field_journal"),
        new Help("Items","name","item.name","<text>","Set cosmetic item name; book title is separate","name Navigator's Compass"),
        new Help("Items","resetname","item.name","","Remove only the custom name","resetname"),
        new Help("Items","lore","item.lore","add|set|remove|clear","Edit cosmetic lore; line numbers start at 1","lore set 2 The needle no longer points north."),
        new Help("Administration","reload","admin.reload","","Validate and reload configuration","reload"),
        new Help("Administration","permissions","admin.permissions","grant|revoke|show <player-or-uuid> [role]","Manage built-in UUID roles","permissions grant ExamplePlayer author")
    );
    @Override public void onInitialize() {
        INSTANCE=this;
        configFile=FabricLoader.getInstance().getConfigDir().resolve("loretools.json");
        config=LoreConfig.load(configFile);
        permissions=new LorePermissions(FabricLoader.getInstance().getGameDir().resolve("local/loretools"),config);
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(s->{
            server=s;
            try {library=new BookLibrary(FabricLoader.getInstance().getGameDir().resolve("local/loretools/books"),s);}
            catch(Exception e){throw new IllegalStateException("Lore Tools library initialization failed; data preserved",e);}
            aliases(s.getCommands().getDispatcher());
            LOG.info("Lore Tools ready; permission backend={}; GUI={}",permissions.backend(),config.guiEnabled);
        });
        ServerTickEvents.END_SERVER_TICK.register(s->{
            if(aliasedDispatcher!=s.getCommands().getDispatcher()) aliases(s.getCommands().getDispatcher());
            if(++ticks%20==0) {
                confirmations.entrySet().removeIf(e->e.getValue().expires()<System.nanoTime());
                // Refresh only when access changes, including LuckPerms grants/revocations.
                Set<UUID> online=new HashSet<>();
                for(ServerPlayer p:s.getPlayerList().getPlayers()) {
                    online.add(p.getUUID()); Set<String> nodes=new HashSet<>();
                    for(String n:LoreConfig.NODES) if(permissions.allowed(p.createCommandSourceStack(),n)) nodes.add(n);
                    if(!nodes.equals(sentPermissions.put(p.getUUID(),nodes))) s.getCommands().sendCommands(p);
                }
                sentPermissions.keySet().retainAll(online); confirmations.keySet().retainAll(online);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{if(library!=null)library.close();permissions.close();configReader.shutdown();});
    }
    public boolean allowed(CommandSourceStack s,String suffix){return permissions.allowed(s,"loretools."+suffix);}
    public void require(CommandSourceStack s,String suffix){if(!allowed(s,suffix))throw new IllegalArgumentException("Permission denied.");}
    public static void tell(CommandSourceStack s,String text){s.sendSuccess(()->Component.literal(text),false);}
    public static void fail(CommandSourceStack s,String text){s.sendFailure(Component.literal(text));}
    public static void audit(CommandSourceStack s,String action,String id){LOG.info("actor={} action={} target={}",s.getPlayer()==null?"console":s.getPlayer().getUUID(),action,id);}
    @FunctionalInterface private interface Action {int run() throws Exception;}
    private int safe(CommandSourceStack s,Action action){try{return action.run();}catch(IllegalArgumentException e){fail(s,e.getMessage());return 0;}catch(Exception e){LOG.error("Lore Tools operation failed",e);fail(s,"Operation failed; see the server log. No success was confirmed.");return 0;}}
    private LiteralArgumentBuilder<CommandSourceStack> command(String name,String node){return literal(name).requires(s->allowed(s,node));}
    private void register(CommandDispatcher<CommandSourceStack> d) {
        if(d.getRoot().getChild("lt")!=null)throw new IllegalStateException("/lt is already registered; Lore Tools refuses to merge command trees.");
        var root=literal("lt").executes(c->help(c.getSource(),null));
        root.then(literal("help").executes(c->help(c.getSource(),null)).then(argument("command",word()).suggests((c,b)->{
            for(Help h:HELP)if(allowed(c.getSource(),h.node())&&h.command().startsWith(b.getRemaining()))b.suggest(h.command());return b.buildFuture();
        }).executes(c->help(c.getSource(),getString(c,"command")))));
        root.then(command("sign","book.sign").then(argument("author",string()).executes(c->sign(c,getString(c,"author"),config.defaultTitle))
            .then(argument("title",string()).executes(c->sign(c,getString(c,"author"),getString(c,"title"))))));
        for(String field:List.of("author","title")) root.then(command(field,"book."+field).then(argument("text",greedyString()).executes(c->safe(c.getSource(),()->{
            String text=unquote(getString(c,"text"));return transform(c.getSource(),"book."+field,field,List.of(text),s->field.equals("author")?ItemOperations.author(s,text):ItemOperations.title(s,text),false);
        }))));
        root.then(command("draft","book.draft").executes(c->safe(c.getSource(),()->transform(c.getSource(),"book.draft","draft",List.of(),ItemOperations::draft,true))));
        root.then(command("name","item.name").then(argument("text",greedyString()).executes(c->safe(c.getSource(),()->{
            String text=getString(c,"text");return transform(c.getSource(),"item.name","name",List.of(text),s->ItemOperations.name(s,text,config.maxNameChars),false);
        }))));
        root.then(command("resetname","item.name").executes(c->safe(c.getSource(),()->transform(c.getSource(),"item.name","resetname",List.of(),ItemOperations::resetName,false))));
        var lore=command("lore","item.lore");
        lore.then(literal("add").then(argument("text",greedyString()).executes(c->lore(c,"add",0,getString(c,"text")))));
        lore.then(literal("set").then(argument("line",IntegerArgumentType.integer(1)).then(argument("text",greedyString()).executes(c->lore(c,"set",IntegerArgumentType.getInteger(c,"line"),getString(c,"text"))))));
        lore.then(literal("remove").then(argument("line",IntegerArgumentType.integer(1)).executes(c->lore(c,"remove",IntegerArgumentType.getInteger(c,"line"),""))));
        lore.then(literal("clear").executes(c->lore(c,"clear",0,"")));root.then(lore);
        for(String action:List.of("save","get","update","delete"))root.then(command(action,"library."+action).then(argument("id",word()).suggests((c,b)->{
            if(library!=null&&allowed(c.getSource(),"library.browse")&&allowed(c.getSource(),"library."+action)) for(var e:library.search(b.getRemaining(),filtered(c.getSource())))if(e.id().startsWith(b.getRemaining()))b.suggest(e.id());return b.buildFuture();
        }).executes(c->safe(c.getSource(),()->libraryAction(c.getSource(),action,getString(c,"id"))))));
        for(String action:List.of("library","list"))root.then(command(action,"library.browse").executes(c->browse(c.getSource(),action,"",0))
            .then(argument("search",greedyString()).executes(c->browse(c.getSource(),action,getString(c,"search"),0))));
        root.then(command("read","library.browse").then(argument("id",word()).then(argument("page",IntegerArgumentType.integer(1)).executes(c->safe(c.getSource(),()->read(c.getSource(),getString(c,"id"),IntegerArgumentType.getInteger(c,"page")))))));
        root.then(literal("confirm").requires(s->allowed(s,"library.update")||allowed(s,"library.delete")).then(argument("token",word()).executes(c->safe(c.getSource(),()->confirm(c.getSource(),getString(c,"token"))))));
        root.then(command("reload","admin.reload").executes(c->safe(c.getSource(),()->reload(c.getSource()))));
        var perms=command("permissions","admin.permissions").executes(c->{tell(c.getSource(),"Active backend: "+permissions.backend()+". /lt permissions grant|revoke|show <player-or-uuid> [role]");return 1;});
        for(String action:List.of("grant","revoke","show")) {
            var target=argument("player-or-uuid",word()).suggests((c,b)->allowed(c.getSource(),"admin.permissions")?SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(),b):b.buildFuture());
            if(action.equals("show"))target.executes(c->safe(c.getSource(),()->permissions.manage(c.getSource(),action,getString(c,"player-or-uuid"),null)));
            else target.then(argument("role",word()).suggests((c,b)->allowed(c.getSource(),"admin.permissions")?SharedSuggestionProvider.suggest(config.roles.keySet(),b):b.buildFuture()).executes(c->safe(c.getSource(),()->permissions.manage(c.getSource(),action,getString(c,"player-or-uuid"),getString(c,"role")))));
            perms.then(literal(action).then(target));
        }
        root.then(perms);
        var built=root.build();
        var guarded=literal("lt").executes(built.getCommand()).build();
        for(var child:built.getChildren())guarded.addChild(child instanceof LiteralCommandNode<CommandSourceStack> lit?new PermissionLiteral(lit):child);
        d.getRoot().addChild(guarded);
    }
    private void aliases(CommandDispatcher<CommandSourceStack> d) {
        aliasedDispatcher=d; shortcuts.clear();
        var root=d.getRoot().getChild("lt");
        if(root==null)throw new IllegalStateException("Lore Tools root missing");
        config.aliases.forEach((canonical,alias)->{
            var child=root.getChild(canonical);
            if(child==null)return;
            if(d.getRoot().getChild(alias)!=null){LOG.warn("Lore Tools shortcut /{} conflicts; use /lt {}",alias,canonical);return;}
            // A same-name executable leaf redirect can compare equal to its target in
            // Brigadier. Packet node deduplication then produces a self-redirect,
            // which vanilla clients reject as an impossible command tree.
            // Share the implementation and arguments without a redirect edge.
            var shortcut=literal(alias).requires(child.getRequirement()).executes(child.getCommand()).build();
            child.getChildren().forEach(shortcut::addChild);
            d.getRoot().addChild(new PermissionLiteral(shortcut));
            shortcuts.put(canonical,alias); LOG.info("Lore Tools registered /{} -> /lt {} (aliases require restart)",alias,canonical);
        });
    }
    private int help(CommandSourceStack s,String requested) {
        tell(s,"Lore Tools — "+permissions.backend()+" permissions. Click an example to prepare it.");String last="";boolean found=false;
        for(Help h:HELP)if(allowed(s,h.node())&&(requested==null||requested.equals(h.command())||requested.equals(shortcuts.get(h.command())))) {
            if(!h.category().equals(last)){tell(s,h.category()+":");last=h.category();}
            String prefix=shortcuts.containsKey(h.command())?shortcuts.get(h.command()):"lt "+h.command();
            String example="/"+prefix+h.example().substring(h.command().length());
            s.sendSystemMessage(Component.literal("/"+prefix+" "+h.arguments()+" — "+h.description()+"\n  e.g. "+example).withStyle(style->style.withClickEvent(new ClickEvent.SuggestCommand(example))));found=true;
        }
        if(!found)tell(s,"No accessible commands match. Ask an administrator for a Lore Tools role.");
        if(requested!=null&&requested.equals("lore")&&allowed(s,"item.lore"))tell(s,"/lore add <text>; /lore set <line> <text>; /lore remove <line>; /lore clear");
        return 1;
    }
    private static String unquote(String text) throws Exception {
        if(!text.startsWith("\""))return text;StringReader r=new StringReader(text);String value=r.readString();r.skipWhitespace();if(r.canRead())throw new IllegalArgumentException("Unexpected text after closing quote.");return value;
    }
    private int sign(CommandContext<CommandSourceStack> c,String author,String title) {
        return safe(c.getSource(),()->transform(c.getSource(),"book.sign","sign",List.of(author,title),s->ItemOperations.sign(s,author,title),false));
    }
    private int lore(CommandContext<CommandSourceStack> c,String action,int line,String text) {
        return safe(c.getSource(),()->transform(c.getSource(),"item.lore","lore."+action,text.isEmpty()?List.of():List.of(text),s->ItemOperations.lore(s,action,line,text,config.maxLoreLines,config.maxLoreLineChars,config.maxLoreTotalChars),false));
    }
    private int transform(CommandSourceStack source,String node,String action,List<String> newText,UnaryOperator<ItemStack> transform,boolean separate) throws Exception {
        require(source,node);ServerPlayer player=source.getPlayerOrException();
        int selected=player.getInventory().getSelectedSlot();ItemStack identity=player.getMainHandItem();
        if(identity.isEmpty())throw new IllegalArgumentException("Hold an item in your main hand.");
        var saved=BookEdits.pending(player.getUUID());
        if(!saved.isDone())tell(source,"Waiting for your saved book pages…");
        saved.whenComplete((unused,saveError)->server.execute(()->safe(source,()->{
            if(saveError!=null)throw new IllegalArgumentException("Vanilla could not save your book. Reopen it and save again before signing.");
            require(source,node);
            if(player.hasDisconnected()||selected!=player.getInventory().getSelectedSlot()||player.getMainHandItem()!=identity)throw new IllegalArgumentException("Held item changed; retry the command.");
            ItemStack before=identity.copy();ItemStack after=transform.apply(before);
            var filtered=newText.isEmpty()?CompletableFuture.completedFuture(List.<net.minecraft.server.network.FilteredText>of()):player.getTextFilter().processMessageBundle(newText);
            filtered.whenComplete((texts,error)->server.execute(()->safe(source,()->{
                require(source,node);
                if(error!=null||texts==null||texts.size()!=newText.size())throw new IllegalArgumentException("Text filtering unavailable; item unchanged.");
                if(texts.stream().anyMatch(net.minecraft.server.network.FilteredText::isFiltered))throw new IllegalArgumentException("The server text filter rejected this text; item unchanged.");
                if(player.hasDisconnected()||selected!=player.getInventory().getSelectedSlot()||!ItemStack.matches(before,player.getMainHandItem()))throw new IllegalArgumentException("Held item changed; retry the command.");
                if(separate)give(player,after);else{player.setItemInHand(InteractionHand.MAIN_HAND,after);sync(player);}
                audit(source,action,after.getItem().toString());
                if(action.equals("sign")){var book=after.get(DataComponents.WRITTEN_BOOK_CONTENT);tell(source,"Signed “"+book.title().get(player.isTextFilteringEnabled())+"” by "+book.author()+".");}
                else tell(source,separate?"Editable draft added; signed original unchanged.":"Updated held item."+(action.equals("name")&&after.has(DataComponents.WRITTEN_BOOK_CONTENT)?" Use /booktitle (or /lt title) for the actual book title.":""));
                return 1;
            })));
            return 1;
        })));
        return 1;
    }
    public static void sync(ServerPlayer p){p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();p.containerMenu.broadcastChanges();}
    public static void give(ServerPlayer p,ItemStack item) {
        int slot=p.getInventory().getFreeSlot();
        if(slot<0)throw new IllegalArgumentException("You need an empty inventory slot. Nothing was dropped or replaced.");
        p.getInventory().setItem(slot,item);sync(p);
    }
    public static boolean filtered(CommandSourceStack s){return s.getPlayer()!=null&&s.getPlayer().isTextFilteringEnabled();}
    private int libraryAction(CommandSourceStack s,String action,String id) throws Exception {
        require(s,"library."+action);ServerPlayer p=s.getPlayerOrException();
        if(library==null)throw new IllegalArgumentException("Library is starting; retry shortly.");
        BookLibrary.validateId(id);BookLibrary.Entry entry=library.find(id);
        if(action.equals("get")){if(entry==null)throw new IllegalArgumentException("Template unavailable.");get(p,entry);return 1;}
        if(action.equals("save")){
            if(entry!=null)throw new IllegalArgumentException("ID already exists. Use /lt update <id> for an explicit confirmed replacement.");
            write(s,action,id,null,library.encode(p.getMainHandItem()));return 1;
        }
        if(entry==null)throw new IllegalArgumentException("Template unavailable.");
        JsonElement replacement=action.equals("update")?library.encode(p.getMainHandItem()):null;
        String token=UUID.randomUUID().toString();
        confirmations.put(p.getUUID(),new Pending(token,action,entry,replacement,System.nanoTime()+TimeUnit.SECONDS.toNanos(60)));
        String cmd="/lt confirm "+token;
        s.sendSystemMessage(Component.literal("Confirm "+action+" of '"+id+"' revision "+entry.revision()+" within 60 seconds. Future copies only. Click then send: "+cmd).withStyle(st->st.withClickEvent(new ClickEvent.SuggestCommand(cmd))));return 1;
    }
    private int confirm(CommandSourceStack s,String token) throws Exception {
        ServerPlayer p=s.getPlayerOrException();Pending pending=confirmations.get(p.getUUID());
        if(pending==null||!pending.token().equals(token)||pending.expires()<System.nanoTime())throw new IllegalArgumentException("Confirmation missing or expired; request it again.");
        require(s,"library."+pending.action());
        if(!library.current(pending.previous()))throw new IllegalArgumentException("Template changed; request a new confirmation.");
        if(pending.replacement()!=null&&!pending.replacement().equals(library.encode(p.getMainHandItem())))throw new IllegalArgumentException("Held book changed; request a new confirmation.");
        confirmations.remove(p.getUUID());write(s,pending.action(),pending.previous().id(),pending.previous().revision(),pending.replacement());return 1;
    }
    private void write(CommandSourceStack s,String action,String id,String revision,JsonElement replacement) {
        var result=library.write(id,revision,replacement);tell(s,"Saving library change…");
        result.whenComplete((entry,error)->{
            if(error!=null){LOG.error("Library write failed for {}",id,error);fail(s,"Library write failed; inspect the server log. No success was confirmed.");}
            else{audit(s,"library."+action,id);tell(s,"Library "+action+" saved: "+id+". Existing copies are unchanged.");}
        });
    }
    public void get(ServerPlayer p,BookLibrary.Entry entry) {
        CommandSourceStack s=p.createCommandSourceStack();require(s,"library.get");
        if(!library.current(entry))throw new IllegalArgumentException("Template changed; refresh the library before getting a copy.");
        give(p,library.decode(entry));audit(s,"library.get",entry.id());tell(s,"Copy added: "+entry.id());
    }
    public int browse(CommandSourceStack s,String action,String search,int page) {
        return safe(s,()->{
            require(s,"library.browse");if(search.length()>256)throw new IllegalArgumentException("Search is limited to 256 characters.");
            if(action.equals("library")&&config.guiEnabled){LibraryMenu.open(this,s.getPlayerOrException(),search,page);return 1;}
            List<BookLibrary.Entry> found=library.search(search,filtered(s));
            if(found.isEmpty()){tell(s,"No matching books.");return 1;}
            int start=Math.max(0,page)*10;int end=Math.min(found.size(),start+10);
            tell(s,"Books "+(start+1)+"–"+end+" of "+found.size()+" (stable ID order). Narrow your search to see other results.");
            for(var entry:found.subList(Math.min(start,found.size()),end)){
                var b=entry.summary();
                String cmd="/lt read "+entry.id()+" 1";
                s.sendSystemMessage(Component.literal(entry.id()+" — "+b.title(filtered(s))+" by "+b.author()+" ("+b.pages()+" pages). Read: "+cmd).withStyle(st->st.withClickEvent(new ClickEvent.SuggestCommand(cmd))));
            }return 1;
        });
    }
    private int read(CommandSourceStack s,String id,int page) {
        require(s,"library.browse");BookLibrary.Entry e=library.find(id);if(e==null)throw new IllegalArgumentException("Template unavailable.");
        var book=library.decode(e).get(DataComponents.WRITTEN_BOOK_CONTENT);
        if(page<1||page>book.pages().size())throw new IllegalArgumentException("Page must be between 1 and "+book.pages().size()+".");
        tell(s,id+" — page "+page+" / "+book.pages().size()+" (plain read-only preview)");
        String text=book.pages().get(page-1).get(filtered(s)).getString();
        for(int at=0;at<text.length();){int end=Math.min(at+1000,text.length());if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;tell(s,text.substring(at,end));at=end;}
        if(text.isEmpty())tell(s,"[blank page]");return 1;
    }
    private int reload(CommandSourceStack s) {
        require(s,"admin.reload");tell(s,"Validating configuration…");
        CompletableFuture.supplyAsync(()->LoreConfig.load(configFile),configReader).whenComplete((next,error)->server.execute(()->safe(s,()->{
            require(s,"admin.reload");if(error!=null)throw new IllegalArgumentException("Configuration rejected; last working configuration retained: "+error.getCause().getMessage());
            if(!next.aliases.equals(config.aliases))throw new IllegalArgumentException("Alias changes require restart; last working configuration retained.");
            permissions.reload(next);config=next;sentPermissions.clear();audit(s,"reload","config");tell(s,"Configuration reloaded. Backend: "+permissions.backend());return 1;
        })));return 1;
    }
}
