package ru.vaulttracker;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import java.util.logging.Logger;

final class TelegramBotService implements AutoCloseable {
    private final TelegramConfig config; private final TelegramApi api; private final TelegramCommands commands; private final Logger log;
    private final AtomicBoolean stopping=new AtomicBoolean(); private final Thread worker;
    private final ScheduledExecutorService deletionScheduler=Executors.newSingleThreadScheduledExecutor(
            runnable->Thread.ofVirtual().name("VaultTracker-telegram-delete").unstarted(runnable));
    TelegramBotService(TelegramConfig config,Catalogue catalogue,StorageEngine storage,Logger log) {
        this.config=config; this.api=new TelegramApi(config); this.commands=new TelegramCommands(catalogue,storage,config.pageSize()); this.log=log;
        worker=Thread.ofVirtual().name("VaultTracker-telegram").unstarted(this::run);
    }
    void start() { worker.start(); }
    private void run() {
        int failures=0; long offset=TelegramApi.loadOffset(config.offsetFile()); boolean initialized=false;
        while(!stopping.get()) {
            try {
                if(!initialized) {
                    String username=api.verify(); api.registerCommands(); initialized=true; failures=0;
                    log.info("Telegram-бот @"+username+" запущен внутри VaultTracker.");
                    if(config.chats().isEmpty() && config.allowedChatIds().isEmpty()) log.warning("chats и allowedChatIds пусты: Telegram-каталог доступен всем пользователям бота.");
                    else if(!config.chats().isEmpty()) log.info("Telegram-бот ограничен чатами и темами из telegram.yml: "+config.chats().size()+".");
                }
                List<TelegramApi.Incoming> updates=api.updates(offset);
                for(var update:updates) {
                    if(update.chatId()!=0 && (update.text()!=null || update.callback())) process(update);
                    offset=Math.max(offset,update.updateId()+1);
                }
                if(!updates.isEmpty()) TelegramApi.saveOffset(config.offsetFile(),offset);
                failures=0;
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch(Exception e) {
                failures++; log.warning("Telegram недоступен: "+api.safe(e)+"; повтор подключения запланирован.");
                int maximum=config.retry().maxAttempts();
                if(maximum>0 && failures>=maximum) { log.severe("Telegram-бот остановлен после "+failures+" неудачных попыток. Перезапустите сервер после исправления telegram.yml."); break; }
                try { Thread.sleep(delay(failures)); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
    private long delay(int failures) {
        long multiplier=1L<<Math.min(30,Math.max(0,failures-1));
        return Math.min(config.retry().maxDelay(),config.retry().initialDelay()*multiplier);
    }
    private void process(TelegramApi.Incoming update) throws Exception {
        if(update.callback()) {
            if(!config.allowed(update.chatId(),update.topicId())) {
                api.answerCallback(update.callbackId(),"",false); return;
            }
            var result=commands.callback(update.userId(),update.callbackData());
            api.answerCallback(update.callbackId(),result.notice(),result.alert());
            if(result.view()!=null) api.edit(update.chatId(),update.messageId(),result.view());
            return;
        }
        if(!config.allowed(update.chatId(),update.topicId())) {
            if(isCommand(update.text()) && update.messageId()>0) {
                try { api.delete(update.chatId(),update.messageId()); }
                catch(Exception e) { log.fine("Не удалось удалить команду из запрещённой темы: "+api.safe(e)); }
            }
            return;
        }
        sendTemporary(update.chatId(),update.topicId(),commands.handle(update.userId(),update.chatId(),update.topicId(),update.text(),config.admin(update.userId())));
        if(isCommand(update.text()) && !keepCommand(update.text()) && update.messageId()>0) {
            try { api.delete(update.chatId(),update.messageId()); }
            catch(Exception e) { log.fine("Не удалось удалить служебную команду Telegram: "+api.safe(e)); }
        }
    }
    private static boolean isCommand(String text) { return text!=null && text.trim().startsWith("/"); }
    static boolean keepCommand(String text) {
        String command=TelegramCommands.command(text);
        return command.equals("/item") || command.equals("/topitem") || command.equals("/itemtop");
    }
    private void sendTemporary(long chatId,int topicId,TelegramCommands.View view) throws Exception {
        int messageId=api.send(chatId,topicId,view);
        deletionScheduler.schedule(()-> {
            if(stopping.get()) return;
            try { api.delete(chatId,messageId); }
            catch(Exception e) { log.fine("Не удалось удалить временное сообщение Telegram: "+api.safe(e)); }
        },config.messageLifetimeSeconds(),TimeUnit.SECONDS);
    }
    @Override public void close() {
        stopping.set(true); worker.interrupt(); deletionScheduler.shutdownNow(); api.close();
        try { worker.join(5000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
