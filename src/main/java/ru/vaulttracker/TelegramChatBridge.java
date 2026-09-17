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
    private sealed interface Job permits Outbound,ListSend,ListEdit {}
    private record Outbound(TelegramChatConfig.Target target,String html,boolean silent,boolean merge,long created) implements Job {}
    private record ListSend(String token,TelegramChatConfig.Target target,List<String> pages,boolean silent,long created) implements Job {}
    private record ListEdit(String token,TelegramChatConfig.Target target,int messageId,int page,String callbackId,List<String> pages) implements Job {}
    private record ListSession(TelegramChatConfig.Target target,int messageId,long expires) {}
    private final JavaPlugin plugin;
    private final TelegramChatConfig config;
    private final TelegramConfig transport;
    private final TelegramApi api;
    private final TelegramChatText translations;
    private final LinkedBlockingDeque<Job> outgoing=new LinkedBlockingDeque<>(1000);
    private final Map<String,ListSession> lists=new ConcurrentHashMap<>();
    private final TelegramListExpiry expiry;
    private final TelegramReports reports;
    private final ExecutorService commandDeletion=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),Thread.ofVirtual().name("VaultTracker-chat-command-delete").factory());
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final Thread sender,receiver;
    private volatile String username;
    private volatile long lastQueueWarning;
    private volatile long lastCommandDeleteWarning;
    private GuardService guard;
    private volatile LinkedChatIdentity identity;
    private volatile long lastIdentityWarning;
    void linkedAccounts(GuardService value) {guard=value;}
    TelegramChatBridge(JavaPlugin plugin,TelegramChatConfig config,TelegramConfig catalogue) throws Exception {
        this.plugin=plugin;this.config=config;transport=config.transport(catalogue);api=new TelegramApi(transport);
        translations=new TelegramChatText(plugin.getDataFolder().toPath());
        expiry=new TelegramListExpiry(transport.offsetFile().resolveSibling("telegramchat-"+config.token.split(":",2)[0]+"-lists.properties"),api,plugin.getLogger());
        reports=config.flag("reports.enabled",false)?new TelegramReports(plugin.getDataFolder().toPath(),config,api,expiry,plugin.getLogger()):null;
        sender=Thread.ofVirtual().name("VaultTracker-chat-send").unstarted(this::sendLoop);
        receiver=Thread.ofVirtual().name("VaultTracker-chat-poll").unstarted(this::pollLoop);
    }
    void start(boolean shared) {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getGlobalRegionScheduler().run(plugin,t->{
            if(stopping.get()) return;
            var flexity=plugin.getServer().getPluginManager().getPlugin("flexity");
            if(flexity!=null&&flexity.isEnabled()) try {identity=new LinkedChatIdentity(flexity);} catch(Exception e) {plugin.getLogger().warning("Оформление Flexity недоступно: используется игровой ник с [TG].");}
        });
        expiry.start();sender.start();if(!shared) receiver.start();
    }
    void username(String value) {username=value;}
    boolean reportsEnabled() {return reports!=null;}
    private TelegramChatFormat.PlayerRow playerRow(Player player) {
        String dimension=switch(player.getWorld().getEnvironment()) {case NORMAL->"overworld";case NETHER->"nether";case THE_END->"end";default->"other";};
        return new TelegramChatFormat.PlayerRow(player.getName(),limit(plain(player.displayName()),100),player.getWorld().getName(),dimension,player.getPing());
    }
    /** Read on each player's entity thread, including after cross-region teleports on Folia. */
    private CompletableFuture<List<String>> currentPlayerPages() {
        var result=new CompletableFuture<List<String>>();
        plugin.getServer().getGlobalRegionScheduler().run(plugin,task->{
            if(stopping.get()) {result.cancel(false);return;}
            List<CompletableFuture<TelegramChatFormat.PlayerRow>> rows=new ArrayList<>();
            for(Player p:plugin.getServer().getOnlinePlayers()) {
                var row=new CompletableFuture<TelegramChatFormat.PlayerRow>();rows.add(row);
                try {
                    var scheduled=p.getScheduler().run(plugin,t->{
                        try {row.complete(!stopping.get()&&p.isOnline()?playerRow(p):null);}
                        catch(Exception failure) {row.complete(null);}
                    },()->row.complete(null));
                    if(scheduled==null) row.complete(null);
                } catch(Exception retired) {row.complete(null);}
                row.completeOnTimeout(null,5,TimeUnit.SECONDS);
            }
            CompletableFuture.allOf(rows.toArray(CompletableFuture[]::new)).thenRun(()->
                result.complete(TelegramChatFormat.playerList(config,rows.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList())));
        });
        return result;
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
        Player p=event.getPlayer();
        if(config.flag("events.join",false) && (!config.flag("events.firstJoinOnly",false)||!p.hasPlayedBefore())) playerEvent(p.hasPlayedBefore()?"join":"firstJoin",p,"");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void left(PlayerQuitEvent event) {if(config.flag("events.leave",false)) playerEvent("leave",event.getPlayer(),"");}
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
        if(update.callback()) {if(reports!=null&&reports.consume(update,username)) return true;return consumeListCallback(update);}
        if(update.text()==null) return false;
        String text=update.text().trim();String first=text.split("\\s+",2)[0];
        boolean group=config.chat.chatId()==update.chatId() || (config.advancementEnabled()&&config.advancements.chatId()==update.chatId());
        if(group && first.startsWith("/") && update.messageId()>0) deleteCommand(update);
        if(reports!=null&&reports.consume(update,username)) return true;
        if(!config.listTarget(update)) return false;
        if(first.startsWith("/")) {
            String[] command=first.split("@",2);
            if(!command[0].equalsIgnoreCase("/list") || (command.length==2 && (username==null || !command[1].equalsIgnoreCase(username)))) return false;
            if(config.flag("list.enabled",true)) currentPlayerPages().thenAccept(pages->enqueueList(new TelegramChatConfig.Target(update.chatId(),update.topicId()),pages));
            return true;
        }
        if(!config.chat.matches(update)) return false;
        if(!config.flag("chat.telegramToMinecraft",true) || (update.media()&&!config.flag("messages.mediaLabels",true))) return true;
        String senderName=update.displayName()==null||update.displayName().isBlank()?Long.toString(update.userId()):update.displayName();
        Component message=incomingMessage(update.userId(),senderName,limit(text,1500));
        plugin.getServer().getGlobalRegionScheduler().run(plugin,task->{
            if(stopping.get()) return;
            for(Player player:plugin.getServer().getOnlinePlayers()) player.getScheduler().run(plugin,t->{if(!stopping.get()) player.sendMessage(message);},null);
            plugin.getServer().getConsoleSender().sendMessage(message);
        });
        return true;
    }
    private void deleteCommand(TelegramApi.Incoming update) {
        // Independent of command routing: slow Telegram/proxy requests cannot delay replies.
        try {commandDeletion.execute(()->{
            if(stopping.get()) return;
            try {api.delete(update.chatId(),update.messageId());}
            catch(Exception failure) {commandDeleteWarning(api.safe(failure));}
        });} catch(RejectedExecutionException full) {if(!stopping.get()) commandDeleteWarning("очередь удаления заполнена");}
    }
    private synchronized void commandDeleteWarning(String reason) {
        long now=System.currentTimeMillis();
        if(now-lastCommandDeleteWarning>=60000) {
            lastCommandDeleteWarning=now;
            plugin.getLogger().warning("Не удалось удалить команду Telegram-чата: "+reason+". Боту нужно право администратора «Удаление сообщений».");
        }
    }
    Component incomingMessage(long user,String sender,String text) {
        boolean showTag=config.flag("messages.showTelegramTagUnlinked",config.flag("messages.showTelegramTag",true));
        if(guard!=null) try {
            var account=guard.account(user).get(5,TimeUnit.SECONDS);
            if(account!=null) {
                showTag=config.flag("messages.showTelegramTagLinked",config.flag("messages.showTelegramTag",true));
                if(config.flag("messages.linkedPlayerIdentity",true)) {
                    try {if(identity!=null) return identity.render(account,text,showTag);} catch(Exception e) {identityWarning();}
                    return LinkedChatIdentity.fallback(account.name(),text,showTag);
                }
            }
        } catch(Exception e) {identityWarning();}
        String template=config.format("telegramChat","{tg}<white>{sender}<gray>:</gray> {text}</white>");
        if(template.equals("<aqua>[TG] {sender}</aqua> {text}")) template="{tg}<white>{sender}<gray>:</gray> {text}</white>";
        // Existing configs used a literal marker. Keep the toggle effective without rewriting them.
        if(!template.contains("{tg}")) template=template.replace("[TG] ","{tg}").replace("[TG]","{tg}");
        return MiniMessage.miniMessage().deserialize(template.replace("{tg}","<bridge_tag>").replace("{sender}","<bridge_sender>").replace("{text}","<bridge_text>"),Placeholder.component("bridge_tag",LinkedChatIdentity.tag(showTag)),Placeholder.component("bridge_sender",Component.text(limit(sender,120),net.kyori.adventure.text.format.NamedTextColor.WHITE)),Placeholder.component("bridge_text",Component.text(text,net.kyori.adventure.text.format.NamedTextColor.WHITE)));
    }
    private void identityWarning() {
        long now=System.currentTimeMillis();if(now-lastIdentityWarning>60000) {lastIdentityWarning=now;plugin.getLogger().warning("Не удалось получить оформление привязанного персонажа; используется запасное оформление [TG].");}
    }
    private boolean consumeListCallback(TelegramApi.Incoming update) {
        String data=update.callbackData();if(data==null||!data.startsWith("vcl:")) return false;
        String[] parts=data.split(":",3);if(parts.length!=3||!config.listTarget(update)) return false;
        int page;try {page=Integer.parseInt(parts[2]);} catch(NumberFormatException ignored) {return false;}
        currentPlayerPages().thenAccept(pages->{if(!stopping.get()) outgoing.offer(new ListEdit(parts[1],new TelegramChatConfig.Target(update.chatId(),update.topicId()),update.messageId(),page,update.callbackId(),pages));});
        return true;
    }
    private void enqueueList(TelegramChatConfig.Target target,List<String> pages) {
        if(stopping.get()) return;
        String token=UUID.randomUUID().toString().replace("-","").substring(0,16);long now=System.currentTimeMillis();
        outgoing.offer(new ListSend(token,target,pages,config.flag("list.silent",false),now));
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
                lists.entrySet().removeIf(entry->System.currentTimeMillis()>=entry.getValue().expires());
                Job job=outgoing.poll(1,TimeUnit.SECONDS);if(job==null) continue;
                if(job instanceof ListSend item) {sendList(item);continue;}
                if(job instanceof ListEdit item) {editList(item);continue;}
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
        long expires=System.currentTimeMillis()+config.listLifetimeSeconds()*1000L;
        lists.put(item.token(),new ListSession(item.target(),messageId,expires));
        expiry.track(item.target().chatId(),messageId,expires);
    }
    private void editList(ListEdit item) throws Exception {
        ListSession session=lists.get(item.token());
        if(session==null||System.currentTimeMillis()>=session.expires()||!session.target().equals(item.target())||session.messageId()!=item.messageId()) {api.answerCallback(item.callbackId(),"Список уже удалён или устарел.",false);return;}
        int page=Math.max(0,Math.min(item.page(),item.pages().size()-1));
        api.editHtml(item.target().chatId(),item.messageId(),item.pages().get(page),listButtons(item.token(),page,item.pages().size()));
        api.answerCallback(item.callbackId(),"",false);
    }
    private void pollLoop() {
        long offset=TelegramApi.loadOffset(transport.offsetFile());int failures=0;boolean initialized=false;
        while(!stopping.get()) try {
            if(!initialized) {username=api.verify();if(reports!=null) api.registerChatCommands(false,true);else api.registerChatCommands(false);initialized=true;plugin.getLogger().info("Telegram-чат @"+username+" подключён.");}
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
        HandlerList.unregisterAll(this);receiver.interrupt();sender.interrupt();commandDeletion.shutdownNow();if(reports!=null) reports.close();expiry.close();api.close();lists.clear();outgoing.clear();
        try {receiver.join(2000);sender.join(2000);} catch(InterruptedException stop) {Thread.currentThread().interrupt();}
    }
}
