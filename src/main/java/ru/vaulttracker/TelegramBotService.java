package ru.vaulttracker;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import java.util.logging.Logger;

final class TelegramBotService implements AutoCloseable {
    private final TelegramConfig config; private final TelegramApi api; private final TelegramCommands commands; private final Logger log;
    private final TelegramPrivateMenu privateMenu;
    private final GuardService guard;
    private final TelegramGuardMenu guardMenu;
    private final ScheduledExecutorService notifications=Executors.newSingleThreadScheduledExecutor(
            r->Thread.ofVirtual().name("VaultTracker-guard-notify").unstarted(r));
    private String username;
    private static final Set<String> COMMANDS=Set.of("/start","/help","/id","/status","/items","/item","/topitem","/itemtop");
    private static final Set<String> PRIVATE_COMMANDS=Set.of("/menu","/search","/cancel");
    private final AtomicBoolean stopping=new AtomicBoolean(); private final Thread worker;
    private final ScheduledExecutorService deletionScheduler=Executors.newSingleThreadScheduledExecutor(
            runnable->Thread.ofVirtual().name("VaultTracker-telegram-delete").unstarted(runnable));
    TelegramBotService(TelegramConfig config,Catalogue catalogue,StorageEngine storage,Logger log) {
        this(config,catalogue,storage,log,null);
    }
    TelegramBotService(TelegramConfig config,Catalogue catalogue,StorageEngine storage,Logger log,GuardService guard) {
        this(config,catalogue,storage,log,guard,null);
    }
    TelegramBotService(TelegramConfig config,Catalogue catalogue,StorageEngine storage,Logger log,GuardService guard,TelegramModeration moderation) {
        this.config=config; this.api=new TelegramApi(config); this.commands=new TelegramCommands(catalogue,storage,config.pageSize()); this.log=log;
        this.privateMenu=new TelegramPrivateMenu(catalogue,storage,commands,config.pageSize());
        this.guard=guard;this.guardMenu=guard==null ? null : new TelegramGuardMenu(guard,commands,moderation);
        privateMenu.guard(guard);
        commands.adminAccess(this::isAdmin);
        worker=Thread.ofVirtual().name("VaultTracker-telegram").unstarted(this::run);
    }
    void start() { worker.start(); }
    private boolean isAdmin(long user) {return guard==null ? config.admin(user) : guard.admin(user);}
    private void run() {
        int failures=0; long offset=TelegramApi.loadOffset(config.offsetFile()); boolean initialized=false;
        while(!stopping.get()) {
            try {
                if(!initialized) {
                    username=api.verify(); api.registerCommands(); initialized=true; failures=0;
                    if(guard!=null) notifications.scheduleWithFixedDelay(this::notifyGuard,2,2,TimeUnit.SECONDS);
                    log.info("Telegram-бот @"+username+" запущен внутри VaultTracker.");
                    if(config.chats().isEmpty() && config.allowedChatIds().isEmpty()) log.warning("chats и allowedChatIds пусты: Telegram-каталог доступен всем пользователям бота.");
                    else if(!config.chats().isEmpty()) log.info("Telegram-бот ограничен чатами и темами из telegram.yml: "+config.chats().size()+".");
                }
                List<TelegramApi.Incoming> updates=api.updates(offset);
                for(var update:updates) {
                    // Persist consumption before effects: a failed reply must never replay a moderation action.
                    offset=Math.max(offset,update.updateId()+1);
                    TelegramApi.saveOffset(config.offsetFile(),offset);
                    consume(update);
                }
                if(!updates.isEmpty()) TelegramApi.saveOffset(config.offsetFile(),offset);
                failures=0;
            } catch(Exception e) {
                if(stopping.get() || Thread.currentThread().isInterrupted()) break;
                failures++; log.warning("Telegram недоступен: "+api.safe(e)+"; повтор подключения запланирован.");
                int maximum=config.retry().maxAttempts();
                if(maximum>0 && failures>=maximum) { log.severe("Telegram-бот остановлен после "+failures+" неудачных попыток. Перезапустите сервер после исправления telegram.yml."); break; }
                try { Thread.sleep(delay(failures)); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
    void consume(TelegramApi.Incoming update) {
        try {if(update.chatId()!=0 && (update.text()!=null || update.callback())) process(update);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();}
        catch(Exception e) {
            if(TelegramApi.benignCallbackError(e)) log.fine("Устаревший ответ Telegram пропущен.");
            else log.warning("Не удалось обработать сообщение Telegram; бот продолжает работу: "+api.safe(e));
        }
    }
    private long delay(int failures) {
        long multiplier=1L<<Math.min(30,Math.max(0,failures-1));
        return Math.min(config.retry().maxDelay(),config.retry().initialDelay()*multiplier);
    }
    private void process(TelegramApi.Incoming update) throws Exception {
        if(update.callback()) {
            if(update.privateChat()) {
                if(guardMenu!=null) guardMenu.cancelInput(update.userId());
                TelegramCommands.Callback result;
                if(guardMenu!=null && update.callbackData().equals("vg:search")) {
                    result=new TelegramCommands.Callback(privateMenu.handle(update.userId(),update.chatId(),0,"/search",isAdmin(update.userId())),"",false);
                } else if(guardMenu!=null && update.callbackData().startsWith("vg:")) {
                    privateMenu.leave(update.userId(),update.chatId(),update.topicId());
                    try {result=guardMenu.callback(update.userId(),update.callbackData());}
                    catch(Exception e) {log.warning("Не удалось открыть кабинет охраны: "+e.getClass().getSimpleName());result=new TelegramCommands.Callback(null,"Кабинет временно недоступен или нет доступа. Откройте /menu.",true);}
                } else if(guardMenu!=null && update.callbackData().startsWith("vt:")) {
                    var page=commands.callback(update.userId(),update.callbackData());
                    result=page.view()==null ? page : new TelegramCommands.Callback(TelegramGuardMenu.withHome(page.view()),page.notice(),page.alert());
                } else result=privateMenu.callback(update.userId(),update.chatId(),update.topicId(),update.callbackData());
                api.answerCallback(update.callbackId(),result.notice(),result.alert());
                if(result.view()!=null) api.edit(update.chatId(),update.messageId(),result.view());
                return;
            }
            if(!config.allowed(update.chatId(),update.topicId())) {
                api.answerCallback(update.callbackId(),"",false); return;
            }
            var result=commands.callback(update.userId(),update.callbackData());
            api.answerCallback(update.callbackId(),result.notice(),result.alert());
            if(result.view()!=null) api.edit(update.chatId(),update.messageId(),result.view());
            return;
        }
        if(!update.privateChat() && !isCommand(update.text())) return;
        if(isCommand(update.text()) && !acceptsCommand(update.text(),username,update.privateChat())) return;
        if(update.privateChat() && guardMenu!=null && isCommand(update.text())) guardMenu.cancelInput(update.userId());
        if(TelegramCommands.command(update.text()).equals("/id")) {
            log.info("Telegram /id — данные для telegram.yml (отправитель: "+update.userId()+"):\n"
                    +"chats:\n"
                    +"  - isDefault: true\n"
                    +"    chatId: "+update.chatId()+"\n"
                    +"    topicId: "+update.topicId()+"\n"
                    +"adminUserIds:\n"
                    +"  - "+update.userId()+"\n"
                    +"Скопируйте нужные значения в telegram.yml и выполните /vtrack reload. Настройки автоматически не изменяются.");
            if(update.messageId()>0) {
                try { api.delete(update.chatId(),update.messageId()); }
                catch(Exception e) { log.fine("Не удалось удалить команду /id из Telegram: "+api.safe(e)); }
            }
            return;
        }
        if(update.privateChat()) {
            TelegramCommands.View view=null;
            if(guardMenu!=null && !isCommand(update.text())) view=guardMenu.input(update.userId(),update.text());
            if(view==null && guardMenu!=null && Set.of("/start","/menu","/cancel").contains(TelegramCommands.command(update.text()))) {
                privateMenu.leave(update.userId(),update.chatId(),update.topicId());view=guardMenu.home(update.userId());
            }
            if(view==null) view=privateMenu.handle(update.userId(),update.chatId(),update.topicId(),update.text(),isAdmin(update.userId()));
            if(view!=null) api.send(update.chatId(),update.topicId(),view);
            if(isCommand(update.text()) && update.messageId()>0) {
                try { api.delete(update.chatId(),update.messageId()); }
                catch(Exception e) { log.fine("Не удалось удалить команду в личном чате: "+api.safe(e)); }
            }
            return;
        }
        if(!config.allowed(update.chatId(),update.topicId())) {
            if(isCommand(update.text()) && update.messageId()>0) {
                try { api.delete(update.chatId(),update.messageId()); }
                catch(Exception e) { log.fine("Не удалось удалить команду из запрещённой темы: "+api.safe(e)); }
            }
            return;
        }
        sendTemporary(update.chatId(),update.topicId(),commands.handle(update.userId(),update.chatId(),update.topicId(),update.text(),isAdmin(update.userId())));
        if(isCommand(update.text()) && update.messageId()>0) {
            try { api.delete(update.chatId(),update.messageId()); }
            catch(Exception e) { log.fine("Не удалось удалить команду Telegram: "+api.safe(e)); }
        }
    }
    private static boolean isCommand(String text) { return text!=null && text.trim().startsWith("/"); }
    static boolean acceptsCommand(String text,String username,boolean privateChat) {
        if(!isCommand(text)) return false;
        String token=text.trim().split("\\s+",2)[0];
        int mention=token.indexOf('@');
        if(mention>=0 && (username==null || !token.substring(mention+1).equalsIgnoreCase(username))) return false;
        String command=TelegramCommands.command(text.trim());
        return COMMANDS.contains(command) || (privateChat && PRIVATE_COMMANDS.contains(command));
    }
    private void sendTemporary(long chatId,int topicId,TelegramCommands.View view) throws Exception {
        int messageId=api.send(chatId,topicId,view);
        deletionScheduler.schedule(()-> {
            if(stopping.get()) return;
            try { api.delete(chatId,messageId); }
            catch(Exception e) { log.fine("Не удалось удалить временное сообщение Telegram: "+api.safe(e)); }
        },config.messageLifetimeSeconds(),TimeUnit.SECONDS);
    }
    private void notifyGuard() {
        if(stopping.get()) return;
        try {
            var grouped=new java.util.LinkedHashMap<Long,java.util.List<GuardService.Delivery>>();
            for(var delivery:guard.deliveries().get()) grouped.computeIfAbsent(delivery.recipient(),ignored->new java.util.ArrayList<>()).add(delivery);
            for(var entry:grouped.entrySet()) {
                if(stopping.get()) return;
                var batch=new java.util.ArrayList<GuardService.Delivery>();StringBuilder text=new StringBuilder();
                for(var delivery:entry.getValue()) {
                    if(text.length()+delivery.text().length()+5>3500) break;
                    if(!text.isEmpty()) text.append("\n\n—\n\n");
                    text.append(delivery.text());batch.add(delivery);
                }
                if(batch.isEmpty()) continue;
                boolean success=false;
                try {api.send(entry.getKey(),0,new TelegramCommands.View(text.toString()));success=true;}
                catch(Exception e) {log.warning("Уведомление охраны не доставлено Telegram-пользователю "+entry.getKey()+": "+api.safe(e)+". Повтор через 5 минут; получатель должен открыть личный чат бота.");}
                for(var delivery:batch) guard.delivered(delivery.id(),success).get();
            }
        } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        catch(Exception e) {log.warning("Ошибка очереди уведомлений охраны: "+e.getClass().getSimpleName());}
    }
    @Override public void close() {
        stopping.set(true); worker.interrupt(); deletionScheduler.shutdownNow(); api.close();
        notifications.shutdownNow();
        try {notifications.awaitTermination(5,TimeUnit.SECONDS);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
        try { worker.join(5000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
