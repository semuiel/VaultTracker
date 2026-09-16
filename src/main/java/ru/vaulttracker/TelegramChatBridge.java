package ru.vaulttracker;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** No network I/O on Minecraft region threads. Shared tokens have exactly one polling owner. */
final class TelegramChatBridge implements Listener,AutoCloseable {
    private sealed interface Job permits Outbound,ListSend,ListEdit,ListDelete {}
    private record Outbound(TelegramChatConfig.Target target,String html,boolean silent,boolean merge,long created) implements Job {}
    private record ListSend(String token,TelegramChatConfig.Target target,List<String> pages,boolean silent,long created) implements Job {}
    private record ListEdit(String token,TelegramChatConfig.Target target,int messageId,int page,String callbackId) implements Job {}
    private record ListDelete(String token,TelegramChatConfig.Target target,int messageId) implements Job {}
    private record ListSession(TelegramChatConfig.Target target,List<String> pages,long expires) {}
    private final JavaPlugin plugin;
    private final TelegramChatConfig config;
    private final TelegramConfig transport;
    private final TelegramApi api;
    private final TelegramChatText translations;
    private final Map<UUID,TelegramChatFormat.PlayerRow> players=new ConcurrentHashMap<>();
    private final LinkedBlockingDeque<Job> outgoing=new LinkedBlockingDeque<>(1000);
    private final Map<String,ListSession> lists=new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiry=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("VaultTracker-chat-expiry").factory());
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final Thread sender,receiver;
    private volatile String username;
    private volatile long lastQueueWarning;
    private GuardService guard;
    private volatile LinkedChatIdentity identity;
    private volatile long lastIdentityWarning;
    void linkedAccounts(GuardService value) {guard=value;}
    TelegramChatBridge(JavaPlugin plugin,TelegramChatConfig config,TelegramConfig catalogue) throws Exception {
        this.plugin=plugin;this.config=config;transport=config.transport(catalogue);api=new TelegramApi(transport);
        translations=new TelegramChatText(plugin.getDataFolder().toPath());
        sender=Thread.ofVirtual().name("VaultTracker-chat-send").unstarted(this::sendLoop);
        receiver=Thread.ofVirtual().name("VaultTracker-chat-poll").unstarted(this::pollLoop);
    }
    void start(boolean shared) {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getGlobalRegionScheduler().run(plugin,t->{
            if(stopping.get()) return;
            var flexity=plugin.getServer().getPluginManager().getPlugin("flexity");
            if(flexity!=null&&flexity.isEnabled()) try {identity=new LinkedChatIdentity(flexity);} catch(Exception e) {plugin.getLogger().warning("Оформление Flexity недоступно: используется игровой ник с [TG].");}
            for(Player p:plugin.getServer().getOnlinePlayers()) p.getScheduler().run(plugin,task->{if(!stopping.get()) remember(p);},null);
        });
        sender.start();if(!shared) receiver.start();
    }
    void username(String value) {username=value;}
    private void remember(Player player) {
        String dimension=switch(player.getWorld().getEnvironment()) {case NORMAL->"overworld";case NETHER->"nether";case THE_END->"end";default->"other";};
        players.put(player.getUniqueId(),new TelegramChatFormat.PlayerRow(player.getName(),limit(plain(player.displayName()),100),player.getWorld().getName(),dimension));
    }
    static String plain(Component c) {return c==null?"":PlainTextComponentSerializer.plainText().serialize(c);}
    static String limit(String value,int length) {return value.length()<=length?value:value.substring(0,length)+"…";}
    private String name(Player p) {return config.flag("messages.useRealUsername",false)?p.getName():limit(plain(p.displayName()),100);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void chat(AsyncChatEvent event) {
        if(!config.flag("chat.minecraftToTelegram",true)) return;
        String text=TelegramChatFormat.chatText(config,plain(event.message()));if(text==null || text.isBlank()) return;
        enqueue(config.chat,TelegramChatFormat.template(config.format("minecraftChat","<b>[{username}]</b> {text}"),Map.of("username",TelegramChatFormat.escape(name(event.getPlayer())),"text",TelegramChatFormat.escape(limit(text,1000)))),config.flag("messages.silent",false),true);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void joined(PlayerJoinEvent event) {
        Player p=event.getPlayer();remember(p);
        if(config.flag("events.join",false) && (!config.flag("events.firstJoinOnly",false)||!p.hasPlayedBefore())) playerEvent(p.hasPlayedBefore()?"join":"firstJoin",p,"");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void left(PlayerQuitEvent event) {players.remove(event.getPlayer().getUniqueId());if(config.flag("events.leave",false)) playerEvent("leave",event.getPlayer(),"");}
    @EventHandler(priority=EventPriority.MONITOR) public void world(PlayerChangedWorldEvent event) {remember(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void died(PlayerDeathEvent event) {if(config.flag("events.death",false) && event.deathMessage()!=null) playerEvent("death",event.getPlayer(),translations.plain(event.deathMessage()));}
    private void playerEvent(String type,Player player,String text) {
        enqueue(config.chat,TelegramChatFormat.template(config.format(type,"{username} {text}"),Map.of("username",TelegramChatFormat.escape(name(player)),"text",TelegramChatFormat.escape(limit(text,1000)))),config.flag("events.silent",true),false);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void advancement(PlayerAdvancementDoneEvent event) {
        if(!config.advancementEnabled()) return;var display=event.getAdvancement().getDisplay();if(display==null || !display.doesAnnounceToChat()) return;
        String type=display.frame().name().toLowerCase(Locale.ROOT);if(!config.flag("advancements."+type,true)) return;
        String key="advancement"+Character.toUpperCase(type.charAt(0))+type.substring(1);
        String description=config.flag("advancements.showDescription",false)?limit(translations.plain(display.description()),1000):"";
        String text=TelegramChatFormat.template(config.format(key,"🏆 {username}: {title}\n{description}"),Map.of("username",TelegramChatFormat.escape(name(event.getPlayer())),"title",TelegramChatFormat.escape(limit(translations.plain(display.title()),300)),"description",TelegramChatFormat.escape(description)));
        enqueue(config.advancements,text.stripTrailing(),config.flag("advancements.silent",false),false);
    }
    void serverEvent(boolean start) {String key=start?"serverStart":"serverStop";if(config.flag("events."+key,false)) enqueue(config.chat,config.format(key,start?"✅ Сервер запущен":"❌ Сервер остановлен"),config.flag("events.silent",true),false);}
    void shutdown() {
        if(config.flag("events.serverStop",false)) {
            // Shutdown delivery is best effort and cannot hold server shutdown indefinitely.
            var sent=CompletableFuture.runAsync(()->{try {api.sendHtml(config.chat.chatId(),config.chat.topicId(),config.format("serverStop","❌ Сервер остановлен!"),config.flag("events.silent",true));} catch(Exception ignored) {}});
            try {sent.get(1500,TimeUnit.MILLISECONDS);} catch(Exception ignored) {sent.cancel(true);}
        }
        close();
    }
    /** true only when this bridge owns the message; catalogue commands can pass through. */
    boolean consume(TelegramApi.Incoming update) {
        if(stopping.get() || update.privateChat() || update.senderBot()) return false;
        if(update.callback()) return consumeListCallback(update);
        if(update.text()==null || !config.listTarget(update)) return false;
        String text=update.text().trim();String first=text.split("\\s+",2)[0];
        if(first.startsWith("/")) {
            String[] command=first.split("@",2);
            if(!command[0].equalsIgnoreCase("/list") || (command.length==2 && (username==null || !command[1].equalsIgnoreCase(username)))) return false;
            if(config.flag("list.enabled",true)) enqueueList(new TelegramChatConfig.Target(update.chatId(),update.topicId()),TelegramChatFormat.playerList(config,players.values()));
            return true;
        }
        if(!config.chat.matches(update)) return false;
        if(!config.flag("chat.telegramToMinecraft",true) || (update.media()&&!config.flag("messages.mediaLabels",true))) return true;
        String senderName=update.profile()==null||update.profile().isBlank()?Long.toString(update.userId()):update.profile();
        Component message=incomingMessage(update.userId(),senderName,limit(text,1500));
        plugin.getServer().getGlobalRegionScheduler().run(plugin,task->{
            if(stopping.get()) return;
            for(Player player:plugin.getServer().getOnlinePlayers()) player.getScheduler().run(plugin,t->{if(!stopping.get()) player.sendMessage(message);},null);
            plugin.getServer().getConsoleSender().sendMessage(message);
        });
        return true;
    }
    Component incomingMessage(long user,String sender,String text) {
        if(guard!=null&&config.flag("messages.linkedPlayerIdentity",true)) try {
            var account=guard.account(user).get(5,TimeUnit.SECONDS);
            if(account!=null) {
                try {if(identity!=null) return identity.render(account,text);} catch(Exception e) {identityWarning();}
                return LinkedChatIdentity.fallback(account.name(),text);
            }
        } catch(Exception e) {identityWarning();}
        return MiniMessage.miniMessage().deserialize(config.format("telegramChat","<aqua>[TG] {sender}</aqua> {text}").replace("{sender}","<bridge_sender>").replace("{text}","<bridge_text>"),Placeholder.unparsed("bridge_sender",limit(sender,120)),Placeholder.unparsed("bridge_text",text));
    }
    private void identityWarning() {
        long now=System.currentTimeMillis();if(now-lastIdentityWarning>60000) {lastIdentityWarning=now;plugin.getLogger().warning("Не удалось получить оформление привязанного персонажа; используется запасное оформление [TG].");}
    }
    private boolean consumeListCallback(TelegramApi.Incoming update) {
        String data=update.callbackData();if(data==null||!data.startsWith("vcl:")) return false;
        String[] parts=data.split(":",3);if(parts.length!=3||!config.listTarget(update)) return false;
        int page;try {page=Integer.parseInt(parts[2]);} catch(NumberFormatException ignored) {return false;}
        outgoing.offer(new ListEdit(parts[1],new TelegramChatConfig.Target(update.chatId(),update.topicId()),update.messageId(),page,update.callbackId()));
        return true;
    }
    private void enqueueList(TelegramChatConfig.Target target,List<String> pages) {
        String token=UUID.randomUUID().toString().replace("-","").substring(0,16);long now=System.currentTimeMillis();
        lists.put(token,new ListSession(target,pages,now+config.listLifetimeSeconds()*1000L));
        if(!outgoing.offer(new ListSend(token,target,pages,config.flag("list.silent",false),now))) lists.remove(token);
    }
    private static List<TelegramCommands.Button> listButtons(String token,int page,int pages) {
        if(pages<=1) return List.of();List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(new TelegramCommands.Button("⬅️", "vcl:"+token+":"+(page-1),0));
        buttons.add(new TelegramCommands.Button((page+1)+"/"+pages,"vcl:"+token+":"+page,0));
        if(page+1<pages) buttons.add(new TelegramCommands.Button("➡️", "vcl:"+token+":"+(page+1),0));
        return List.copyOf(buttons);
    }
    private void enqueue(TelegramChatConfig.Target target,String html,boolean silent,boolean merge) {
        if(stopping.get()) return;
        if(!outgoing.offer(new Outbound(target,html,silent,merge,System.currentTimeMillis())) && System.currentTimeMillis()-lastQueueWarning>60000) {lastQueueWarning=System.currentTimeMillis();plugin.getLogger().warning("Очередь Telegram-чата заполнена: новое сообщение пропущено. Игра продолжает работать.");}
    }
    private void sendLoop() {
        while(!stopping.get()) {
            try {
                Job job=outgoing.poll(1,TimeUnit.SECONDS);if(job==null) continue;
                if(job instanceof ListSend item) {sendList(item);continue;}
                if(job instanceof ListEdit item) {editList(item);continue;}
                if(job instanceof ListDelete item) {deleteList(item);continue;}
                Outbound item=(Outbound)job;
                if(System.currentTimeMillis()-item.created()>600000) continue;
                if(item.merge() && config.mergeSeconds()>0) {
                    long remaining=item.created()+config.mergeSeconds()*1000L-System.currentTimeMillis();if(remaining>0) Thread.sleep(remaining);
                    StringBuilder html=new StringBuilder(item.html());
                    while(true) {Job nextJob=outgoing.peek();if(!(nextJob instanceof Outbound next)||!next.merge()||!next.target().equals(item.target())||next.created()>item.created()+config.mergeSeconds()*1000L||html.length()+next.html().length()+1>3500) break;outgoing.poll();html.append('\n').append(next.html());}
                    item=new Outbound(item.target(),html.toString(),item.silent(),false,item.created());
                }
                try {api.sendHtml(item.target().chatId(),item.target().topicId(),item.html(),item.silent());}
                catch(Exception failure) {
                    if(TelegramApi.permanentFailure(failure)) {plugin.getLogger().warning("Telegram-чат отклонил сообщение: "+api.safe(failure)+". Проверьте права бота, тему и HTML-шаблон.");continue;}
                    plugin.getLogger().warning("Telegram-чат: доставка отложена: "+api.safe(failure));
                    outgoing.offerFirst(item);Thread.sleep(Math.max(1000,Math.min(30000,transport.retry().initialDelay())));
                }
            } catch(InterruptedException stop) {Thread.currentThread().interrupt();break;}
            catch(Exception failure) {plugin.getLogger().warning("Ошибка Telegram-чата: "+api.safe(failure));}
        }
    }
    private void sendList(ListSend item) throws Exception {
        if(System.currentTimeMillis()-item.created()>600000) {lists.remove(item.token());return;}
        int messageId=api.sendHtml(item.target().chatId(),item.target().topicId(),item.pages().getFirst(),item.silent(),listButtons(item.token(),0,item.pages().size()));
        expiry.schedule(()->outgoing.offer(new ListDelete(item.token(),item.target(),messageId)),config.listLifetimeSeconds(),TimeUnit.SECONDS);
    }
    private void editList(ListEdit item) throws Exception {
        ListSession session=lists.get(item.token());
        if(session==null||System.currentTimeMillis()>=session.expires()||!session.target().equals(item.target())) {api.answerCallback(item.callbackId(),"Список уже удалён или устарел.",false);return;}
        int page=Math.max(0,Math.min(item.page(),session.pages().size()-1));
        api.editHtml(item.target().chatId(),item.messageId(),session.pages().get(page),listButtons(item.token(),page,session.pages().size()));
        api.answerCallback(item.callbackId(),"",false);
    }
    private void deleteList(ListDelete item) {
        if(lists.remove(item.token())==null) return;
        try {api.delete(item.target().chatId(),item.messageId());} catch(Exception ignored) {}
    }
    private void pollLoop() {
        long offset=TelegramApi.loadOffset(transport.offsetFile());int failures=0;boolean initialized=false;
        while(!stopping.get()) try {
            if(!initialized) {username=api.verify();api.registerChatCommands(false);initialized=true;plugin.getLogger().info("Telegram-чат @"+username+" подключён.");}
            for(var update:api.updates(offset)) {
                offset=Math.max(offset,update.updateId()+1);TelegramApi.saveOffset(transport.offsetFile(),offset);
                try {consume(update);} catch(Exception failure) {plugin.getLogger().warning("Сообщение Telegram-чата пропущено: "+api.safe(failure));}
            }
            failures=0;
        } catch(Exception failure) {
            if(stopping.get()||Thread.currentThread().isInterrupted()) break;
            plugin.getLogger().warning("Telegram-чат недоступен: "+api.safe(failure));
            long delay=Math.min(transport.retry().maxDelay(),transport.retry().initialDelay()*(1L<<Math.min(20,failures++)));
            try {Thread.sleep(delay);} catch(InterruptedException stop) {Thread.currentThread().interrupt();break;}
        }
    }
    @Override public void close() {
        if(!stopping.compareAndSet(false,true)) return;
        HandlerList.unregisterAll(this);receiver.interrupt();sender.interrupt();expiry.shutdownNow();api.close();players.clear();lists.clear();outgoing.clear();
        try {receiver.join(2000);sender.join(2000);} catch(InterruptedException stop) {Thread.currentThread().interrupt();}
    }
}
