package ru.vaulttracker.smoke;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import ru.vaulttracker.*;
import java.lang.reflect.*;
import java.util.*;

/** Destructive fixture ONLY for the disposable local smoke_world; refuses every other world. */
public final class SmokePlugin extends JavaPlugin {
    private VaultTrackerPlugin plugin;
    private Catalogue catalogue;
    private World world;
    private Method refresh, destroyed;
    private final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000002");
    @Override public void onEnable() {
        getServer().getGlobalRegionScheduler().runDelayed(this,t->{
            world=getServer().getWorld("smoke_world");
            if (world==null || getServer().getPort()!=25579) { getLogger().severe("Refusing non-disposable environment"); return; }
            world.getChunkAtAsync(0,0,true).thenRun(()->getServer().getRegionScheduler().execute(this,new Location(world,8,80,8),()->{
                try { setup(); testOrientations(); testBlockStorages(); testPlayerCommands(); testHopperEvent(); }
                catch (Throwable e) { fail(e); }
            }));
        },100);
    }
    private void setup() throws Exception {
        // With no player online, the fixture must retain its chunk across delayed steps.
        world.addPluginChunkTicket(0,0,this);
        plugin=(VaultTrackerPlugin)getServer().getPluginManager().getPlugin("VaultTracker");
        Field f=VaultTrackerPlugin.class.getDeclaredField("catalogue"); f.setAccessible(true); catalogue=(Catalogue)f.get(plugin);
        refresh=VaultTrackerPlugin.class.getDeclaredMethod("refresh",BlockKey.class,World.class); refresh.setAccessible(true);
        destroyed=VaultTrackerPlugin.class.getDeclaredMethod("destroyed",Block.class); destroyed.setAccessible(true);
        for (Snapshot v:catalogue.all()) catalogue.remove(v.sign(),v.generation(),System.currentTimeMillis());
    }
    private BlockKey key(Block b) { return new BlockKey(world.getUID(),b.getX(),b.getY(),b.getZ()); }
    private void check(boolean value,String label) { if (!value) throw new AssertionError(label); getLogger().info("SMOKE_PASS "+label); }
    private Block sign(Block anchor,BlockFace outward) {
        Block b=anchor.getRelative(outward); b.setType(Material.OAK_WALL_SIGN,false);
        WallSign data=(WallSign)b.getBlockData(); data.setFacing(outward); b.setBlockData(data,false);
        Sign s=(Sign)b.getState(); s.getSide(Side.FRONT).line(0,Component.text("[vault]")); s.update(true,false); return b;
    }
    private void testOrientations() throws Exception {
        Block anchor=world.getBlockAt(8,80,8);
        for (BlockFace facing:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST)) {
            for (int x=6;x<=10;x++) for (int z=6;z<=10;z++) world.getBlockAt(x,80,z).setType(Material.AIR,false);
            BlockFace side=ChestLayout.partner(facing,org.bukkit.block.data.type.Chest.Type.LEFT);
            Block partner=anchor.getRelative(side);
            anchor.setType(Material.CHEST,false); partner.setType(Material.CHEST,false);
            var a=(org.bukkit.block.data.type.Chest)anchor.getBlockData(); a.setFacing(facing); a.setType(org.bukkit.block.data.type.Chest.Type.LEFT); anchor.setBlockData(a,false);
            var b=(org.bukkit.block.data.type.Chest)partner.getBlockData(); b.setFacing(facing); b.setType(org.bukkit.block.data.type.Chest.Type.RIGHT); partner.setBlockData(b,false);
            ((Chest)anchor.getState()).getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
            ((Chest)partner.getState()).getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,11));
            Block marker=sign(anchor,side.getOppositeFace());
            catalogue.register(key(marker),owner,"Smoke",List.of(key(anchor),key(partner)),Map.of(),System.currentTimeMillis(),100);
            refresh.invoke(plugin,key(marker),world);
            check(catalogue.get(key(marker)).items().getOrDefault("DIAMOND",0L)==18,"double chest "+facing);
            check(((Sign)marker.getState()).getSide(Side.FRONT).line(2).equals(Component.text("Smoke")),"marker replaced by owner "+facing);
            refresh.invoke(plugin,key(marker),world);
            check(catalogue.get(key(marker)).items().getOrDefault("DIAMOND",0L)==18,"named sign survives reconciliation "+facing);
            catalogue.rename(owner,"RenamedSmoke");
            refresh.invoke(plugin,key(marker),world);
            check(((Sign)marker.getState()).getSide(Side.FRONT).line(2).equals(Component.text("RenamedSmoke")),"owner rename updates sign "+facing);
            destroyed.invoke(plugin,marker);
            check(catalogue.get(key(marker))==null,"sign removal "+facing);
        }
    }
    private void testHopperEvent() throws Exception {
        for (int x=6;x<=10;x++) for (int z=6;z<=10;z++) world.getBlockAt(x,80,z).setType(Material.AIR,false);
        Block anchor=world.getBlockAt(8,80,8); anchor.setType(Material.CHEST,false);
        Block marker=sign(anchor,BlockFace.NORTH);
        catalogue.register(key(marker),owner,"Smoke",List.of(key(anchor)),Map.of(),System.currentTimeMillis(),100);
        var inventory=((Chest)anchor.getState()).getBlockInventory();
        inventory.setItem(0,new ItemStack(Material.IRON_INGOT,9));
        getServer().getPluginManager().callEvent(new InventoryMoveItemEvent(inventory,new ItemStack(Material.IRON_INGOT,1),inventory,true));
        getServer().getRegionScheduler().runDelayed(this,anchor.getLocation(),task->{
            try {
                check(catalogue.get(key(marker)).items().getOrDefault("IRON_INGOT",0L)==9,"hopper event region scheduling");
                var current=((Chest)anchor.getState()).getBlockInventory();
                current.clear();
                getServer().getPluginManager().callEvent(new InventoryMoveItemEvent(current,new ItemStack(Material.IRON_INGOT,1),current,true));
                getServer().getRegionScheduler().runDelayed(this,anchor.getLocation(),next->{
                    try {
                        check(catalogue.get(key(marker)).items().isEmpty(),"empty chest clears totals after event");
                        destroyed.invoke(plugin,anchor);
                        check(catalogue.get(key(marker))==null,"support chest removal");
                        getLogger().info("SMOKE_OK all native Folia checks passed"); finish();
                    } catch (Throwable e) { fail(e); }
                },10);
            } catch (Throwable e) { fail(e); }
        },10);
    }
    private void testBlockStorages() throws Exception {
        for (int x=6;x<=10;x++) for (int z=6;z<=10;z++) world.getBlockAt(x,80,z).setType(Material.AIR,false);
        Block anchor = world.getBlockAt(8,80,8);
        List<Material> types = new ArrayList<>(List.of(Material.BARREL, Material.FURNACE, Material.BLAST_FURNACE,
                Material.SMOKER, Material.HOPPER, Material.DISPENSER, Material.DROPPER, Material.BREWING_STAND,
                Material.CRAFTER, Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.JUKEBOX,
                Material.LECTERN, Material.OAK_SHELF));
        for (Material type : Material.values()) if (!type.name().startsWith("LEGACY_")
                && (type.name().equals("SHULKER_BOX") || type.name().endsWith("_SHULKER_BOX"))) types.add(type);
        for (Material type : types) {
            anchor.setType(Material.AIR,false); anchor.setType(type,false);
            var holder = (io.papermc.paper.block.TileStateInventoryHolder)anchor.getState();
            Material resource = switch(type) {
                case CHISELED_BOOKSHELF -> Material.BOOK;
                case LECTERN -> Material.WRITTEN_BOOK;
                case JUKEBOX -> Material.MUSIC_DISC_CAT;
                default -> Material.DIAMOND;
            };
            holder.getInventory().setItem(0,new ItemStack(resource,1));
            Block marker = sign(anchor,BlockFace.NORTH);
            catalogue.register(key(marker),owner,"Smoke",List.of(key(anchor)),Map.of(),System.currentTimeMillis(),100);
            refresh.invoke(plugin,key(marker),world);
            check(catalogue.get(key(marker)).items().getOrDefault(resource.name(),0L)==1,"block inventory "+type);
            destroyed.invoke(plugin,anchor);
            check(catalogue.get(key(marker))==null,"remove storage "+type);
        }
    }
    private void testPlayerCommands() throws Exception {
        Method name=Class.forName("ru.vaulttracker.RussianItems",true,plugin.getClass().getClassLoader()).getDeclaredMethod("name",String.class);
        name.setAccessible(true);
        List<String> missing=new ArrayList<>();
        for(Material material:Material.values()) if(!material.name().startsWith("LEGACY_") && material.isItem()
                && name.invoke(null,material.name()).equals(material.name())) missing.add(material.name());
        check(missing.isEmpty(),"Russian item names complete: "+missing);
        List<Component> messages=new ArrayList<>();
        var sender=(org.bukkit.command.CommandSender)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.bukkit.command.CommandSender.class},(proxy,method,args)-> {
                    if(method.getName().equals("hasPermission")) return true;
                    if(method.getName().equals("sendMessage") && args!=null && args[0] instanceof Component component) messages.add(component);
                    return null;
                });
        Map<String,Long> items=new HashMap<>();
        for(Material material:List.of(Material.DIAMOND,Material.STONE,Material.IRON_INGOT,Material.GOLD_INGOT,Material.EMERALD,
                Material.COAL,Material.COPPER_INGOT,Material.DIRT,Material.SAND)) items.put(material.name(),7L);
        BlockKey marker=new BlockKey(world.getUID(),20,80,20);
        Snapshot v=catalogue.register(marker,owner,"ShopTester",List.of(new BlockKey(world.getUID(),20,80,21)),items,System.currentTimeMillis(),100);
        plugin.onCommand(sender,plugin.getCommand("topitem"),"topitem",new String[]{"ShopTester"});
        check(messages.size()==10,"player resources page size");
        check(findClick(messages.getLast(),"/topitem player ShopTester 2"),"clickable next page command");
        messages.clear();
        plugin.onCommand(sender,plugin.getCommand("topitem"),"topitem",new String[]{"player","ShopTester","2"});
        check(messages.size()==3 && findClick(messages.getLast(),"/topitem player ShopTester 1"),"last page and back button");
        messages.clear();
        plugin.onCommand(sender,plugin.getCommand("topitem"),"topitem",new String[]{"ShopTester","diamon"});
        check(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(messages.getFirst()).contains("Алмаз: 7 шт."),"player specific item typo and Russian name");
        catalogue.remove(marker,v.generation(),System.currentTimeMillis());
    }
    private boolean findClick(Component component,String command) {
        return component.clickEvent()!=null && component.clickEvent().value().equals(command)
                || component.children().stream().anyMatch(child -> findClick(child,command));
    }
    private void fail(Throwable e) { getLogger().log(java.util.logging.Level.SEVERE,"SMOKE_FAIL",e); finish(); }
    private void finish() { getServer().getGlobalRegionScheduler().runDelayed(this,t->getServer().shutdown(),20); }
}

