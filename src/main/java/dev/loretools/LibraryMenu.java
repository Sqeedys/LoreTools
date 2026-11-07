package dev.loretools;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import java.util.*;

/** Vanilla chest protocol, entirely inert slots. No invocation of vanilla click movement. */
public final class LibraryMenu extends ChestMenu {
    private final LoreTools tools;
    private final ServerPlayer viewer;
    private final SimpleContainer icons;
    private final String search;
    private List<BookLibrary.Entry> results;
    private int page;
    private BookLibrary.Entry detail;
    private int previewPage;
    private int previewPart;
    private LibraryMenu(int id,Inventory inventory,LoreTools tools,String search,int page,SimpleContainer icons) {
        super(MenuType.GENERIC_9x6,id,inventory,icons,6);
        this.tools=tools;this.viewer=(ServerPlayer)inventory.player;this.icons=icons;this.search=search;this.page=page;
        results=tools.library.search(search,viewer.isTextFilteringEnabled());render();
    }
    public static void open(LoreTools tools,ServerPlayer viewer,String search,int page) {
        tools.require(viewer.createCommandSourceStack(),"library.browse");
        if(!tools.config.guiEnabled)throw new IllegalArgumentException("Library menu disabled; use /lt list [search] and /lt get <id>.");
        // Vanilla safely returns an existing cursor stack when replacing another menu.
        viewer.openMenu(new SimpleMenuProvider((id,inv,p)->new LibraryMenu(id,inv,tools,search,page,new SimpleContainer(54)),Component.literal("Lore Tools — Book Library")));
    }
    private void icon(int slot,Item item,String name,List<String> lore) {
        ItemStack stack=new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        stack.set(DataComponents.LORE,new ItemLore(lore.stream().map(s->(Component)Component.literal(s)).toList()));icons.setItem(slot,stack);
    }
    private void control(int slot,String label){icon(slot,Items.PAPER,label,List.of("Click to select. Menu items cannot be taken."));}
    private void render() {
        icons.clearContent();
        if(detail!=null){renderDetail();return;}
        page=Math.max(0,Math.min(page,Math.max(0,(results.size()-1)/45)));
        int start=page*45;
        for(int i=0;i<45&&start+i<results.size();i++) {
            var entry=results.get(start+i);var b=entry.summary();
            icon(i,Items.BOOK,b.title(viewer.isTextFilteringEnabled()),List.of("Author: "+b.author(),"ID: "+entry.id(),"Pages: "+b.pages(),b.preview(viewer.isTextFilteringEnabled()),"Click: details / read-only preview"));
        }
        if(results.isEmpty())control(22,"No matching books — /lt library <search>");
        if(page>0)control(45,"Previous results");
        control(49,"Page "+(page+1)+" / "+Math.max(1,(results.size()+44)/45)+" — "+results.size()+" books");
        control(47,"Refresh results");control(50,"Search: /lt library <title, author or ID>");control(52,"Close");
        if((page+1)*45<results.size())control(53,"Next results");
    }
    private void renderDetail() {
        var book=tools.library.decode(detail).get(DataComponents.WRITTEN_BOOK_CONTENT);
        icon(4,Items.BOOK,book.title().get(viewer.isTextFilteringEnabled()),List.of("Author: "+book.author(),"ID: "+detail.id(),"Pages: "+book.pages().size(),"Read-only plain-text preview; original formatting stays in copies."));
        int count=book.pages().size();previewPage=Math.max(0,Math.min(previewPage,Math.max(0,count-1)));
        String text=count==0?"[No pages]":book.pages().get(previewPage).get(viewer.isTextFilteringEnabled()).getString();
        List<String> parts=previewParts(text);previewPart=Math.max(0,Math.min(previewPart,parts.size()-1));
        List<String> lines=new ArrayList<>();
        for(String line:parts.get(previewPart).split("\n",-1))lines.addAll(split(line,65));
        icon(22,Items.PAPER,"Page "+(count==0?0:previewPage+1)+" / "+count+" — part "+(previewPart+1)+" / "+parts.size(),lines);
        control(45,"Back to results");if(previewPage>0)control(46,"Previous book page");
        if(previewPart>0)control(47,"Previous preview part");
        if(tools.allowed(viewer.createCommandSourceStack(),"library.get"))control(49,"Get Copy — one empty inventory slot required");
        if(previewPart+1<parts.size())control(50,"Next preview part");
        control(52,"Close");if(previewPage+1<count)control(53,"Next book page");
    }
    static String shortText(String text,int max){return text.length()<=max?text:split(text,max).getFirst()+"…";}
    static List<String> previewParts(String text) {
        // Keep newline-heavy books within vanilla's 256-line tooltip component limit.
        if(text.isEmpty())return List.of("");List<String> out=new ArrayList<>();int start=0,lines=0;
        for(int at=0;at<text.length();at++){
            if(text.charAt(at)=='\n')lines++;
            if(at-start>=798||lines>=160){int end=at+1;if(Character.isHighSurrogate(text.charAt(at))&&end<text.length())end++;out.add(text.substring(start,end));start=end;at=end-1;lines=0;}
        }
        if(start<text.length())out.add(text.substring(start));return out;
    }
    static List<String> split(String text,int size) {
        if(text.isEmpty())return List.of("");List<String> parts=new ArrayList<>();
        for(int pos=0;pos<text.length();){int end=Math.min(pos+size,text.length());if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;parts.add(text.substring(pos,end));pos=end;}return parts;
    }
    @Override public void clicked(int slot,int button,ContainerInput input,Player player) {
        if(player!=viewer||!stillValid(player)){viewer.closeContainer();return;}
        // Shift, drag, hotbar/offhand swap, creative clone, throw, double click and inventory clicks are inert.
        if(input!=ContainerInput.PICKUP||button!=0||slot<0||slot>=54){broadcastFullState();return;}
        try {
            if(slot==52){viewer.closeContainer();return;}
            if(detail!=null){
                if(slot==45){detail=null;results=tools.library.search(search,viewer.isTextFilteringEnabled());render();}
                else {
                    if(!tools.library.current(detail)){detail=null;results=tools.library.search(search,viewer.isTextFilteringEnabled());render();throw new IllegalArgumentException("Template changed; select its current entry again.");}
                    switch(slot){case 46-> {previewPage--;previewPart=0;}case 47->previewPart--;case 49->tools.get(viewer,detail);case 50->previewPart++;case 53->{previewPage++;previewPart=0;}default->{}}
                    render();
                }
            }else {
                if(slot<45&&page*45+slot<results.size()) {
                    BookLibrary.Entry selected=results.get(page*45+slot);
                    if(!tools.library.current(selected))throw new IllegalArgumentException("Template changed; click Refresh results.");
                    detail=selected;previewPage=0;previewPart=0;
                }else if(slot==45)page--;else if(slot==53)page++;else if(slot==47)results=tools.library.search(search,viewer.isTextFilteringEnabled());
                render();
            }
        }catch(IllegalArgumentException error){LoreTools.fail(viewer.createCommandSourceStack(),error.getMessage());}
        broadcastFullState();
    }
    @Override public boolean stillValid(Player player){return player==viewer&&tools.config.guiEnabled&&tools.allowed(viewer.createCommandSourceStack(),"library.browse");}
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean canDragTo(Slot slot){return false;}
    @Override public boolean canTakeItemForPickAll(ItemStack stack,Slot slot){return false;}
    @Override public void broadcastChanges(){
        if(viewer!=null&&!stillValid(viewer)){icons.clearContent();viewer.closeContainer();return;}
        super.broadcastChanges();
    }
}
