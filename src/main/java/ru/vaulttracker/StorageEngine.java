package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Local durability and network synchronization use different workers: DB outages cannot stall disk writes. */
public final class StorageEngine implements AutoCloseable {
    private final ScheduledExecutorService disk = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"VaultTracker-disk"));
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"VaultTracker-mariadb"));
    private final ConcurrentMap<BlockKey, Snapshot> pending = new ConcurrentHashMap<>();
    private final Path path;
    private final RemoteStore.Settings settings;
    private final Logger log;
    private LocalStore local, mirror;
    private RemoteStore remote;
    private volatile boolean closing, ready, connected, diskHealthy = true;
    private volatile long dirtyCount;
    private long lastDiskWarning, lastNetworkWarning;

    public StorageEngine(Path path, RemoteStore.Settings settings, Logger log) { this.path=path; this.settings=settings; this.log=log; }
    public void start(Consumer<List<Snapshot>> loaded) {
        disk.execute(() -> {
            try {
                // Register the drivers in this plugin classloader explicitly.
                Class.forName("org.h2.Driver"); Class.forName("org.mariadb.jdbc.Driver");
                local = new LocalStore(path);
                loaded.accept(local.load());
                local.republishAll();
                ready = true;
                dirtyCount = local.dirtyCount();
                if (settings != null && !closing) network.scheduleWithFixedDelay(this::sync, 0, 2, TimeUnit.SECONDS);
                log.info("Локальный каталог загружен. MariaDB синхронизируется отдельно.");
            } catch (Exception e) { diskHealthy=false; log.severe("Не удалось открыть локальный каталог: " + e.getClass().getSimpleName() + ". Сохраните файлы cache и проверьте доступ к папке."); }
        });
        disk.scheduleWithFixedDelay(this::flush, 250, 250, TimeUnit.MILLISECONDS);
    }
    public void accept(Snapshot v) {
        if (closing) return;
        pending.merge(v.sign(), v, (a,b) -> a.revision() > b.revision() ? a : b);
    }
    private void flush() {
        if (local == null || pending.isEmpty()) return;
        List<Snapshot> batch = pending.values().stream().limit(256).toList();
        try {
            local.save(batch);
            for (Snapshot v : batch) pending.remove(v.sign(), v);
            dirtyCount=local.dirtyCount(); diskHealthy=true;
        } catch (Exception e) {
            diskHealthy=false;
            if (System.currentTimeMillis()-lastDiskWarning > 30_000) {
                log.severe("Не удалось сохранить каталог на диск. Изменения остаются в памяти; проверьте свободное место и права.");
                lastDiskWarning=System.currentTimeMillis();
            }
        }
    }
    private void sync() {
        if (!ready || closing) return;
        try {
            if (mirror == null) mirror = new LocalStore(path);
            if (remote == null) remote = new RemoteStore(settings);
            remote.connect();
            List<Snapshot> batch = mirror.dirty(256);
            if (!batch.isEmpty()) { remote.write(batch); mirror.acknowledge(batch); }
            dirtyCount=mirror.dirtyCount();
            if (!connected) log.info("MariaDB подключена. Синхронизация каталога работает.");
            connected=true;
        } catch (Exception e) {
            connected=false;
            try { if (remote != null) remote.close(); } catch (Exception ignored) {}
            if (System.currentTimeMillis()-lastNetworkWarning > 30_000) {
                String state = e instanceof java.sql.SQLException se ? " SQLState="+se.getSQLState() : "";
                log.warning("MariaDB недоступна; изменения сохранены локально, подключение повторяется автоматически."+state);
                lastNetworkWarning=System.currentTimeMillis();
            }
        }
    }
    public boolean ready() { return ready && diskHealthy && !closing; }
    public String status() {
        return "Локальный каталог: " + (ready ? (diskHealthy ? "работает" : "ОШИБКА ЗАПИСИ") : "загружается/ошибка")
                + "; MariaDB: " + (settings == null ? "выключена в config.yml" : connected ? "подключена" : "ожидание подключения")
                + "; записей к отправке: " + dirtyCount + "; изменений в памяти: " + pending.size();
    }
    @Override public void close() {
        closing=true;
        network.submit(() -> {
            try { if (remote != null) remote.close(); } catch (Exception ignored) {}
            try { if (mirror != null) mirror.close(); } catch (Exception ignored) {}
        });
        network.shutdown();
        disk.submit(() -> {
            while (!pending.isEmpty() && local != null) {
                int count=pending.size(); flush();
                if (pending.size() >= count) break;
            }
            if (!pending.isEmpty()) log.severe("Не все изменения удалось сохранить при остановке: " + pending.size());
            try { if (local != null) local.close(); } catch (Exception e) { log.warning("Ошибка закрытия локального каталога."); }
        });
        disk.shutdown();
        try {
            if (!disk.awaitTermination(10, TimeUnit.SECONDS)) { log.severe("Истекло время сохранения каталога."); disk.shutdownNow(); }
            if (!network.awaitTermination(10, TimeUnit.SECONDS)) network.shutdownNow();
        } catch (InterruptedException e) { disk.shutdownNow(); network.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
