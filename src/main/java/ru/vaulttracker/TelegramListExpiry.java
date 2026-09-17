package ru.vaulttracker;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Durable deletion deadlines, independent of chat delivery and Minecraft ticks. */
final class TelegramListExpiry implements AutoCloseable {
    private record Message(long chat,int id) {
        String key() {return chat+":"+id;}
    }
    private final Path file;
    private final TelegramApi api;
    private final Logger logger;
    private final Map<Message,Long> pending=new HashMap<>(),retryAt=new HashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("VaultTracker-list-expiry").factory());
    private volatile boolean closed;
    private boolean dirty;
    private long lastWarning;

    TelegramListExpiry(Path file,TelegramApi api,Logger logger) throws IOException {
        this.file=file;this.api=api;this.logger=logger;
        if(Files.exists(file)) {
            Properties stored=new Properties();try(var in=Files.newInputStream(file)) {stored.load(in);}
            for(String key:stored.stringPropertyNames()) try {
                String[] parts=key.split(":",2);
                pending.put(new Message(Long.parseLong(parts[0]),Integer.parseInt(parts[1])),Long.parseLong(stored.getProperty(key)));
            } catch(RuntimeException invalid) {logger.warning("Пропущена повреждённая запись срока удаления /list.");}
        }
    }
    void start() {worker.scheduleWithFixedDelay(()->deleteDue(System.currentTimeMillis()),1,1,TimeUnit.SECONDS);}
    synchronized void track(long chat,int id,long expires) {
        pending.put(new Message(chat,id),expires);dirty=true;save();
    }
    void deleteDue(long now) {
        Map<Message,Long> snapshot;
        synchronized(this) {if(closed) return;if(dirty) save();snapshot=new HashMap<>(pending);}
        for(var entry:snapshot.entrySet()) {
            Message message=entry.getKey();
            synchronized(this) {
                if(closed) return;
                if(now<entry.getValue()||now<retryAt.getOrDefault(message,0L)) continue;
            }
            boolean done=false;
            // Telegram cannot delete these messages after 48 hours. Leave a small margin.
            if(now-entry.getValue()>TimeUnit.HOURS.toMillis(47)) {
                logger.warning("Не удалось удалить старый /list до истечения срока Telegram; запись удалена из очереди.");done=true;
            } else try {api.delete(message.chat(),message.id());done=true;}
            catch(Exception failure) {
                String reason=String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
                if(reason.contains("message to delete not found")) done=true;
                else synchronized(this) {
                    if(closed) return;
                    retryAt.put(message,now+30000);
                    if(lastWarning==0||now-lastWarning>=60000) {
                        lastWarning=now;
                        logger.warning("Не удалось удалить /list: "+api.safe(failure)+". Повтор через 30 секунд; проверьте соединение и права бота на удаление сообщений.");
                    }
                }
            }
            synchronized(this) {
                if(closed) return;
                if(done) {pending.remove(message);retryAt.remove(message);dirty=true;save();}
            }
        }
    }
    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Properties stored=new Properties();pending.forEach((message,expires)->stored.setProperty(message.key(),Long.toString(expires)));
            Path temp=file.resolveSibling(file.getFileName()+".tmp");
            try(var out=Files.newOutputStream(temp)) {stored.store(out,"VaultTracker /list deletion deadlines; no bot tokens");}
            try {Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            dirty=false;
        } catch(IOException failure) {
            long now=System.currentTimeMillis();
            if(lastWarning==0||now-lastWarning>=60000) {lastWarning=now;logger.warning("Не удалось сохранить сроки удаления /list; удаление продолжится в памяти, запись будет повторена.");}
        }
    }
    @Override public synchronized void close() {closed=true;worker.shutdownNow();if(dirty) save();}
}
