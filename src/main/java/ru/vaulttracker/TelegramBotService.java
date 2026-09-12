package ru.vaulttracker;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class TelegramBotService implements AutoCloseable {
    private final TelegramConfig config; private final TelegramApi api; private final TelegramCommands commands; private final Logger log;
    private final AtomicBoolean stopping=new AtomicBoolean(); private final Thread worker;
    TelegramBotService(TelegramConfig config,Catalogue catalogue,StorageEngine storage,Logger log) {
        this.config=config; this.api=new TelegramApi(config); this.commands=new TelegramCommands(catalogue,storage,config.resultLimit()); this.log=log;
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
                    if(config.allowedChatIds().isEmpty()) log.warning("allowedChatIds пуст: Telegram-каталог доступен всем пользователям бота.");
                }
                List<TelegramApi.Incoming> updates=api.updates(offset);
                for(var update:updates) {
                    if(update.text()!=null && update.chatId()!=0) process(update);
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
        boolean id=TelegramCommands.command(update.text()).equals("/id");
        if(!id && !config.allowed(update.chatId())) { api.send(update.chatId(),"Доступ закрыт.\nID этого чата: "+update.chatId()); return; }
        api.send(update.chatId(),commands.handle(update.chatId(),update.text()));
    }
    @Override public void close() {
        stopping.set(true); worker.interrupt(); api.close();
        try { worker.join(5000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
