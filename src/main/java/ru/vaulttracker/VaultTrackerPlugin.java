package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VaultTrackerPlugin extends JavaPlugin implements Listener, TabExecutor {
    private Catalogue catalogue;
    private StorageEngine storage;
    private TelegramBotService telegram;
    private GuardService guard;
    private final Object telegramLock=new Object();
    private final Set<BlockKey> scheduled = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private volatile long debounceTicks;
    private volatile int playerLimit;

    @Override public void onEnable() {
        saveDefaultConfig();
        ensureTelegramConfig();
        String id = getConfig().getString("server-id", "test");
        if (!id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid server-id");
        RemoteStore.Settings database = null;
        if (getConfig().getBoolean("database.enabled")) {
            String password = System.getenv("VAULT_DB_PASSWORD");
            if (password == null || password.isBlank()) password = getConfig().getString("database.password", "");
            database = new RemoteStore.Settings(getConfig().getString("database.host", "127.0.0.1"),
                    getConfig().getInt("database.port", 3306), getConfig().getString("database.name", "vault_tracker"),
                    getConfig().getString("database.username", "vault_plugin"), password, id);
        }
        applyRuntimeConfig();
        try {guard=new GuardService(getDataFolder().toPath().resolve("guard-data"),GuardConfig.load(getDataFolder().toPath()),getLogger());}
        catch(Exception e) {throw new IllegalStateException("Не удалось открыть guard.yml / guard-data. Сохраните файлы и проверьте доступ к диску.",e);}
        for(World world:getServer().getWorlds()) guard.world(world.getUID(),world.getName());
        for(Player player:getServer().getOnlinePlayers()) guard.presence(player.getUniqueId(),player.getName(),true);
        storage = new StorageEngine(getDataFolder().toPath().resolve("cache-"+id), database, getLogger());
        catalogue = new Catalogue(snapshot-> {storage.accept(snapshot);guard.accept(snapshot);});
        getServer().getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("vaulttracker")).setExecutor(this);
        Objects.requireNonNull(getCommand("vaulttracker")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("topitem")).setExecutor(this);
        Objects.requireNonNull(getCommand("topitem")).setTabCompleter(this);
        storage.start(snapshots-> {catalogue.restore(snapshots);guard.restore(snapshots);});
        synchronized(telegramLock) { replaceTelegram(); }
        getLogger().info("VaultTracker " + getPluginMeta().getVersion() + ": каталог ресурсов. /vtrack help");
    }
    private void ensureTelegramConfig() {
        java.io.File file=new java.io.File(getDataFolder(),"telegram.yml");
        if(file.exists()) return;
        saveResource("telegram.yml",false);
        getLogger().info("Создан стандартный Telegram-конфиг: "+file.getAbsolutePath());
    }
    private void applyRuntimeConfig() {
        debounceTicks=Math.max(1,Math.min(20,getConfig().getLong("update-delay-ticks",2)));
        playerLimit=Math.max(1,getConfig().getInt("max-vaults-per-player",100));
    }
    private String replaceTelegram() {
        if(telegram!=null) { telegram.close(); telegram=null; }
        try {
            TelegramConfig telegramConfig=TelegramConfig.load(getDataFolder().toPath());
            guard.configure(GuardConfig.load(getDataFolder().toPath()),telegramConfig.adminUserIds());
            if(!telegramConfig.enabled()) { getLogger().info("Telegram-бот выключен в telegram.yml."); return "Telegram-бот выключен."; }
            telegram=new TelegramBotService(telegramConfig,catalogue,storage,getLogger(),guard,new TelegramModeration(this,guard)); telegram.start();
            return "Настройки применены, Telegram-бот перезапущен.";
        } catch(Exception e) {
            getLogger().severe("Telegram-бот не запущен: "+e.getMessage()+". Учёт хранилищ продолжает работать.");
            return "Настройки плагина применены, но Telegram-бот не запущен: "+e.getMessage();
        }
    }
    private void reloadPlugin(CommandSender sender) {
        reloadConfig(); applyRuntimeConfig();
        tell(sender,"Перечитываю настройки и перезапускаю Telegram-бот...");
        Thread.ofVirtual().name("VaultTracker-reload").start(()-> {
            String result;
            synchronized(telegramLock) {
                if(stopping) return;
                result=replaceTelegram();
                if(stopping && telegram!=null) { telegram.close(); telegram=null; return; }
            }
            if(!stopping) getServer().getGlobalRegionScheduler().run(this,task->tell(sender,result));
        });
    }
    private static BlockKey key(Block b) { return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }
    private static Location location(BlockKey p, World w) { return new Location(w,p.x(),p.y(),p.z()); }
    private static String plain(Component c) { return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c); }
    private static boolean marker(String text) { return "[vault]".equalsIgnoreCase(text.trim()); }
    static void decorateOwner(Sign sign, String playerName) {
        SignSide front = sign.getSide(Side.FRONT);
        // Only replace the registration marker; players may keep their own labels.
        if (!marker(plain(front.line(0)))) return;
        front.line(0, Component.text("Собственность"));
        front.line(1, Component.text("игрока"));
        front.line(2, Component.text(playerName));
        front.line(3, Component.empty());
        sign.update(true, false);
    }
    private static boolean readable(BlockKey p, World w) {
        return w.isChunkLoaded(p.x() >> 4,p.z() >> 4) && Bukkit.isOwnedByCurrentRegion(location(p,w));
    }
    static Block attached(Block sign) {
        var data = sign.getBlockData();
        if (data instanceof WallSign wall) return sign.getRelative(wall.getFacing().getOppositeFace());
        if (data instanceof org.bukkit.block.data.type.Sign) return sign.getRelative(BlockFace.DOWN);
        if (data instanceof org.bukkit.block.data.type.HangingSign) return sign.getRelative(BlockFace.UP);
        return null;
    }
    record ChestView(List<BlockKey> chests, Map<String, Long> items) {}
    // null = temporarily unavailable. An empty list = no supported container.
    private ChestView readChest(Block anchor) {
        if (anchor == null) return new ChestView(List.of(), Map.of());
        World w = anchor.getWorld();
        if (!readable(key(anchor),w)) return null;
        BlockState state = anchor.getState();
        if (!(state instanceof Chest first)) {
            ItemStack[] contents = blockContents(state);
            return contents == null ? new ChestView(List.of(), Map.of())
                    : new ChestView(List.of(key(anchor)), ItemCounter.count(Collections.singletonList(contents)));
        }
        var data = (org.bukkit.block.data.type.Chest)anchor.getBlockData();
        List<BlockKey> parts = new ArrayList<>(List.of(key(anchor)));
        List<ItemStack[]> inventories = new ArrayList<>();
        inventories.add(first.getBlockInventory().getContents());
        if (data.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            Block partner = anchor.getRelative(ChestLayout.partner(data.getFacing(),data.getType()));
            if (!readable(key(partner),w)) return null;
            if (!(partner.getState() instanceof Chest second) || partner.getType() != anchor.getType()) return null;
            var otherData = (org.bukkit.block.data.type.Chest)partner.getBlockData();
            if (otherData.getFacing() != data.getFacing() || otherData.getType() == data.getType()
                    || otherData.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) return null;
            parts.add(key(partner));
            inventories.add(second.getBlockInventory().getContents());
        }
        return new ChestView(List.copyOf(parts),ItemCounter.count(inventories));
    }
    static ItemStack[] blockContents(BlockState state) {
        if (state instanceof TileStateInventoryHolder holder) return holder.getSnapshotInventory().getContents();
        if (state instanceof Campfire campfire) {
            ItemStack[] items = new ItemStack[campfire.getSize()];
            for (int i = 0; i < items.length; i++) items[i] = campfire.getItem(i);
            return items;
        }
        return null;
    }
    private void schedule(BlockKey p) {
        if (stopping || catalogue.get(p) == null) return;
        World w = getServer().getWorld(p.world());
        if (w == null || !w.isChunkLoaded(p.x() >> 4, p.z() >> 4) || !scheduled.add(p)) return;
        try {
            getServer().getRegionScheduler().runDelayed(this,location(p,w),task -> {
                scheduled.remove(p);
                if (!stopping) refresh(p,w);
            },debounceTicks);
        } catch (RuntimeException e) {
            scheduled.remove(p);
            if (!stopping && isEnabled()) getLogger().warning("Не удалось запланировать сверку сундука: " + e.getClass().getSimpleName());
        }
    }
    private void refresh(BlockKey p, World w) {
        Snapshot v = catalogue.get(p);
        if (v == null || !readable(p,w)) return;
        Block block = location(p,w).getBlock();
        // Registration belongs to the persisted catalogue, not the displayed marker.
        // Editing its text does not change registration or ownership.
        if (!(block.getState() instanceof Sign sign)) {
            catalogue.remove(p,v.generation(),System.currentTimeMillis()); return;
        }
        ChestView view = readChest(attached(block));
        if (view == null) return;
        if (view.chests().isEmpty()) { catalogue.remove(p,v.generation(),System.currentTimeMillis()); return; }
        if (!catalogue.observe(p,v.generation(),view.chests(),view.items(),System.currentTimeMillis()))
            getLogger().warning("Объединены два зарегистрированных хранилища. Их учёт снят во избежание двойного подсчёта; заново напишите [vault] на одной табличке.");
        else decorateOwner(sign, v.playerName());
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void validateSign(SignChangeEvent e) {
        if (e.getSide() != Side.FRONT) return;
        Snapshot existing = catalogue.get(key(e.getBlock()));
        if (existing != null && !existing.owner().equals(e.getPlayer().getUniqueId()) && !e.getPlayer().hasPermission("vaulttracker.admin")) {
            e.setCancelled(true); tell(e.getPlayer(),"Эта табличка зарегистрирована другим игроком."); return;
        }
        if (existing != null) return;
        if (!marker(plain(e.line(0)))) return;
        if (!storage.ready()) { e.setCancelled(true); tell(e.getPlayer(),"Каталог пока недоступен. Сообщите администратору: /vtrack status"); return; }
        if (!e.getPlayer().hasPermission("vaulttracker.register")) { e.setCancelled(true); tell(e.getPlayer(),"Нет права vaulttracker.register."); return; }
        ChestView view = readChest(attached(e.getBlock()));
        if (view == null || view.chests().isEmpty()) { e.setCancelled(true); tell(e.getPlayer(),"Поставьте табличку на хранилище или прикрепите сбоку. Эндер-сундук не поддерживается: его инвентарь принадлежит игроку, а не блоку."); return; }
        for (BlockKey chest : view.chests()) {
            BlockKey claimed = catalogue.claimedBy(chest);
            if (claimed != null && !claimed.equals(key(e.getBlock()))) {
                e.setCancelled(true); tell(e.getPlayer(),"Это хранилище уже зарегистрировано. Для двойного сундука нужна одна табличка."); return;
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void changedSign(SignChangeEvent e) {
        if (e.getSide() != Side.FRONT) return;
        BlockKey p = key(e.getBlock());
        Snapshot existing = catalogue.get(p);
        // Text edits need neither a catalogue mutation nor an inventory check.
        if (existing != null || !marker(plain(e.line(0)))) return;
        if (!storage.ready() || !e.getPlayer().hasPermission("vaulttracker.register")) return;
        ChestView view = readChest(attached(e.getBlock()));
        if (view == null || view.chests().isEmpty()) return;
        try {
            catalogue.register(p,e.getPlayer().getUniqueId(),e.getPlayer().getName(),view.chests(),view.items(),System.currentTimeMillis(),playerLimit);
            // Decorate on the region scheduler after Minecraft applies the event's text.
            schedule(p);
            tell(e.getPlayer(),"Хранилище поставлено на учёт (" + (view.chests().size()==2 ? "двойной сундук" : "один блок") + "). Сохранение выполняется в фоне.");
        } catch (IllegalArgumentException ex) { tell(e.getPlayer(),ex.getMessage()); }
    }
    private void nearby(Block b) { nearby(b,null,false); }
    private void nearbyAutomated(Block b) { nearby(b,null,true); }
    private void nearby(Block b,Player actor) { nearby(b,actor,false); }
    private void nearby(Block b,Player actor,boolean automated) {
        BlockKey p = key(b);
        for (int cx=(p.x()-2)>>4; cx<=(p.x()+2)>>4; cx++) for (int cz=(p.z()-2)>>4; cz<=(p.z()+2)>>4; cz++)
            for (Snapshot v : catalogue.inChunk(new BlockKey.ChunkKey(p.world(),cx,cz)))
                if (near(v.sign(),p) || v.chests().stream().anyMatch(c -> near(c,p))) {
                    if(actor!=null) guard.attribute(v.sign(),actor.getUniqueId(),actor.getName());
                    if(automated) guard.automated(v.sign());
                    schedule(v.sign());
                }
    }
    private static boolean near(BlockKey a, BlockKey b) {
        return a.world().equals(b.world()) && Math.abs((long)a.x()-b.x())<=2 && Math.abs((long)a.y()-b.y())<=2 && Math.abs((long)a.z()-b.z())<=2;
    }
    private void changedInventory(Inventory inventory,Player actor) {
        changedInventory(inventory,actor,actor==null);
    }
    private void changedInventory(Inventory inventory,Player actor,boolean automated) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest d) {
            if (d.getLeftSide() instanceof Chest c) nearby(c.getBlock(),actor,automated);
            if (d.getRightSide() instanceof Chest c) nearby(c.getBlock(),actor,automated);
        } else if (holder instanceof BlockInventoryHolder block) nearby(block.getBlock(),actor,automated);
    }
    private void destroyed(Block b) {
        BlockKey p=key(b);
        Snapshot marker=catalogue.get(p);
        if (marker != null) catalogue.remove(p,marker.generation(),System.currentTimeMillis());
        BlockKey sign=catalogue.claimedBy(p);
        if (sign != null) {
            Snapshot v=catalogue.get(sign);
            if (v != null && v.chests().getFirst().equals(p)) catalogue.remove(sign,v.generation(),System.currentTimeMillis());
        }
        nearby(b);
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void click(InventoryClickEvent e) { changedInventory(e.getInventory(),e.getWhoClicked() instanceof Player p ? p : null); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void drag(InventoryDragEvent e) { changedInventory(e.getInventory(),e.getWhoClicked() instanceof Player p ? p : null); }
    @EventHandler(priority=EventPriority.MONITOR) public void close(InventoryCloseEvent e) { changedInventory(e.getInventory(),e.getPlayer() instanceof Player p ? p : null); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void move(InventoryMoveItemEvent e) { changedInventory(e.getSource(),null); changedInventory(e.getDestination(),null); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void pickup(InventoryPickupItemEvent e) { changedInventory(e.getInventory(),null); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void cook(BlockCookEvent e) { nearbyAutomated(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void burn(FurnaceBurnEvent e) { nearbyAutomated(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void brew(BrewEvent e) { nearbyAutomated(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void brewFuel(BrewingStandFuelEvent e) { nearbyAutomated(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void dispense(BlockDispenseEvent e) { nearbyAutomated(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void craft(CrafterCraftEvent e) { nearbyAutomated(e.getBlock()); }
    // Direct interactions include bookshelves, shelves, pots, jukeboxes and campfires.
    @EventHandler(priority=EventPriority.MONITOR) public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && e.useInteractedBlock() != Event.Result.DENY) nearby(e.getClickedBlock(),e.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void broken(BlockBreakEvent e) {
        BlockKey p=key(e.getBlock());Snapshot marker=catalogue.get(p);BlockKey ownerSign=catalogue.claimedBy(p);
        if(marker!=null) guard.attribute(marker.sign(),e.getPlayer().getUniqueId(),e.getPlayer().getName());
        if(ownerSign!=null) guard.attribute(ownerSign,e.getPlayer().getUniqueId(),e.getPlayer().getName());
        destroyed(e.getBlock());
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void explode(EntityExplodeEvent e) { e.blockList().forEach(this::destroyed); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void explode(BlockExplodeEvent e) { e.blockList().forEach(this::destroyed); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void placed(BlockPlaceEvent e) { nearby(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void physics(BlockPhysicsEvent e) { if (catalogue.get(key(e.getBlock())) != null) schedule(key(e.getBlock())); }
    @EventHandler(priority=EventPriority.MONITOR) public void chunkLoad(ChunkLoadEvent e) {
        if (!storage.ready()) return;
        for (Snapshot v : catalogue.inChunk(new BlockKey.ChunkKey(e.getWorld().getUID(),e.getChunk().getX(),e.getChunk().getZ()))) schedule(v.sign());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void worldLoad(WorldLoadEvent e) {guard.world(e.getWorld().getUID(),e.getWorld().getName());}
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void chunkUnload(ChunkUnloadEvent e) {
        for (Snapshot v : catalogue.inChunk(new BlockKey.ChunkKey(e.getWorld().getUID(),e.getChunk().getX(),e.getChunk().getZ()))) refresh(v.sign(),e.getWorld());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        guard.presence(e.getPlayer().getUniqueId(),e.getPlayer().getName(),true);
        if (storage.ready()) catalogue.rename(e.getPlayer().getUniqueId(),e.getPlayer().getName());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {guard.presence(e.getPlayer().getUniqueId(),e.getPlayer().getName(),false);}
    private static void tell(CommandSender sender,String message) { sender.sendMessage(Component.text("[VaultTracker] " + message)); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("topitem") || command.getName().equalsIgnoreCase("itemtop")) {
            return new TopItemCommand(catalogue,storage::ready).execute(sender,args);
        }
        String action=args.length==0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if(action.equals("link")) {
            if(!(sender instanceof Player player)) {tell(sender,"Привязка выполняется только игроком в Minecraft.");return true;}
            if(!player.hasPermission("vaulttracker.link")) {tell(sender,"Нет права vaulttracker.link.");return true;}
            if(args.length!=2 || !args[1].matches("[0-9a-f]{32}")) {tell(sender,"Получите готовую команду в личном кабинете Telegram-бота.");return true;}
            guard.link(player.getUniqueId(),player.getName(),args[1]).whenComplete((message,error)-> {
                if(stopping) return;
                player.getScheduler().run(this,task->tell(player,error==null ? message : "Не удалось сохранить привязку. Сообщите администратору."),null);
            });
        } else if (action.equals("status")) {
            if (!sender.hasPermission("vaulttracker.admin")) { tell(sender,"Нет права vaulttracker.admin."); return true; }
            tell(sender,storage.status()); tell(sender,"Хранилищ: " + catalogue.size());
        } else if (action.equals("rescan")) {
            if (!sender.hasPermission("vaulttracker.admin")) { tell(sender,"Нет права vaulttracker.admin."); return true; }
            catalogue.all().forEach(v -> schedule(v.sign())); tell(sender,"Запущена сверка загруженных хранилищ.");
        } else if (action.equals("reload")) {
            if (!sender.hasPermission("vaulttracker.admin")) { tell(sender,"Нет права vaulttracker.admin."); return true; }
            reloadPlugin(sender);
        } else if (action.equals("find") && args.length==2) {
            if (!sender.hasPermission("vaulttracker.search")) { tell(sender,"Нет права vaulttracker.search."); return true; }
            if (!storage.ready()) { tell(sender,"Каталог загружается. Повторите поиск чуть позже."); return true; }
            String material=args[1].toUpperCase(Locale.ROOT).replace("MINECRAFT:","");
            if (material.equals("DAIMOND")) material = "DIAMOND";
            Material type=Material.getMaterial(material);
            if (type==null || !type.isItem()) { tell(sender,"Неизвестный предмет. Пример: /vtrack find DIAMOND"); return true; }
            List<String> found=catalogue.find(material,20);
            tell(sender,material+" — последние известные остатки:");
            if (found.isEmpty()) tell(sender,"Не найдено."); else found.forEach(line -> tell(sender,line));
        } else if (action.equals("mine") && sender instanceof Player player) {
            List<Snapshot> mine=catalogue.all().stream().filter(v->v.owner().equals(player.getUniqueId())).toList();
            tell(sender,"Ваших хранилищ: " + mine.size() + ". Первые 10:");
            mine.stream().limit(10).forEach(v->tell(sender,v.sign().x()+" "+v.sign().y()+" "+v.sign().z()+" — "+v.chests().size()+" блоков"));
        } else {
            tell(sender,"Напишите [vault] на первой строке таблички на хранилище: появится ваш ник.");
            tell(sender,"/vtrack mine — ваши хранилища; /topitem diamond или /vtrack find DIAMOND — у кого есть алмазы. Поиск доступен постоянно.");
            tell(sender,"/vtrack link <код> — привязать персонажа к Telegram. Получите код кнопкой в личном чате бота.");
            if (sender.hasPermission("vaulttracker.admin")) tell(sender,"/vtrack status | /vtrack rescan | /vtrack reload — управление каталогом.");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if (command.getName().equalsIgnoreCase("topitem")) {
            if (!sender.hasPermission("vaulttracker.search") || args.length==0) return List.of();
            boolean explicit=args.length>1 && args[0].equalsIgnoreCase("player");
            int position=args.length-(explicit ? 1 : 0);
            String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
            Set<String> options=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (position==1) {
                options.addAll(catalogue.ownerNames());
                if (!explicit) options.add("player");
            }
            if ((!explicit && position==1) || position==2)
                Arrays.stream(Material.values()).filter(m -> !m.name().startsWith("LEGACY_")).filter(Material::isItem)
                        .map(m -> m.name().toLowerCase(Locale.ROOT)).forEach(options::add);
            if (position==2) {
                String nick=args[explicit ? 1 : 0];
                var owners=catalogue.ownerItems(nick);
                if (owners.size()==1) {
                    int pages=Math.max(1,(owners.getFirst().items().size()+TopItemCommand.PAGE_SIZE-1)/TopItemCommand.PAGE_SIZE);
                    for (int page=1;page<=pages;page++) options.add(Integer.toString(page));
                }
            }
            return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(40).toList();
        }
        if (args.length==1) return List.of("help","mine","find","link","status","rescan","reload").stream()
                .filter(s -> (!s.equals("status") && !s.equals("rescan") && !s.equals("reload")) || sender.hasPermission("vaulttracker.admin"))
                .filter(s -> !s.equals("find") || sender.hasPermission("vaulttracker.search"))
                .filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length==2 && args[0].equalsIgnoreCase("find")) return Arrays.stream(Material.values()).filter(Material::isItem).map(Enum::name).filter(s->s.startsWith(args[1].toUpperCase(Locale.ROOT))).limit(40).toList();
        return List.of();
    }
    @Override public void onDisable() {
        stopping=true;
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        synchronized(telegramLock) { if(telegram!=null) { telegram.close(); telegram=null; } }
        if (storage != null) storage.close();
        if (guard != null) guard.close();
    }
}
