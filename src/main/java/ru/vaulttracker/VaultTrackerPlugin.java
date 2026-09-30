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
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public final class VaultTrackerPlugin extends JavaPlugin implements Listener, TabExecutor {
    private Catalogue catalogue;
    private StorageEngine storage;
    private TelegramBotService telegram;
    private TelegramChatBridge telegramChat;
    private GuardService guard;
    private PlayerDirectMessages directMessages;
    private SearchCompass searchCompass;
    private BastionTrust bastionTrust;
    private ChildPlayers childPlayers;
    private final Object telegramLock=new Object();
    private final Set<BlockKey> scheduled = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService voteDeliveries=Executors.newSingleThreadScheduledExecutor(
            task->Thread.ofVirtual().name("VaultTracker-vote-delivery").unstarted(task));
    private volatile boolean stopping;
    private volatile long debounceTicks;
    private volatile int playerLimit;

    @Override public void onEnable() {
        saveDefaultConfig();
        ensureTelegramConfig();
        TelegramItemIcons.configure(getDataFolder().toPath(),getLogger());
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
        directMessages=new PlayerDirectMessages(this,guard);
        for(World world:getServer().getWorlds()) guard.world(world.getUID(),world.getName());
        bastionTrust=new BastionTrust(this);
        for(Player player:getServer().getOnlinePlayers()) {guard.presence(player.getUniqueId(),player.getName(),true);retryBastion(player.getUniqueId());}
        storage = new StorageEngine(getDataFolder().toPath().resolve("cache-"+id), database, getLogger(),guard::knownPlayerUuid);
        catalogue = new Catalogue(snapshot-> {storage.accept(snapshot);guard.accept(snapshot);});
        searchCompass=new SearchCompass(this,catalogue,guard);
        childPlayers=new ChildPlayers(this,guard);
        getServer().getPluginManager().registerEvents(childPlayers,this);childPlayers.start();
        getServer().getPluginManager().registerEvents(searchCompass,this);
        getServer().getPluginManager().registerEvents(this,this);
        registerVotifier();
        voteDeliveries.scheduleWithFixedDelay(this::deliverServerVotes,2,10,TimeUnit.SECONDS);
        // Vanilla remains the source; a sparse UUID-keyed snapshot protects history across world moves.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this,task->{
            if(stopping) return;
            for(Player player:getServer().getOnlinePlayers()) player.getScheduler().run(this,t->snapshotStatistics(player),()->{});
        },72_000L,72_000L);
        Objects.requireNonNull(getCommand("vaulttracker")).setExecutor(this);
        Objects.requireNonNull(getCommand("vaulttracker")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("topitem")).setExecutor(this);
        Objects.requireNonNull(getCommand("topitem")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("find")).setExecutor(this);
        Objects.requireNonNull(getCommand("find")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("tg")).setExecutor(this);
        Objects.requireNonNull(getCommand("tg")).setTabCompleter(this);
        storage.start(snapshots-> {
            catalogue.restore(snapshots);guard.restore(snapshots);
            for(UUID owner:snapshots.stream().map(Snapshot::owner).distinct().toList()) {
                String current=guard.knownPlayerName(owner);
                if(current!=null) catalogue.rename(owner,current);
            }
        });
        synchronized(telegramLock) { replaceTelegram(); }
        if(telegramChat!=null) telegramChat.serverEvent(true);
        getLogger().info("VaultTracker " + getPluginMeta().getVersion() + ": каталог ресурсов. /vtrack help");
    }
    @SuppressWarnings("unchecked")
    private void registerVotifier() {
        Plugin votifier=getServer().getPluginManager().getPlugin("VotifierPlus");
        if(votifier==null||!votifier.isEnabled()) {getLogger().warning("VotifierPlus не найден: награды за голос сервера выключены.");return;}
        try {
            Class<?> raw=Class.forName("com.vexsoftware.votifier.model.VotifierEvent",true,votifier.getClass().getClassLoader());
            Class<? extends Event> type=(Class<? extends Event>)raw.asSubclass(Event.class);
            Listener listener=new Listener(){};
            getServer().getPluginManager().registerEvent(type,listener,EventPriority.MONITOR,(ignored,event)->{
                try {
                    Object vote=event.getClass().getMethod("getVote").invoke(event);
                    String nickname=(String)vote.getClass().getMethod("getUsername").invoke(vote);
                    String service=(String)vote.getClass().getMethod("getServiceName").invoke(vote);
                    String timestamp=(String)vote.getClass().getMethod("getTimeStamp").invoke(vote);
                    guard.queueServerVote(nickname,service,timestamp).exceptionally(failure->{
                        getLogger().severe("Голос VotifierPlus не сохранён в локальную очередь: "+failure.getClass().getSimpleName());return null;
                    });
                } catch(Exception failure) {getLogger().warning("Некорректное событие VotifierPlus: "+failure.getClass().getSimpleName());}
            },this,true);
            getLogger().info("VotifierPlus подключён: награды за голоса конкретного сервера активны.");
        } catch(Exception failure) {getLogger().severe("Не удалось подключить VotifierPlus: "+failure.getClass().getSimpleName());}
    }
    private void deliverServerVotes() {
        if(stopping||guard==null) return;
        try {
            for(var vote:guard.pendingServerVotes(100).get()) {
                if(stopping) return;
                try {guard.donations.recordServerVote(vote.nickname(),vote.service(),vote.timestamp());}
                catch(Exception unavailable) {getLogger().fine("Платёжный сервис недоступен; голос остаётся в локальной очереди.");return;}
                guard.serverVoteDelivered(vote.id()).get();
            }
            guard.donations.resolveKnownVotes(guard);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        catch(Exception failure) {getLogger().fine("Очередь голосов ожидает следующей попытки: "+failure.getClass().getSimpleName());}
    }
    private void ensureTelegramConfig() {
        for(String name:List.of("telegram.yml","telegramchat.yml","donations.yml","telegram-menu.yml")) {
            java.io.File file=new java.io.File(getDataFolder(),name);
            if(file.exists()) continue;
            saveResource(name,false);
            getLogger().info("Создан стандартный Telegram-конфиг: "+file.getAbsolutePath());
        }
    }
    private void applyRuntimeConfig() {
        debounceTicks=Math.max(1,Math.min(20,getConfig().getLong("update-delay-ticks",2)));
        playerLimit=0;
    }
    private String replaceTelegram() {
        if(telegram!=null) { telegram.close(); telegram=null; }
        if(telegramChat!=null) {telegramChat.close();telegramChat=null;}
        try {
            TelegramConfig telegramConfig=TelegramConfig.load(getDataFolder().toPath(),false);
            guard.configure(GuardConfig.load(getDataFolder().toPath()),telegramConfig.adminUserIds());
            if(telegramConfig.enabled()) {
                if(telegramConfig.token().matches("[0-9]+:[A-Za-z0-9_-]{20,}")) telegram=new TelegramBotService(telegramConfig,catalogue,storage,getLogger(),guard,new TelegramModeration(this,guard,searchCompass,childPlayers));
                else getLogger().warning("Каталожный Telegram-бот не запущен: заполните token в telegram.yml. Чат-бот с отдельным токеном может работать независимо.");
            }
            String chatStatus="чат выключен";
            try {
                TelegramChatConfig chatConfig=TelegramChatConfig.load(getDataFolder().toPath());
                if(chatConfig.enabled) {
                    telegramChat=new TelegramChatBridge(this,chatConfig,telegramConfig);
                    telegramChat.linkedAccounts(guard);
                    boolean shared=chatConfig.shared(telegramConfig);
                    if(shared) telegram.chatBridge(telegramChat);
                    telegramChat.start(shared);chatStatus="чат запущен"+(shared?" с общим токеном":" с отдельным токеном");
                }
            } catch(Exception failure) {
                if(telegramChat!=null) {telegramChat.close();telegramChat=null;}
                if(telegram!=null) telegram.chatBridge(null);
                chatStatus="чат не запущен: проверьте telegramchat.yml";
                getLogger().warning("Telegram-чат не запущен: "+failure.getClass().getSimpleName()+". Проверьте telegramchat.yml и telegramchat-lang.json.");
            }
            if(telegram!=null) telegram.start();
            return "Настройки применены: каталог "+(telegram==null?"выключен":"запущен")+", "+chatStatus+".";
        } catch(Exception e) {
            getLogger().severe("Telegram-бот не запущен: "+e.getMessage()+". Учёт хранилищ продолжает работать.");
            return "Настройки плагина применены, но Telegram-бот не запущен: "+e.getMessage();
        }
    }
    private void reloadPlugin(CommandSender sender) {
        reloadConfig(); applyRuntimeConfig();TelegramItemIcons.configure(getDataFolder().toPath(),getLogger());
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
    private static final Pattern SIGN_MARKER=Pattern.compile("(?i)\\[\\s*[^\\]\\s]?(?:vault|v)[^\\]\\s]?\\s*\\]");
    static boolean marker(String text) { return text!=null && SIGN_MARKER.matcher(text).find(); }
    static boolean marker(SignChangeEvent event) {
        for(int line=0;line<4;line++) if(marker(plain(event.line(line)))) return true;
        return false;
    }
    static void decorateOwner(Sign sign, String playerName) {
        for(Side side:Side.values()) {
            SignSide text=sign.getSide(side);
            if(text==null) continue;
            boolean found=false;
            for(int line=0;line<4;line++) if(marker(plain(text.line(line)))) {found=true;break;}
            if(!found) continue;
            text.line(0, Component.text("Собственность"));
            text.line(1, Component.text("игрока"));
            text.line(2, Component.text(playerName));
            text.line(3, Component.empty());
            sign.update(true, false);
            return;
        }
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
    static Block frameAnchor(ItemFrame frame) {
        return frame.getLocation().getBlock().getRelative(frame.getFacing().getOppositeFace());
    }
    private ItemFrame frameAt(BlockKey p,World w) {
        for(var entity:w.getChunkAt(p.x()>>4,p.z()>>4).getEntities())
            if(entity instanceof ItemFrame frame && key(frame.getLocation().getBlock()).equals(p)) return frame;
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
        Sign sign=block.getState() instanceof Sign s?s:null;
        ItemFrame frame=sign==null?frameAt(p,w):null;
        if(sign==null && frame==null) {catalogue.remove(p,v.generation(),System.currentTimeMillis());return;}
        ChestView view = readChest(sign!=null?attached(block):frameAnchor(frame));
        if (view == null) return;
        if (view.chests().isEmpty()) { catalogue.remove(p,v.generation(),System.currentTimeMillis()); return; }
        if (!catalogue.observe(p,v.generation(),view.chests(),view.items(),System.currentTimeMillis()))
            getLogger().warning("Объединены два зарегистрированных хранилища. Их учёт снят во избежание двойного подсчёта; заново напишите [vault] на одной табличке.");
        else if(sign!=null) decorateOwner(sign, v.playerName());
    }
    private void registerFrame(ItemFrame frame,Player player) {
        if(stopping || !frame.isValid() || frame.getItem().getType()!=Material.PAPER) return;
        BlockKey p=key(frame.getLocation().getBlock());
        if(catalogue.get(p)!=null) return; // The marker survives any later item change.
        if(!storage.ready()) {warn(player,"Каталог пока недоступен. Сообщите администратору: /vtrack status");return;}
        if(!player.hasPermission("vaulttracker.register")) {error(player,"Нет права vaulttracker.register.");return;}
        ChestView view=readChest(frameAnchor(frame));
        if(view==null || view.chests().isEmpty()) {warn(player,"Поместите рамку прямо на хранилище. Эндер-сундук не поддерживается.");return;}
        try {
            catalogue.register(p,player.getUniqueId(),player.getName(),view.chests(),view.items(),System.currentTimeMillis(),playerLimit);
            GameMessages.send(player,"Хранилище поставлено на учёт через рамку. Теперь предмет в рамке можно менять.",GameMessages.Tone.SUCCESS);
        } catch(IllegalArgumentException ex) {warn(player,ex.getMessage());}
    }
    private void scheduleFrameRegistration(ItemFrame frame,Player player) {
        frame.getScheduler().runDelayed(this,task->registerFrame(frame,player),()->{},1L);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void interactFrame(PlayerInteractEntityEvent e) {
        if(!(e.getRightClicked() instanceof ItemFrame frame)) return;
        // Only insertion into an empty, unclaimed frame can create a marker.
        // Rotating/replacing an existing item must never retry registration.
        if(!frame.getItem().getType().isAir()) return;
        if(catalogue.get(key(frame.getLocation().getBlock()))!=null) return;
        if(catalogue.claimedBy(key(frameAnchor(frame)))!=null) return;
        scheduleFrameRegistration(frame,e.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void placedFrame(HangingPlaceEvent e) {
        if(e.getEntity() instanceof ItemFrame frame && e.getPlayer()!=null) scheduleFrameRegistration(frame,e.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void brokenFrame(HangingBreakEvent e) {
        if(!(e.getEntity() instanceof ItemFrame frame)) return;
        BlockKey p=key(frame.getLocation().getBlock());Snapshot marker=catalogue.get(p);
        if(marker==null) return;
        Player breaker=e instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player?player:null;
        if(breaker!=null) guard.attribute(p,breaker.getUniqueId(),breaker.getName());
        catalogue.remove(p,marker.generation(),System.currentTimeMillis());
        if(breaker!=null && marker.owner().equals(breaker.getUniqueId()))
            GameMessages.send(breaker,"Хранилище снято с учёта.",GameMessages.Tone.SUCCESS);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void validateSign(SignChangeEvent e) {
        Snapshot existing = catalogue.get(key(e.getBlock()));
        if (existing != null && !existing.owner().equals(e.getPlayer().getUniqueId()) && !e.getPlayer().hasPermission("vaulttracker.admin")) {
            e.setCancelled(true); error(e.getPlayer(),"Эта табличка зарегистрирована другим игроком."); return;
        }
        if (existing != null) return;
        if (!marker(e)) return;
        if (!storage.ready()) { e.setCancelled(true); warn(e.getPlayer(),"Каталог пока недоступен. Сообщите администратору: /vtrack status"); return; }
        if (!e.getPlayer().hasPermission("vaulttracker.register")) { e.setCancelled(true); error(e.getPlayer(),"Нет права vaulttracker.register."); return; }
        ChestView view = readChest(attached(e.getBlock()));
        if (view == null || view.chests().isEmpty()) { e.setCancelled(true); warn(e.getPlayer(),"Поставьте табличку на хранилище или прикрепите сбоку. Эндер-сундук не поддерживается: его инвентарь принадлежит игроку, а не блоку."); return; }
        for (BlockKey chest : view.chests()) {
            BlockKey claimed = catalogue.claimedBy(chest);
            if (claimed != null && !claimed.equals(key(e.getBlock()))) {
                e.setCancelled(true); warn(e.getPlayer(),"Это хранилище уже зарегистрировано. Для двойного сундука нужна одна табличка."); return;
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void changedSign(SignChangeEvent e) {
        BlockKey p = key(e.getBlock());
        Snapshot existing = catalogue.get(p);
        // Text edits need neither a catalogue mutation nor an inventory check.
        if (existing != null || !marker(e)) return;
        if (!storage.ready() || !e.getPlayer().hasPermission("vaulttracker.register")) return;
        ChestView view = readChest(attached(e.getBlock()));
        if (view == null || view.chests().isEmpty()) return;
        try {
            catalogue.register(p,e.getPlayer().getUniqueId(),e.getPlayer().getName(),view.chests(),view.items(),System.currentTimeMillis(),playerLimit);
            // Decorate on the region scheduler after Minecraft applies the event's text.
            schedule(p);
            GameMessages.send(e.getPlayer(),"Хранилище поставлено на учёт (" + (view.chests().size()==2 ? "двойной сундук" : "один блок") + "). Сохранение выполняется в фоне.",GameMessages.Tone.SUCCESS);
        } catch (IllegalArgumentException ex) { warn(e.getPlayer(),ex.getMessage()); }
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
    // Explosion block lists are candidates; other protection plugins may keep those blocks intact.
    // Recheck the actual sign/container after the event instead of unregistering immediately.
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void explode(EntityExplodeEvent e) { e.blockList().forEach(this::nearby); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void explode(BlockExplodeEvent e) { e.blockList().forEach(this::nearby); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void placed(BlockPlaceEvent e) { nearby(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void physics(BlockPhysicsEvent e) { if (catalogue.get(key(e.getBlock())) != null) schedule(key(e.getBlock())); }
    @EventHandler(priority=EventPriority.MONITOR) public void chunkLoad(ChunkLoadEvent e) {
        if (!storage.ready()) return;
        for (Snapshot v : catalogue.inChunk(new BlockKey.ChunkKey(e.getWorld().getUID(),e.getChunk().getX(),e.getChunk().getZ()))) schedule(v.sign());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void worldLoad(WorldLoadEvent e) {guard.world(e.getWorld().getUID(),e.getWorld().getName());}
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void chunkUnload(ChunkUnloadEvent e) {
        for (Snapshot v : catalogue.inChunk(new BlockKey.ChunkKey(e.getWorld().getUID(),e.getChunk().getX(),e.getChunk().getZ()))) {
            // Entity removal during unload is not a broken frame. The next load rechecks it.
            if(!e.getWorld().isChunkLoaded(v.sign().x()>>4,v.sign().z()>>4)) continue;
            if(location(v.sign(),e.getWorld()).getBlock().getState() instanceof Sign) refresh(v.sign(),e.getWorld());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        guard.presence(e.getPlayer().getUniqueId(),e.getPlayer().getName(),true);
        retryBastion(e.getPlayer().getUniqueId());
        if (storage.ready()) catalogue.rename(e.getPlayer().getUniqueId(),e.getPlayer().getName());
        storage.retryLegacy(snapshots->{
            var added=catalogue.restoreMissing(snapshots);guard.restore(added);
            for(Snapshot value:added) {
                String current=guard.knownPlayerName(value.owner());
                if(current!=null) catalogue.rename(value.owner(),current);
            }
        });
        e.getPlayer().getScheduler().runDelayed(this,task->snapshotStatistics(e.getPlayer()),()->{},20L);
    }
    private void retryBastion(UUID uuid) {
        guard.bastionPending(uuid).thenAccept(pending->{
            if(!pending || stopping) return;
            bastionTrust.change(uuid,true).thenAccept(outcome->{
                if(outcome.success()) guard.bastionGranted(uuid);
                else getLogger().warning("Ожидает доверия Bastion после привязки: "+uuid+". "+outcome.message());
            }).exceptionally(error->{getLogger().warning("Не удалось выдать доверие Bastion после привязки: "+uuid);return null;});
        }).exceptionally(error->{getLogger().warning("Не удалось проверить ожидающее доверие Bastion: "+uuid);return null;});
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        snapshotStatistics(e.getPlayer());guard.presence(e.getPlayer().getUniqueId(),e.getPlayer().getName(),false);
    }
    private void snapshotStatistics(Player player) {
        if(stopping) return;
        try {guard.mergeStatistics(player.getUniqueId(),PlayerStatistics.capture(player));}
        catch(RuntimeException failure) {getLogger().warning("Не удалось прочитать статистику игрока: "+failure.getClass().getSimpleName());}
    }
    private static void tell(CommandSender sender,String message) { GameMessages.send(sender,message,GameMessages.Tone.INFO); }
    private static void warn(CommandSender sender,String message) { GameMessages.send(sender,message,GameMessages.Tone.WARNING); }
    private static void error(CommandSender sender,String message) { GameMessages.send(sender,message,GameMessages.Tone.ERROR); }
    private boolean findOwn(CommandSender sender,String[] args) {
        if(!(sender instanceof Player player)) {warn(sender,"Поиск сундуков доступен в игре.");return true;}
        if(!player.hasPermission("vaulttracker.search")) {error(sender,"Нет права vaulttracker.search.");return true;}
        if(!storage.ready()) {warn(sender,"Каталог загружается.");return true;}
        if(args.length==0) {tell(sender,"/find предмет [страница] — свои сундуки; /find ник [предмет] [страница] — доступные сундуки друга.");return true;}
        int requested=1,end=args.length;
        if(args.length>1&&args[args.length-1].matches("[0-9]+")) {try {requested=Integer.parseInt(args[--end]);} catch(NumberFormatException e) {warn(sender,"Слишком большой номер страницы.");return true;}}
        String query=String.join(" ",Arrays.copyOf(args,end));if(query.isBlank()||query.length()>64) {warn(sender,"Введите название предмета до 64 символов.");return true;}
        String navigationQuery=query;UUID searchOwner=player.getUniqueId();String title="Ваши сундуки";
        var owners=catalogue.ownerItems(args[0]);
        if(owners.size()>1) {warn(sender,"Найдено несколько игроков с этим ником. Обратитесь к администратору.");return true;}
        if(owners.size()==1) {
            var owner=owners.getFirst();
            if(!guard.canSearch(player.getUniqueId(),owner.owner())) {error(sender,"Игрок не открыл вам доступ к поиску в хранилищах.");return true;}
            searchOwner=owner.owner();title="Сундуки "+owner.name();query=end==1?"*":String.join(" ",Arrays.copyOfRange(args,1,end));
        }
        var loc=player.getLocation();var rows=OwnResourceSearch.find(catalogue,searchOwner,query,new BlockKey(loc.getWorld().getUID(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()));
        if(guard!=null && end==args.length && !query.equals("*")) guard.recordItemSearch(ItemSorting.queryItem(query));
        if(end==args.length) searchCompass.point(player,rows);
        int pages=Math.max(1,(rows.size()+19)/20),page=Math.max(1,Math.min(requested,pages));
        tell(sender,title+" · "+(query.equals("*")?"Все ресурсы":query)+" · "+page+"/"+pages+". Ближайшие в вашем мире сначала.");
        if(rows.isEmpty()) warn(sender,"Не найдено в зарегистрированных хранилищах.");
        for(var row:rows.subList((page-1)*20,Math.min(page*20,rows.size()))) {
            BlockKey chest=row.chest();World world=getServer().getWorld(chest.world());
            Component coordinates=Component.text(chest.x()+" "+chest.y()+" "+chest.z(),net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/vtrack compass "+chest.world()+" "+chest.x()+" "+chest.y()+" "+chest.z()))
                .hoverEvent(Component.text("Направить компас на этот сундук"));
            GameMessages.send(player,Component.text(RussianItems.name(row.material())+" ×"+row.amount()+" — "+(world==null?chest.world():world.getName())+" · ")
                .append(coordinates).append(Component.text(Double.isFinite(row.distance())?" · "+Math.round(row.distance())+" м":"")),GameMessages.Tone.INFO);
        }
        Component navigation=Component.text("◀ Назад");
        if(page>1) navigation=navigation.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/find "+navigationQuery+" "+(page-1)));
        Component next=Component.text("Вперёд ▶");if(page<pages) next=next.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/find "+navigationQuery+" "+(page+1)));
        GameMessages.send(player,navigation.append(Component.text("   "+page+"/"+pages+"   ")).append(next),GameMessages.Tone.INFO);return true;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(command.getName().equalsIgnoreCase("unlink")) {
            if(!(sender instanceof Player player)) {warn(sender,"Отвязка выполняется только игроком в Minecraft.");return true;}
            if(!player.hasPermission("vaulttracker.link")) {error(sender,"Нет права vaulttracker.link.");return true;}
            if(args.length!=0) {warn(sender,"Используйте /unlink без аргументов.");return true;}
            guard.unlink(player.getUniqueId()).whenComplete((message,failure)->{
                if(stopping) return;
                player.getScheduler().run(this,task->{
                    if(failure==null) GameMessages.send(player,message,GameMessages.Tone.INFO);
                    else error(player,"Не удалось отвязать Telegram. Сообщите администратору.");
                },null);
            });
            return true;
        }
        if(command.getName().equalsIgnoreCase("tg")) {
            if(!(sender instanceof Player player)) {warn(sender,"Команда доступна только игроку.");return true;}
            if(!player.hasPermission("vaulttracker.message")) {error(sender,"Нет права vaulttracker.message.");return true;}
            if(args.length<2) {warn(sender,"Используйте /tg <ник> <текст>.");return true;}
            String text=String.join(" ",Arrays.copyOfRange(args,1,args.length));
            directMessages.fromGame(player.getUniqueId(),player.getName(),args[0],text).whenComplete((result,failure)->{
                if(stopping) return;
                player.getScheduler().run(this,task->{
                    if(failure==null) {
                        player.sendMessage(PlayerDirectMessages.message(args[0],text,true));
                    }
                    else warn(player,Objects.toString(failure.getCause()==null?failure.getMessage():failure.getCause().getMessage(),"Не удалось отправить сообщение."));
                },null);
            });return true;
        }
        if(command.getName().equalsIgnoreCase("find")) return findOwn(sender,args);
        if (command.getName().equalsIgnoreCase("topitem") || command.getName().equalsIgnoreCase("itemtop")) {
            return new TopItemCommand(catalogue,storage::ready,id-> {
                Material material=Material.getMaterial(id);return material!=null && material.isItem();
            },item->{if(guard!=null) guard.recordItemSearch(item);}).execute(sender,args);
        }
        String action=args.length==0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if(action.equals("compass")) {
            if(!(sender instanceof Player player)) {warn(sender,"Выберите сундук в игре.");return true;}
            if(!storage.ready()) {warn(sender,"Каталог загружается.");return true;}
            try {
                if(args.length!=5) throw new IllegalArgumentException();
                tell(sender,searchCompass.select(player,new BlockKey(UUID.fromString(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]))));
            } catch(IllegalArgumentException invalid) {warn(sender,"Повторите поиск и нажмите на координаты сундука.");}
        } else if(action.equals("link")) {
            if(!(sender instanceof Player player)) {warn(sender,"Привязка выполняется только игроком в Minecraft.");return true;}
            if(!player.hasPermission("vaulttracker.link")) {error(sender,"Нет права vaulttracker.link.");return true;}
            if(args.length!=2) {warn(sender,"Получите готовую команду в личном кабинете Telegram-бота.");return true;}
            guard.linkOutcome(player.getUniqueId(),player.getName(),args[1]).thenCompose(linked->{
                if(!linked.linked()) return java.util.concurrent.CompletableFuture.completedFuture(linked.message());
                return bastionTrust.change(player.getUniqueId(),true)
                    .exceptionally(error->new BastionTrust.Outcome(false,"Команда Bastion временно недоступна."))
                    .thenCompose(outcome->
                    outcome.success()?guard.bastionGranted(player.getUniqueId()).thenApply(ignored->linked.message()+" "+outcome.message())
                        :java.util.concurrent.CompletableFuture.completedFuture(linked.message()+" Доверие Bastion пока не выдано: "+outcome.message()+" Повторим при следующем входе."));
            }).whenComplete((message,failure)-> {
                if(stopping) return;
                player.getScheduler().run(this,task->{if(failure==null) GameMessages.send(player,message,GameMessages.Tone.INFO);
                    else error(player,"Не удалось сохранить привязку. Сообщите администратору.");},null);
            });
        } else if (action.equals("status")) {
            if (!sender.hasPermission("vaulttracker.admin")) { error(sender,"Нет права vaulttracker.admin."); return true; }
            tell(sender,storage.status()); tell(sender,"Хранилищ: " + catalogue.size());
        } else if (action.equals("rescan")) {
            if (!sender.hasPermission("vaulttracker.admin")) { error(sender,"Нет права vaulttracker.admin."); return true; }
            catalogue.all().forEach(v -> schedule(v.sign())); tell(sender,"Запущена сверка загруженных хранилищ.");
        } else if (action.equals("reload")) {
            if (!sender.hasPermission("vaulttracker.admin")) { error(sender,"Нет права vaulttracker.admin."); return true; }
            reloadPlugin(sender);
        } else if (action.equals("find") && args.length==2) {
            if (!sender.hasPermission("vaulttracker.search")) { error(sender,"Нет права vaulttracker.search."); return true; }
            if (!storage.ready()) { warn(sender,"Каталог загружается. Повторите поиск чуть позже."); return true; }
            String material=args[1].toUpperCase(Locale.ROOT).replace("MINECRAFT:","");
            if (material.equals("DAIMOND")) material = "DIAMOND";
            Material type=Material.getMaterial(material);
            if (type==null || !type.isItem()) { warn(sender,"Неизвестный предмет. Пример: /vtrack find DIAMOND"); return true; }
            List<String> found=catalogue.find(material,20);
            tell(sender,material+" — последние известные остатки:");
            if (found.isEmpty()) warn(sender,"Не найдено."); else found.forEach(line -> tell(sender,line));
        } else if (action.equals("mine") && sender instanceof Player player) {
            List<Snapshot> mine=catalogue.all().stream().filter(v->v.owner().equals(player.getUniqueId())).toList();
            tell(sender,"Ваших хранилищ: " + mine.size() + ". Первые 10:");
            mine.stream().limit(10).forEach(v->tell(sender,v.sign().x()+" "+v.sign().y()+" "+v.sign().z()+" — "+v.chests().size()+" блоков"));
        } else {
            tell(sender,"Напишите [vault] или [v] на табличке либо вставьте бумагу в рамку на хранилище.");
            tell(sender,"/vtrack mine — ваши хранилища; /topitem diamond или /vtrack find DIAMOND — у кого есть алмазы. Поиск доступен постоянно.");
            tell(sender,"/vtrack link <код> — привязать персонажа к Telegram. Получите код кнопкой в личном чате бота.");
            tell(sender,"/unlink — отвязать своего персонажа от Telegram.");
            tell(sender,"/tg <ник> <текст> — личное сообщение игроку, привязанному к Telegram.");
            if (sender.hasPermission("vaulttracker.admin")) tell(sender,"/vtrack status | /vtrack rescan | /vtrack reload — управление каталогом.");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(command.getName().equalsIgnoreCase("tg")) {
            if(args.length!=1 || !sender.hasPermission("vaulttracker.message")) return List.of();
            String prefix=args[0].toLowerCase(Locale.ROOT);
            return guard.linkedNames().stream().filter(name->name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER).limit(40).toList();
        }
        if(command.getName().equalsIgnoreCase("find")) return sender.hasPermission("vaulttracker.search")?FindTabCompletion.complete(catalogue,args):List.of();
        if (command.getName().equalsIgnoreCase("topitem")) {
            return sender.hasPermission("vaulttracker.search")?TopItemTabCompletion.complete(catalogue,args):List.of();
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
        voteDeliveries.shutdownNow();
        try {voteDeliveries.awaitTermination(5,TimeUnit.SECONDS);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        synchronized(telegramLock) { if(telegram!=null) { telegram.close(); telegram=null; } if(telegramChat!=null) {telegramChat.shutdown();telegramChat=null;} }
        if (storage != null) storage.close();
        if (guard != null) guard.close();
    }
}
