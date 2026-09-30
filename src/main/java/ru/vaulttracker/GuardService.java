package ru.vaulttracker;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Dedicated disk worker: region threads only enqueue immutable snapshots and presence changes. */
final class GuardService implements AutoCloseable {
    private static final char[] LINK_CODE_ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    static final int LINK_CODE_LENGTH=10;
    static final List<String> ADMIN_CAPABILITIES=List.of("ban","permanentBan","kick","inventory","ender","unban","heal","kill","repair","scale","flySpeed","walkSpeed","teleport","teleportCoords","luckPerms","op","deop","chat","freeze","bastionTrust");
    static final long RETENTION=30L*24*60*60*1000, CODE_TTL=10*60*1000L;
    static final long MAX_OFFLINE_SECONDS=31_536_000;
    record Account(UUID uuid,String name,boolean notifications) {}
    record LinkResult(String message,boolean linked) {}
    record Child(UUID uuid,String name,double size) {}
    record Friend(UUID uuid,String name,boolean muted,boolean shared,boolean notified) {}
    record DirectMessage(long id,UUID sender,UUID recipient,String senderName,String text,long created) {}
    record PendingDirect(long id,long telegram,UUID sender,String senderName,String text) {}
    record Dialog(UUID peer,String name,long unread,long latest) {}
    record Conversation(List<DirectMessage> messages,boolean older) {}
    record RootCommand(long id,String command) {}
    record OnlineLinked(UUID uuid,String name) {}
    record UnbanState(String id,long telegram,UUID uuid,boolean linked,int attempt,long inviteUntil,
                      int inviteMessage,long accessUntil,String banReason,long banExpiry,String banSource,long actor) {}
    final DonationClient donations;
    private final Map<UUID,Child> children=new ConcurrentHashMap<>();
    private final Map<UUID,String> linkedNames=new ConcurrentHashMap<>();
    private final Map<UUID,String> currentNames=new ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> knownPlayerNames=new ConcurrentHashMap<>();
    private final Map<UUID,Boolean> telegramGameChat=new ConcurrentHashMap<>();
    private final Map<Long,ItemSorting.Mode> itemSortModes=new ConcurrentHashMap<>();
    private final Map<String,Long> itemSearchCounts=new ConcurrentHashMap<>();
    private final Map<UUID,Map<UUID,Friend>> friends=new ConcurrentHashMap<>();
    record Event(long id,long time,String name,String location,Map<String,Long> changes,boolean removed,String actor,UUID actorUuid) {
        Event(long id,long time,String name,String location,Map<String,Long> changes,boolean removed,String actor) {this(id,time,name,location,changes,removed,actor,null);}
    }
    record Delivery(long id,long recipient,String text,UUID actorUuid,String actor) {}
    private record Code(long user,long expires) {}
    private final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"VaultTracker-guard-disk"));
    private final Connection db;
    private final SupportRepository supportRepository;
    private final Logger log;
    private final LongSupplier clock;
    private final long started;
    private final Map<UUID,Long> presence=new ConcurrentHashMap<>(); // -1 online, otherwise exit timestamp
    private final Map<UUID,Long> personalAbsence=new ConcurrentHashMap<>();
    private final Map<UUID,String> worlds=new ConcurrentHashMap<>();
    private final Map<BlockKey,Actor> actors=new ConcurrentHashMap<>();
    private final Map<BlockKey,Long> automated=new ConcurrentHashMap<>();
    private record Actor(UUID uuid,String name,long expires) {}
    private final Map<BlockKey,Snapshot> baseline=new ConcurrentHashMap<>();
    private final Map<String,Code> codes=new HashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final Gson gson=new Gson();
    private volatile GuardConfig config;
    private volatile Set<Long> admins=Set.of();
    private final Map<Long,Boolean> adminOverrides=new ConcurrentHashMap<>();
    private final Set<Long> extraSupers=ConcurrentHashMap.newKeySet();
    private final Set<Long> extraRoots=ConcurrentHashMap.newKeySet();
    private final Map<Long,String> telegramProfiles=new ConcurrentHashMap<>();
    private final Map<Long,Long> superDelays=new ConcurrentHashMap<>();
    private final Map<String,Boolean> adminCapabilities=new ConcurrentHashMap<>();
    private long lastPrune;

    GuardService(Path path,GuardConfig config,Logger log) throws Exception { this(path,config,log,System::currentTimeMillis); }
    GuardService(Path path,GuardConfig config,Logger log,LongSupplier clock) throws Exception {
        this.config=config; this.log=log; this.clock=clock; started=clock.getAsLong();donations=new DonationClient(path.toAbsolutePath().getParent());
        Class.forName("org.h2.Driver");
        db=DriverManager.getConnection("jdbc:h2:"+path.toAbsolutePath()+";DB_CLOSE_ON_EXIT=FALSE","sa","");
        supportRepository=new SupportRepository(db,gson,clock);
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS accounts (tg BIGINT PRIMARY KEY, uuid VARCHAR(36) UNIQUE NOT NULL, nickname VARCHAR(64) NOT NULL, alerts BOOLEAN NOT NULL DEFAULT TRUE)");
            s.execute("CREATE TABLE IF NOT EXISTS children (uuid VARCHAR(36) PRIMARY KEY, nickname VARCHAR(64) NOT NULL, size DOUBLE PRECISION NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guard_friends (owner_uuid VARCHAR(36) NOT NULL, friend_uuid VARCHAR(36) NOT NULL, nickname VARCHAR(64) NOT NULL, PRIMARY KEY(owner_uuid,friend_uuid))");
            s.execute("ALTER TABLE guard_friends ADD COLUMN IF NOT EXISTS muted BOOLEAN DEFAULT TRUE NOT NULL");
            s.execute("ALTER TABLE guard_friends ADD COLUMN IF NOT EXISTS shared BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE guard_friends ADD COLUMN IF NOT EXISTS notified BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("CREATE TABLE IF NOT EXISTS donation_settings (id INT PRIMARY KEY, visible BOOLEAN NOT NULL)");
            s.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS offline_seconds BIGINT");
            s.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS telegram_tag BOOLEAN NOT NULL DEFAULT FALSE");
            s.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS bastion_pending BOOLEAN NOT NULL DEFAULT FALSE");
            s.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS friend_alerts BOOLEAN NOT NULL DEFAULT TRUE");
            s.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS game_message_notifications BOOLEAN NOT NULL DEFAULT TRUE");
            s.execute("CREATE TABLE IF NOT EXISTS guard_admins (tg BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guard_supers (tg BIGINT PRIMARY KEY)");
            s.execute("CREATE TABLE IF NOT EXISTS telegram_profiles (tg BIGINT PRIMARY KEY, label VARCHAR(512) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS super_delays (tg BIGINT PRIMARY KEY, seconds BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS admin_capabilities (capability VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guard_worlds (uuid VARCHAR(36) PRIMARY KEY, world_name VARCHAR(256) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS preferences (tg BIGINT PRIMARY KEY, alerts BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS presence (uuid VARCHAR(36) PRIMARY KEY, departed BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS events (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, happened BIGINT NOT NULL, payload CLOB NOT NULL)");
            s.execute("ALTER TABLE events ADD COLUMN IF NOT EXISTS owner_uuid VARCHAR(36)");
            s.execute("CREATE INDEX IF NOT EXISTS guard_owner_events ON events(owner_uuid,happened)");
            s.execute("CREATE TABLE IF NOT EXISTS outbox (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, event_id BIGINT, tg BIGINT NOT NULL, owner_uuid VARCHAR(36), body CLOB NOT NULL, due BIGINT NOT NULL, created BIGINT NOT NULL)");
            s.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS own_channel BOOLEAN NOT NULL DEFAULT TRUE");
            s.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS admin_channel BOOLEAN NOT NULL DEFAULT TRUE");
            s.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS batch CLOB");
            s.execute("CREATE INDEX IF NOT EXISTS guard_event_time ON events(happened)");
            s.execute("CREATE INDEX IF NOT EXISTS guard_outbox_due ON outbox(due)");
            s.execute("CREATE TABLE IF NOT EXISTS player_messages (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, sender_uuid VARCHAR(36) NOT NULL, recipient_uuid VARCHAR(36) NOT NULL, sender_name VARCHAR(64) NOT NULL, recipient_name VARCHAR(64) NOT NULL, body VARCHAR(500) NOT NULL, created BIGINT NOT NULL, read_at BIGINT)");
            s.execute("CREATE INDEX IF NOT EXISTS player_messages_inbox ON player_messages(recipient_uuid,read_at,id)");
            s.execute("CREATE INDEX IF NOT EXISTS player_messages_sender ON player_messages(sender_uuid,id)");
            s.execute("CREATE INDEX IF NOT EXISTS player_messages_pair ON player_messages(sender_uuid,recipient_uuid,id)");
            s.execute("ALTER TABLE player_messages ADD COLUMN IF NOT EXISTS notified_at BIGINT");
            s.execute("ALTER TABLE player_messages ADD COLUMN IF NOT EXISTS from_game BOOLEAN NOT NULL DEFAULT TRUE");
            s.execute("CREATE INDEX IF NOT EXISTS player_messages_notify ON player_messages(notified_at,read_at,id)");
            s.execute("CREATE TABLE IF NOT EXISTS telegram_notification_topics (tg BIGINT PRIMARY KEY, thread_id INT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS telegram_game_chat (uuid VARCHAR(36) PRIMARY KEY, enabled BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS player_stat_counters (uuid VARCHAR(36) NOT NULL, stat_key VARCHAR(128) NOT NULL, total BIGINT NOT NULL, last_vanilla INT NOT NULL, PRIMARY KEY(uuid,stat_key))");
            s.execute("CREATE TABLE IF NOT EXISTS known_player_names (nickname VARCHAR(64) NOT NULL, uuid VARCHAR(36) NOT NULL, PRIMARY KEY(nickname,uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS item_sort_preferences (tg BIGINT PRIMARY KEY, mode VARCHAR(32) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS item_search_counts (item VARCHAR(128) PRIMARY KEY, searches BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS root_commands (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, command VARCHAR(2048) NOT NULL)");
            s.execute("ALTER TABLE root_commands ADD COLUMN IF NOT EXISTS tg BIGINT NOT NULL DEFAULT 0");
            s.execute("CREATE TABLE IF NOT EXISTS guard_roots (tg BIGINT PRIMARY KEY)");
        }
        if(config.superAdminUserId()>0) execute("UPDATE root_commands SET tg=? WHERE tg=0",config.superAdminUserId());
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT uuid,offline_seconds FROM accounts WHERE offline_seconds IS NOT NULL")) {
            while(rs.next()) personalAbsence.put(UUID.fromString(rs.getString(1)),rs.getLong(2)*1000);
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT uuid,nickname FROM accounts")) {
            while(rs.next()) {UUID uuid=UUID.fromString(rs.getString(1));String name=rs.getString(2);linkedNames.put(uuid,name);currentNames.put(uuid,name);rememberKnownName(uuid,name);}
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT nickname,uuid FROM known_player_names")) {
            while(rs.next()) rememberKnownName(UUID.fromString(rs.getString(2)),rs.getString(1));
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT uuid,enabled FROM telegram_game_chat")) {
            while(rs.next()) telegramGameChat.put(UUID.fromString(rs.getString(1)),rs.getBoolean(2));
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT tg,mode FROM item_sort_preferences")) {
            while(rs.next()) try {itemSortModes.put(rs.getLong(1),ItemSorting.Mode.valueOf(rs.getString(2)));}
            catch(IllegalArgumentException invalid) {log.warning("Неизвестный режим сортировки предметов; используется режим по умолчанию.");}
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT item,searches FROM item_search_counts")) {
            while(rs.next()) itemSearchCounts.put(rs.getString(1),rs.getLong(2));
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT uuid,world_name FROM guard_worlds")) {
            while(rs.next()) worlds.put(UUID.fromString(rs.getString(1)),rs.getString(2));
        }
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT uuid, departed FROM presence")) {
            while(rs.next()) presence.put(UUID.fromString(rs.getString(1)),rs.getLong(2)<0 ? started : rs.getLong(2));
        }
        execute("UPDATE presence SET departed=? WHERE departed<0",started);
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT tg,enabled FROM guard_admins")) {while(rs.next()) adminOverrides.put(rs.getLong(1),rs.getBoolean(2));}
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT tg FROM guard_supers")) {while(rs.next()) extraSupers.add(rs.getLong(1));}
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT tg FROM guard_roots")) {while(rs.next()) extraRoots.add(rs.getLong(1));}
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT tg,label FROM telegram_profiles")) {while(rs.next()) telegramProfiles.put(rs.getLong(1),rs.getString(2));}
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT tg,seconds FROM super_delays")) {while(rs.next()) superDelays.put(rs.getLong(1),rs.getLong(2));}
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT capability,enabled FROM admin_capabilities")) {while(rs.next()) adminCapabilities.put(rs.getString(1),rs.getBoolean(2));}
        disk.scheduleWithFixedDelay(()-> {try {prune();} catch(Exception e) {log.warning("Не удалось очистить историю охраны.");}},1,60,TimeUnit.MINUTES);
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT uuid,nickname,size FROM children")) {while(rs.next()) {var child=new Child(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getDouble(3));children.put(child.uuid(),child);}}
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT owner_uuid,friend_uuid,nickname,muted,shared,notified FROM guard_friends")) {while(rs.next()) {
            UUID owner=UUID.fromString(rs.getString(1)),friend=UUID.fromString(rs.getString(2));
            friends.computeIfAbsent(owner,ignored->new ConcurrentHashMap<>()).put(friend,new Friend(friend,rs.getString(3),rs.getBoolean(4),rs.getBoolean(5),rs.getBoolean(6)));
        }}
    }
    CompletableFuture<List<Friend>> friends(long user) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        return friends.getOrDefault(account.uuid(),Map.of()).entrySet().stream()
                .map(Map.Entry::getValue).sorted(Comparator.comparing(Friend::name,String.CASE_INSENSITIVE_ORDER)).toList();
    });}
    CompletableFuture<Void> addFriend(long user,UUID uuid,String name) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        if(account.uuid().equals(uuid)) throw new IllegalArgumentException("Нельзя добавить себя в друзья");
        String nickname=Objects.requireNonNull(name).strip();if(nickname.isEmpty()||nickname.length()>64) throw new IllegalArgumentException("Некорректный игровой ник");
        var prior=friends.getOrDefault(account.uuid(),Map.of()).get(uuid);
        Friend value=new Friend(uuid,nickname,prior==null||prior.muted(),prior!=null&&prior.shared(),prior!=null&&prior.notified());
        execute("MERGE INTO guard_friends (owner_uuid,friend_uuid,nickname,muted,shared,notified) KEY(owner_uuid,friend_uuid) VALUES(?,?,?,?,?,?)",account.uuid().toString(),uuid.toString(),nickname,value.muted(),value.shared(),value.notified());
        friends.computeIfAbsent(account.uuid(),ignored->new ConcurrentHashMap<>()).put(uuid,value);return null;
    });}
    CompletableFuture<Void> removeFriend(long user,UUID uuid) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        execute("DELETE FROM guard_friends WHERE owner_uuid=? AND friend_uuid=?",account.uuid().toString(),uuid.toString());
        execute("DELETE FROM outbox WHERE owner_uuid=? AND tg=(SELECT tg FROM accounts WHERE uuid=?) AND own_channel=FALSE AND admin_channel=FALSE",account.uuid().toString(),uuid.toString());
        Map<UUID,Friend> ownerFriends=friends.get(account.uuid());if(ownerFriends!=null) {ownerFriends.remove(uuid);if(ownerFriends.isEmpty()) friends.remove(account.uuid(),ownerFriends);}
        return null;
    });}
    private boolean friend(UUID owner,UUID actor) {var entry=friends.getOrDefault(owner,Map.of()).get(actor);return entry!=null&&entry.muted();}
    CompletableFuture<Void> toggleFriend(long user,UUID uuid,boolean sharing) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        var entries=friends.getOrDefault(account.uuid(),Map.of());var old=entries.get(uuid);
        if(old==null) throw new SecurityException("Друг больше не в списке");
        Friend value=new Friend(uuid,old.name(),sharing?old.muted():!old.muted(),sharing?!old.shared():old.shared(),old.notified());
        execute("UPDATE guard_friends SET muted=?,shared=? WHERE owner_uuid=? AND friend_uuid=?",value.muted(),value.shared(),account.uuid().toString(),uuid.toString());
        entries.put(uuid,value);return null;
    });}
    CompletableFuture<Void> toggleFriendNotifications(long user,UUID uuid) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        var entries=friends.getOrDefault(account.uuid(),Map.of());var old=entries.get(uuid);
        if(old==null) throw new SecurityException("Друг больше не в списке");
        Friend value=new Friend(uuid,old.name(),old.muted(),old.shared(),!old.notified());
        execute("UPDATE guard_friends SET notified=? WHERE owner_uuid=? AND friend_uuid=?",value.notified(),account.uuid().toString(),uuid.toString());
        if(!value.notified()) execute("DELETE FROM outbox WHERE owner_uuid=? AND tg=(SELECT tg FROM accounts WHERE uuid=?) AND own_channel=FALSE AND admin_channel=FALSE",account.uuid().toString(),uuid.toString());
        entries.put(uuid,value);return null;
    });}
    boolean canSearch(UUID viewer,UUID owner) {
        if(viewer.equals(owner)) return true;
        var entry=friends.getOrDefault(owner,Map.of()).get(viewer);return entry!=null&&entry.shared();
    }
    Set<UUID> sharedOwners(UUID viewer) {return friends.entrySet().stream().filter(e->!e.getKey().equals(viewer)&&canSearch(viewer,e.getKey())).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    Child child(UUID uuid) {return children.get(uuid);}
    List<Child> children(long user) {requireRoot(user);return children.values().stream().sorted(Comparator.comparing(Child::name,String.CASE_INSENSITIVE_ORDER)).toList();}
    CompletableFuture<Void> child(long user,UUID uuid,String name,boolean enabled) {return submit(()->{
        requireRoot(user);
        if(enabled) {var prior=children.get(uuid);double size=prior==null?1:prior.size();execute("MERGE INTO children KEY(uuid) VALUES(?,?,?)",uuid.toString(),name,size);children.put(uuid,new Child(uuid,name,size));}
        else {execute("DELETE FROM children WHERE uuid=?",uuid.toString());children.remove(uuid);}
        log.info("Telegram "+user+": "+(enabled?"addChild":"removeChild")+" → "+uuid);
        return null;
    });}
    CompletableFuture<UUID> childSize(long user,double size) {return submit(()->{
        if(size!=0.6&&size!=0.75&&size!=1) throw new IllegalArgumentException("Недопустимый размер");
        var account=accountNow(user);var child=account==null?null:children.get(account.uuid());
        if(child==null) throw new SecurityException("Функция доступна только добавленным детям с привязанным персонажем");
        execute("UPDATE children SET size=? WHERE uuid=?",size,child.uuid().toString());children.put(child.uuid(),new Child(child.uuid(),child.name(),size));return child.uuid();
    });}
    void configure(GuardConfig config,Set<Long> admins) { this.config=config; this.admins=Set.copyOf(admins); }
    boolean superAdmin(long user) {return user>0 && (rootAdmin(user) || extraSupers.contains(user));}
    boolean rootAdmin(long user) {return user>0 && (config.superAdminUserId()==user || extraRoots.contains(user));}
    void requireRoot(long user) {if(!rootAdmin(user)) throw new SecurityException("Раздел доступен только Root");}
    void requirePrimaryRoot(long user) {if(!primaryRoot(user)) throw new SecurityException("Управлять Root может только основной ID из guard.yml");}
    List<Long> rootIds(long user) {
        requirePrimaryRoot(user);
        List<Long> ids=new ArrayList<>(extraRoots);
        ids.remove(config.superAdminUserId());
        ids.sort(Long::compareTo);
        if(config.superAdminUserId()>0) ids.addFirst(config.superAdminUserId());
        return List.copyOf(ids);
    }
    boolean primaryRoot(long target) {return target>0 && target==config.superAdminUserId();}
    boolean canRemoveRoot(long user,long target) {return primaryRoot(user) && target>0 && target!=user && !primaryRoot(target) && extraRoots.contains(target);}
    CompletableFuture<Void> addRoot(long user,long target) {return submit(()-> {
        requirePrimaryRoot(user);
        if(target<=0 || rootAdmin(target)) throw new IllegalArgumentException("Некорректный или уже добавленный Root ID");
        execute("MERGE INTO guard_roots KEY(tg) VALUES(?)",target);
        extraRoots.add(target);
        log.info("Telegram "+user+": addRoot → "+target);
        return null;
    });}
    CompletableFuture<Void> removeRoot(long user,long target) {return submit(()-> {
        requirePrimaryRoot(user);
        if(!canRemoveRoot(user,target)) throw new IllegalArgumentException("Этого Root нельзя удалить");
        execute("DELETE FROM guard_roots WHERE tg=?",target);
        extraRoots.remove(target);
        log.info("Telegram "+user+": removeRoot → "+target);
        return null;
    });}
    CompletableFuture<List<RootCommand>> rootCommands(long user) {return submit(()-> {
        requireRoot(user);List<RootCommand> rows=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT id,command FROM root_commands WHERE tg=? ORDER BY id DESC LIMIT 20")) {
            s.setLong(1,user);try(var rs=s.executeQuery()) {
            while(rs.next()) rows.add(new RootCommand(rs.getLong(1),rs.getString(2)));
            }
        }
        return List.copyOf(rows);
    });}
    CompletableFuture<String> rootCommand(long user,long id) {return submit(()-> {
        requireRoot(user);
        try(var s=db.prepareStatement("SELECT command FROM root_commands WHERE id=? AND tg=?")) {
            s.setLong(1,id);s.setLong(2,user);try(var rs=s.executeQuery()) {return rs.next()?rs.getString(1):null;}
        }
    });}
    CompletableFuture<Void> rememberRootCommand(long user,String command) {return submit(()-> {
        requireRoot(user);
        if(command==null || command.isBlank() || command.length()>2048) throw new IllegalArgumentException("Некорректная команда");
        execute("DELETE FROM root_commands WHERE tg=? AND command=?",user,command);
        execute("INSERT INTO root_commands(tg,command) VALUES(?,?)",user,command);
        try(var s=db.prepareStatement("SELECT id FROM root_commands WHERE tg=? ORDER BY id DESC LIMIT 1 OFFSET 19")) {
            s.setLong(1,user);try(var rs=s.executeQuery()) {
                if(rs.next()) execute("DELETE FROM root_commands WHERE tg=? AND id<?",user,rs.getLong(1));
            }
        }
        return null;
    });}
    CompletableFuture<Void> clearRootCommands(long user) {return submit(()-> {requireRoot(user);execute("DELETE FROM root_commands WHERE tg=?",user);return null;});}
    boolean admin(long user) { return superAdmin(user) || adminOverrides.getOrDefault(user,admins.contains(user)); }
    boolean telegramGameChatEnabled(UUID uuid) {return telegramGameChat.getOrDefault(uuid,true);}
    CompletableFuture<Void> mergeStatistics(UUID uuid,Map<String,Integer> raw) {return submit(()-> {
        Objects.requireNonNull(uuid);Objects.requireNonNull(raw);
        try(var select=db.prepareStatement("SELECT total,last_vanilla FROM player_stat_counters WHERE uuid=? AND stat_key=?");
            var upsert=db.prepareStatement("MERGE INTO player_stat_counters(uuid,stat_key,total,last_vanilla) KEY(uuid,stat_key) VALUES(?,?,?,?)")) {
            for(var entry:raw.entrySet()) {
                String key=entry.getKey();int observed=entry.getValue();
                if(observed<=0 || key==null || key.length()>128) continue;
                select.setString(1,uuid.toString());select.setString(2,key);
                PlayerStatistics.Counter prior=null;
                try(var rs=select.executeQuery()) {if(rs.next()) prior=new PlayerStatistics.Counter(rs.getLong(1),rs.getInt(2));}
                PlayerStatistics.Counter next=PlayerStatistics.merge(prior,observed);
                if(next==null || next.equals(prior)) continue;
                upsert.setString(1,uuid.toString());upsert.setString(2,key);upsert.setLong(3,next.total());upsert.setInt(4,next.lastVanilla());upsert.addBatch();
            }
            upsert.executeBatch();
        }
        return null;
    });}
    CompletableFuture<Map<String,Long>> statistics(UUID uuid) {return submit(()-> {
        Map<String,Long> values=new HashMap<>();
        try(var statement=db.prepareStatement("SELECT stat_key,total FROM player_stat_counters WHERE uuid=?")) {
            statement.setString(1,uuid.toString());
            try(var rs=statement.executeQuery()) {while(rs.next()) values.put(rs.getString(1),rs.getLong(2));}
        }
        return Map.copyOf(values);
    });}
    CompletableFuture<Boolean> toggleTelegramGameChat(long user) {return submit(()-> {
        Account account=accountNow(user);
        if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        UUID uuid=account.uuid();boolean enabled=!telegramGameChatEnabled(uuid);
        execute("MERGE INTO telegram_game_chat(uuid,enabled) KEY(uuid) VALUES(?,?)",uuid.toString(),enabled);
        telegramGameChat.put(uuid,enabled);
        return enabled;
    });}
    Set<Long> adminIds() {
        Set<Long> ids=new TreeSet<>(admins);adminOverrides.forEach((id,enabled)-> {if(enabled) ids.add(id);else ids.remove(id);});
        ids.addAll(extraSupers);ids.addAll(extraRoots);if(config.superAdminUserId()>0) ids.add(config.superAdminUserId());return ids;
    }
    void requireAdmin(long user) {if(!admin(user)) throw new SecurityException("Требуются права администратора");}
    String telegramProfile(long user) {return telegramProfiles.getOrDefault(user,"Telegram-пользователь");}
    void rememberTelegram(long user,String label) {
        if(user<=0 || label==null || label.isBlank()) return;
        String bounded=label.substring(0,Math.min(label.length(),512));
        if(bounded.equals(telegramProfiles.put(user,bounded))) return;
        enqueue(()->execute("MERGE INTO telegram_profiles KEY(tg) VALUES(?,?)",user,bounded));
    }
    void requireSuper(long user) {if(!superAdmin(user)) throw new SecurityException("Требуются права суперадминистратора");}
    boolean capability(String name) {return adminCapabilities.getOrDefault(name, false);}
    void requireCapability(long user,String name) {if(superAdmin(user)) return;requireAdmin(user);if(!capability(name)) throw new SecurityException("Эта функция отключена суперадминистратором");}
    void requireAnyCapability(long user,Collection<String> names) {if(superAdmin(user)) return;requireAdmin(user);if(names.stream().noneMatch(this::capability)) throw new SecurityException("Действия с игроками отключены суперадминистратором");}
    CompletableFuture<Void> capability(long user,String name,boolean enabled) {return submit(()-> {requireSuper(user);if(!ADMIN_CAPABILITIES.contains(name)) throw new IllegalArgumentException("Неизвестная функция");execute("MERGE INTO admin_capabilities KEY(capability) VALUES(?,?)",name,enabled);adminCapabilities.put(name,enabled);log.info("Telegram "+user+": capability "+name+" → "+enabled);return null;});}
    CompletableFuture<Void> changeAdmin(long user,long target,boolean enabled) {return submit(()-> {
        requireSuper(user);if(target<=0 || superAdmin(target)) throw new IllegalArgumentException("Нельзя изменить эту роль");
        execute("MERGE INTO guard_admins KEY(tg) VALUES(?,?)",target,enabled);adminOverrides.put(target,enabled);
        log.info("Telegram "+user+": "+(enabled?"addAdmin":"removeAdmin")+" → "+target);
        return null;
    });}
    CompletableFuture<Void> changeSuper(long user,long target,boolean enabled) {return submit(()-> {
        requireRoot(user);
        if(target<=0 || rootAdmin(target) || target==user) throw new IllegalArgumentException("Root и собственную роль изменить нельзя");
        if(!enabled && !extraSupers.contains(target)) throw new IllegalArgumentException("Суперадминистратор не найден");
        if(enabled) {execute("MERGE INTO guard_supers KEY(tg) VALUES(?)",target);extraSupers.add(target);}
        else {execute("DELETE FROM guard_supers WHERE tg=?",target);extraSupers.remove(target);execute("MERGE INTO guard_admins KEY(tg) VALUES(?,?)",target,false);adminOverrides.put(target,false);}
        log.info("Telegram "+user+": "+(enabled?"addSuper":"removeSuper")+" → "+target);
        return null;
    });}
    boolean canRemoveSuper(long user,long target) {return rootAdmin(user) && extraSupers.contains(target) && target!=user && !rootAdmin(target);}
    CompletableFuture<Boolean> tag(long user,boolean toggle) {return submit(()-> {
        if(accountNow(user)==null) throw new SecurityException("Сначала привяжите персонажа");
        if(toggle) execute("UPDATE accounts SET telegram_tag=NOT telegram_tag WHERE tg=?",user);
        try(var s=db.prepareStatement("SELECT telegram_tag FROM accounts WHERE tg=?")) {s.setLong(1,user);try(var rs=s.executeQuery()) {rs.next();return rs.getBoolean(1);}}
    });}
    CompletableFuture<Void> setTag(long user,boolean enabled) {return submit(()-> {
        if(accountNow(user)==null) throw new SecurityException("Сначала привяжите персонажа");
        execute("UPDATE accounts SET telegram_tag=? WHERE tg=?",enabled,user);return null;
    });}
    long superDelay(long user) {requireSuper(user);return superDelays.getOrDefault(user,defaultOfflineSeconds());}
    CompletableFuture<Void> superDelay(long user,long seconds) {return submit(()-> {
        if(seconds== -2) {requireSuper(user);execute("DELETE FROM super_delays WHERE tg=?",user);superDelays.remove(user);log.info("Telegram "+user+": resetSuperDelay");return null;}
        requireSuper(user);if(seconds < -1 || seconds>MAX_OFFLINE_SECONDS) throw new IllegalArgumentException("Время вне диапазона");
        execute("MERGE INTO super_delays KEY(tg) VALUES(?,?)",user,seconds);superDelays.put(user,seconds);log.info("Telegram "+user+": setSuperDelay → "+seconds);return null;
    });}
    boolean enabled() { return config.enabled(); }
    long defaultOfflineSeconds() {return config.absenceMillis()/1000;}
    void world(UUID uuid,String name) {
        worlds.put(uuid,name);
        enqueue(()->execute("MERGE INTO guard_worlds KEY(uuid) VALUES(?,?)",uuid.toString(),name));
    }
    String readableLocation(String text) {
        var pattern=java.util.regex.Pattern.compile("Мир ([0-9a-fA-F-]{36}); блок (-?\\d+ -?\\d+ -?\\d+)");
        var matcher=pattern.matcher(text);StringBuilder result=new StringBuilder();
        while(matcher.find()) {
            String name=worlds.getOrDefault(UUID.fromString(matcher.group(1)),"неизвестный мир");
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement("Мир: "+name+"\nКоординаты: "+matcher.group(2)));
        }
        matcher.appendTail(result);return result.toString();
    }
    CompletableFuture<Long> offlineSeconds(long user) {return submit(()-> {
        Account a=accountNow(user);if(a==null) throw new SecurityException("Сначала привяжите персонажа");
        Long millis=personalAbsence.get(a.uuid());return millis==null ? null : millis/1000;
    });}
    CompletableFuture<Void> offlineSeconds(long user,Long seconds) {return submit(()-> {
        if(seconds!=null && (seconds< -1 || seconds>MAX_OFFLINE_SECONDS)) throw new IllegalArgumentException("Время вне диапазона");
        Account a=accountNow(user);if(a==null) throw new SecurityException("Сначала привяжите персонажа");
        execute("UPDATE accounts SET offline_seconds=? WHERE tg=?",seconds,user);
        if(seconds==null) personalAbsence.remove(a.uuid());else personalAbsence.put(a.uuid(),seconds*1000);
        return null;
    });}
    void restore(Collection<Snapshot> snapshots) {for(var v:snapshots) if(v.active()) baseline.put(v.sign(),v);}
    void attribute(BlockKey sign,UUID playerId,String playerName) {
        if(playerId!=null && playerName!=null && !playerName.isBlank()) actors.put(sign,new Actor(playerId,playerName,clock.getAsLong()+30_000));
    }
    /** Kept for compatibility with old callers that cannot identify the player. */
    void attribute(BlockKey sign,String playerName) {
        if(playerName!=null && !playerName.isBlank()) actors.put(sign,new Actor(null,playerName,clock.getAsLong()+30_000));
    }
    /** Marks the next inventory snapshot as produced by a server-side mechanism. */
    void automated(BlockKey sign) { automated.put(sign,clock.getAsLong()+30_000); }
    void presence(UUID uuid,String name,boolean online) {
        rememberKnownName(uuid,name);
        if(name!=null && !name.isBlank()) currentNames.put(uuid,name);
        if(online && linkedNames.containsKey(uuid)) linkedNames.put(uuid,name);
        long time=online ? -1 : clock.getAsLong(); presence.put(uuid,time);
        enqueue(()-> {
            execute("MERGE INTO presence KEY(uuid) VALUES(?,?)",uuid.toString(),time);
            execute("MERGE INTO known_player_names(nickname,uuid) KEY(nickname,uuid) VALUES(?,?)",name.toLowerCase(Locale.ROOT),uuid.toString());
            if(online) {
                execute("UPDATE accounts SET nickname=? WHERE uuid=?",name,uuid.toString());
                if(linkedNames.containsKey(uuid)) linkedNames.put(uuid,name);
            }
        });
    }
    private void rememberKnownName(UUID uuid,String name) {
        if(uuid==null || name==null || name.isBlank()) return;
        knownPlayerNames.computeIfAbsent(name.toLowerCase(Locale.ROOT),ignored->ConcurrentHashMap.newKeySet()).add(uuid);
    }
    UUID knownPlayerUuid(String oldName) {
        if(oldName==null) return null;
        Set<UUID> matches=knownPlayerNames.getOrDefault(oldName.toLowerCase(Locale.ROOT),Set.of());
        return matches.size()==1?matches.iterator().next():null;
    }
    String knownPlayerName(UUID uuid) {return currentNames.get(uuid);}
    void accept(Snapshot snapshot) {
        Actor attributed=actors.remove(snapshot.sign());
        Actor actorInfo=attributed!=null && attributed.expires()>=clock.getAsLong() ? attributed : null;
        Long automatedUntil=automated.remove(snapshot.sign());
        boolean automatedChange=automatedUntil!=null && automatedUntil>=clock.getAsLong();
        String actor=actorInfo==null ? null : actorInfo.name();
        Snapshot old=snapshot.active() ? baseline.put(snapshot.sign(),snapshot) : baseline.remove(snapshot.sign());
        if(old==null || !old.generation().equals(snapshot.generation()) || !old.owner().equals(snapshot.owner())) return;
        if(snapshot.active() && old.items().equals(snapshot.items())) return;
        // The owner working with their own storage is expected activity, not an incident.
        if(actorInfo!=null && snapshot.owner().equals(actorInfo.uuid())) return;
        // Hopper/crafter/furnace-style updates are catalogued but never alert the bot.
        // A real player attribution takes precedence when both happen together.
        if(automatedChange && actorInfo==null) return;
        long now=clock.getAsLong(), departed=presence.getOrDefault(snapshot.owner(),started);
        GuardConfig settings=config;
        if(!settings.enabled()) return;
        long ownDelay=personalAbsence.getOrDefault(snapshot.owner(),settings.absenceMillis());
        boolean ownEligible=ownDelay<0 || (departed>=0 && now-departed>=ownDelay);
        boolean adminEligible=departed>=0 && now-departed>=settings.absenceMillis();
        long root=settings.superAdminUserId(),rootDelay=superDelays.getOrDefault(root,settings.absenceMillis()/1000)*1000;
        boolean rootEligible=root>0 && (rootDelay<0 || (departed>=0 && now-departed>=rootDelay));
        boolean extraEligible=extraSupers.stream().anyMatch(id->eligibleSuper(id,departed,now));
        boolean trustedFriend=actorInfo!=null&&actorInfo.uuid()!=null&&friend(snapshot.owner(),actorInfo.uuid());
        boolean friendEligible=friends.getOrDefault(snapshot.owner(),Map.of()).values().stream().anyMatch(Friend::notified);
        if(ownEligible || adminEligible || rootEligible || extraEligible || friendEligible) enqueue(()->observe(old,snapshot,now,ownEligible,adminEligible,actor,actorInfo==null?null:actorInfo.uuid(),rootEligible,root,trustedFriend));
    }
    private boolean eligibleSuper(long user,long departed,long now) {long delay=superDelays.getOrDefault(user,defaultOfflineSeconds())*1000;return delay<0 || (departed>=0 && now-departed>=delay);}
    private void observe(Snapshot old,Snapshot current,long now,boolean ownEligible,boolean adminEligible,String actor,UUID actorUuid,boolean rootEligible,long root,boolean trustedFriend) throws Exception {
        Map<String,Long> changes=new TreeMap<>();
        if(current.active()) {
            Set<String> keys=new HashSet<>(old.items().keySet()); keys.addAll(current.items().keySet());
            for(String item:keys) {
                long delta=current.items().getOrDefault(item,0L)-old.items().getOrDefault(item,0L);
                if(delta!=0) changes.put(item,delta);
            }
        }
        BlockKey p=old.chests().getFirst();
        String location=readableLocation("Мир "+p.world()+"; блок "+p.x()+" "+p.y()+" "+p.z());
        Event event=new Event(0,now,old.playerName(),location,changes,!current.active(),actor,actorUuid);
        db.setAutoCommit(false);
        try {
            long id;
            try(var s=db.prepareStatement("INSERT INTO events(happened,payload,owner_uuid) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                s.setLong(1,now);s.setString(2,gson.toJson(event));s.setString(3,old.owner().toString());s.executeUpdate();
                try(var rs=s.getGeneratedKeys()) {rs.next();id=rs.getLong(1);}
            }
            Set<Long> recipients=new HashSet<>();
            Long ownerRecipient=null;
            try(var s=db.prepareStatement("SELECT tg FROM accounts WHERE uuid=? AND alerts=TRUE")) {
                s.setString(1,old.owner().toString()); try(var rs=s.executeQuery()) {if(rs.next() && ownEligible) {ownerRecipient=rs.getLong(1);recipients.add(ownerRecipient);}}
            }
            Set<Long> adminRecipients=new HashSet<>();
            for(long admin:adminIds()) if((superAdmin(admin) ? eligibleSuper(admin,presence.getOrDefault(old.owner(),started),now) : adminEligible) && adminAlertsNow(admin)) {recipients.add(admin);adminRecipients.add(admin);}
            if(trustedFriend) {recipients.clear();adminRecipients.clear();ownerRecipient=null;}
            for(Friend subscriber:friends.getOrDefault(old.owner(),Map.of()).values()) {
                if(!subscriber.notified() || Objects.equals(subscriber.uuid(),actorUuid)) continue;
                try(var s=db.prepareStatement("SELECT tg FROM accounts WHERE uuid=? AND friend_alerts=TRUE")) {
                    s.setString(1,subscriber.uuid().toString());
                    try(var rs=s.executeQuery()) {if(rs.next()) recipients.add(rs.getLong(1));}
                }
            }
            for(long recipient:recipients) {
                boolean adminChannel=adminRecipients.contains(recipient);
                queueNotification(id,recipient,old.owner(),now,Objects.equals(ownerRecipient,recipient),adminChannel,
                        new Event(id,event.time(),event.name(),event.location(),event.changes(),event.removed(),actor,actorUuid));
            }
            db.commit();
        } catch(Exception e) {db.rollback();throw e;} finally {db.setAutoCommit(true);}
        prune();
    }
    private void queueNotification(long eventId,long recipient,UUID owner,long now,boolean own,boolean admin,Event event) throws Exception {
        long existing=0;List<Event> batch=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT id,batch FROM outbox WHERE tg=? AND owner_uuid=? AND own_channel=? AND admin_channel=? AND created>? AND due=created+10000 AND batch IS NOT NULL ORDER BY id DESC LIMIT 1")) {
            s.setLong(1,recipient);s.setString(2,owner.toString());s.setBoolean(3,own);s.setBoolean(4,admin);s.setLong(5,now-10000);
            try(var rs=s.executeQuery()) {if(rs.next()) {Event[] entries=gson.fromJson(rs.getString(2),Event[].class);if(entries.length>0 && entries.length<1000 && Objects.equals(entries[0].actorUuid(),event.actorUuid()) && Objects.equals(entries[0].actor(),event.actor())) {existing=rs.getLong(1);batch.addAll(Arrays.asList(entries));}}}
        }
        batch.add(event);String body=batchText(batch,true),payload=gson.toJson(batch);
        if(existing>0) execute("UPDATE outbox SET body=?,batch=? WHERE id=?",body,payload,existing);
        else execute("INSERT INTO outbox(event_id,tg,owner_uuid,body,due,created,own_channel,admin_channel,batch) VALUES(?,?,?,?,?,?,?,?,?)",eventId,recipient,owner.toString(),body,now+10000,now,own,admin,payload);
    }
    static String batchText(List<Event> batch,boolean admin) {
        Map<String,Event> groups=new LinkedHashMap<>();
        for(Event e:batch) {
            // Keep removals and additions separate: a later return must not hide a withdrawal.
            for(boolean positive:new boolean[]{false,true}) {
                Map<String,Long> delta=new TreeMap<>();for(var change:e.changes().entrySet()) if((change.getValue()>0)==positive) delta.put(change.getKey(),change.getValue());
                if(delta.isEmpty() && (!e.removed() || positive)) continue;
                String key=e.location()+"|"+(admin?e.actor():"")+"|"+e.removed()+"|"+positive;
                Event previous=groups.get(key);
                if(previous!=null) {Map<String,Long> sum=new TreeMap<>(previous.changes());delta.forEach((k,v)->sum.merge(k,v,Long::sum));delta=sum;}
                groups.put(key,new Event(e.id(),e.time(),e.name(),e.location(),delta,e.removed(),admin?e.actor():null));
            }
        }
        StringBuilder text=new StringBuilder("🛡 Изменения хранилищ за 10 секунд · событий: "+batch.size()+"\n");
        for(Event group:groups.values()) {
            String part=eventText(group,0,12,admin);
            if(text.length()+part.length()>3300) {text.append("\nОстальные изменения — в истории.\n");break;}
            text.append('\n').append(part).append('\n');
        }
        return text.append("\nСобытия #").append(batch.getFirst().id()).append(" … #").append(batch.getLast().id()).append(". Подробности — в истории.").toString();
    }
    CompletableFuture<String> generate(long user) {return submit(()-> {
        if(accountNow(user)!=null) return "Персонаж уже привязан. Откройте кабинет.";
        long now=clock.getAsLong(); codes.entrySet().removeIf(e->e.getValue().expires()<=now || e.getValue().user()==user);
        String code;
        do {StringBuilder value=new StringBuilder(LINK_CODE_LENGTH);for(int i=0;i<LINK_CODE_LENGTH;i++) value.append(LINK_CODE_ALPHABET[random.nextInt(LINK_CODE_ALPHABET.length)]);code=value.toString();} while(codes.containsKey(code));
        codes.put(code,new Code(user,now+CODE_TTL));
        return "Выполните в Minecraft под своим персонажем:\n"+TelegramEmojiMarkup.plainCode("/vtrack link "+code)+"\n\nКод одноразовый, действует 10 минут. Не передавайте его другим игрокам. После выполнения нажмите «Обновить кабинет».";
    });}
    CompletableFuture<String> link(UUID uuid,String name,String token) {return linkOutcome(uuid,name,token).thenApply(LinkResult::message);}
    CompletableFuture<String> unlink(UUID uuid) {return submit(()-> {
        Long telegram=null;
        try(var statement=db.prepareStatement("SELECT tg FROM accounts WHERE uuid=?")) {
            statement.setString(1,uuid.toString());
            try(var result=statement.executeQuery()) {if(result.next()) telegram=result.getLong(1);}
        }
        if(telegram==null) return "Ваш персонаж не привязан к Telegram.";
        long telegramId=telegram;
        execute("DELETE FROM accounts WHERE uuid=?",uuid.toString());
        execute("DELETE FROM outbox WHERE tg=? AND admin_channel=FALSE",telegramId);
        codes.entrySet().removeIf(entry->entry.getValue().user()==telegramId);
        linkedNames.remove(uuid);
        personalAbsence.remove(uuid);
        return "Персонаж отвязан от Telegram. Для повторной привязки получите новый код в боте.";
    });}
    CompletableFuture<LinkResult> linkOutcome(UUID uuid,String name,String token) {return submit(()-> {
        String normalized=token==null?"":token.strip().toUpperCase(Locale.ROOT);Code code=codes.get(normalized);
        if(code==null || code.expires()<=clock.getAsLong()) return new LinkResult("Код неверный или истёк. Получите новый в личном чате бота.",false);
        if(accountNow(code.user())!=null) return new LinkResult("Этот Telegram-аккаунт уже привязан.",false);
        try(var s=db.prepareStatement("SELECT tg FROM accounts WHERE uuid=?")) {
            s.setString(1,uuid.toString());try(var rs=s.executeQuery()) {if(rs.next()) return new LinkResult("Ваш персонаж уже привязан к Telegram. Повторная привязка запрещена.",false);}
        }
        execute("INSERT INTO accounts(tg,uuid,nickname,alerts,bastion_pending) VALUES(?,?,?,TRUE,TRUE)",code.user(),uuid.toString(),name);
        linkedNames.put(uuid,name);
        currentNames.put(uuid,name);
        rememberKnownName(uuid,name);
        execute("MERGE INTO known_player_names(nickname,uuid) KEY(nickname,uuid) VALUES(?,?)",name.toLowerCase(Locale.ROOT),uuid.toString());
        codes.remove(normalized);
        return new LinkResult("Персонаж "+name+" привязан к Telegram. В боте нажмите «Обновить кабинет» или /menu. Уведомления о ваших хранилищах включены.",true);
    });}
    CompletableFuture<Boolean> bastionPending(UUID uuid) {return submit(()->{
        try(var s=db.prepareStatement("SELECT bastion_pending FROM accounts WHERE uuid=?")) {
            s.setString(1,uuid.toString());try(var rs=s.executeQuery()) {return rs.next() && rs.getBoolean(1);}
        }
    });}
    CompletableFuture<Void> bastionGranted(UUID uuid) {return submit(()->{execute("UPDATE accounts SET bastion_pending=FALSE WHERE uuid=?",uuid.toString());return null;});}
    CompletableFuture<Account> account(long user) {return submit(()->accountNow(user));}
    record PendingSupport(SupportTicket ticket,List<Integer> sources) {}
    CompletableFuture<Void> saveSupport(SupportTicket ticket) {return submit(()->supportRepository.saveSupport(ticket));}
    CompletableFuture<Void> queueSupport(SupportTicket ticket,List<Integer> sources) {return submit(()->supportRepository.queueSupport(ticket,sources));}
    CompletableFuture<List<PendingSupport>> pendingSupport() {return submit(()->supportRepository.pendingSupport());}
    CompletableFuture<Void> supportDeliveryProgress(SupportTicket ticket,boolean complete) {return submit(()->supportRepository.supportDeliveryProgress(ticket,complete));}
    CompletableFuture<Void> supportDeliveryRetry(String id) {return submit(()->supportRepository.supportDeliveryRetry(id));}
    CompletableFuture<Void> saveUnban(SupportTicket ticket) {return submit(()->supportRepository.saveUnban(ticket));}
    CompletableFuture<UnbanState> unbanState(String id) {return submit(()->supportRepository.unbanState(id));}
    CompletableFuture<Void> unbanInvite(String id,long until,int message,long actor) {return submit(()->supportRepository.unbanInvite(id,until,message,actor));}
    CompletableFuture<Void> unbanAccess(String id,long until,String reason,long expiry,String source) {return submit(()->supportRepository.unbanAccess(id,until,reason,expiry,source));}
    CompletableFuture<Void> clearUnbanAccess(String id) {return submit(()->supportRepository.clearUnbanAccess(id));}
    CompletableFuture<List<UnbanState>> unbanPending() {return submit(()->supportRepository.unbanPending());}
    CompletableFuture<Void> supportActor(String id,long actor) {return submit(()->supportRepository.supportActor(id,actor));}
    CompletableFuture<Boolean> supportStatus(String id,String expected,String status) {return submit(()->supportRepository.supportStatus(id,expected,status));}
    CompletableFuture<Boolean> supportChange(SupportTicket ticket,String expected) {return submit(()->supportRepository.supportChange(ticket,expected));}
    CompletableFuture<Integer> unbanAttempt(long telegram,UUID uuid,boolean linked) {return submit(()->supportRepository.unbanAttempt(telegram,uuid,linked));}
    CompletableFuture<SupportTicket> support(String id) {return submit(()->supportRepository.support(id));}
    ItemSorting.Mode itemSortMode(long user) {return itemSortModes.getOrDefault(user,ItemSorting.Mode.MINECRAFT);}
    Map<String,Long> itemSearchCounts() {return Map.copyOf(itemSearchCounts);}
    CompletableFuture<Void> itemSortMode(long user,ItemSorting.Mode mode) {return submit(()->{
        if(accountNow(user)==null) throw new SecurityException("Сначала привяжите персонажа");
        execute("MERGE INTO item_sort_preferences(tg,mode) KEY(tg) VALUES(?,?)",user,mode.name());
        itemSortModes.put(user,mode);
        return null;
    });}
    CompletableFuture<Void> recordItemSearch(String item) {
        if(item==null || !item.matches("[A-Z0-9_]{1,128}")) return CompletableFuture.completedFuture(null);
        return submit(()->{
            long count=itemSearchCounts.getOrDefault(item,0L)+1;
            execute("MERGE INTO item_search_counts(item,searches) KEY(item) VALUES(?,?)",item,count);
            itemSearchCounts.put(item,count);
            return null;
        });
    }
    List<String> linkedNames() {return List.copyOf(linkedNames.values());}
    boolean linked(UUID uuid) {return linkedNames.containsKey(uuid);}
    List<OnlineLinked> onlineLinked(UUID exclude) {
        return linkedNames.entrySet().stream()
                .filter(entry->!entry.getKey().equals(exclude) && presence.getOrDefault(entry.getKey(),0L)==-1L)
                .map(entry->new OnlineLinked(entry.getKey(),entry.getValue()))
                .sorted(Comparator.comparing(OnlineLinked::name,String.CASE_INSENSITIVE_ORDER)).toList();
    }
    CompletableFuture<Account> linkedPlayer(String name) {return submit(()->{
        try(var s=db.prepareStatement("SELECT uuid,nickname,alerts FROM accounts WHERE LOWER(nickname)=LOWER(?) LIMIT 2")) {
            s.setString(1,name);try(var rs=s.executeQuery()) {if(!rs.next()) return null;
                Account found=new Account(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getBoolean(3));
                if(rs.next()) throw new IllegalArgumentException("Ник неоднозначен. Уточните имя игрока.");return found;}
        }
    });}
    CompletableFuture<Account> linkedPlayer(UUID uuid) {return submit(()->{
        try(var s=db.prepareStatement("SELECT uuid,nickname,alerts FROM accounts WHERE uuid=?")) {
            s.setString(1,uuid.toString());try(var rs=s.executeQuery()) {return rs.next()?new Account(uuid,rs.getString(2),rs.getBoolean(3)):null;}
        }
    });}
    CompletableFuture<DirectMessage> directMessage(UUID sender,String senderName,UUID recipient,String recipientName,String body) {
        return directMessage(sender,senderName,recipient,recipientName,body,true);
    }
    CompletableFuture<DirectMessage> directMessage(UUID sender,String senderName,UUID recipient,String recipientName,String body,boolean fromGame) {return submit(()->{
        if(sender.equals(recipient)) throw new IllegalArgumentException("Нельзя написать самому себе.");
        if(body==null||body.isBlank()||body.length()>500||body.contains("\n")||body.contains("\r")) throw new IllegalArgumentException("Сообщение: одна строка, до 500 символов.");
        if(linkedPlayerNow(recipient)==null) throw new IllegalArgumentException("У получателя нет связанного Telegram-аккаунта.");
        long now=clock.getAsLong();
        boolean suppressNotice=fromGame && !gameMessageNotificationsNow(recipient);
        try(var s=db.prepareStatement("INSERT INTO player_messages(sender_uuid,recipient_uuid,sender_name,recipient_name,body,created,from_game,notified_at) VALUES(?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,sender.toString());s.setString(2,recipient.toString());s.setString(3,senderName);s.setString(4,recipientName);s.setString(5,body);s.setLong(6,now);s.setBoolean(7,fromGame);
            if(suppressNotice) s.setLong(8,now);else s.setNull(8,Types.BIGINT);
            s.executeUpdate();
            try(var keys=s.getGeneratedKeys()) {keys.next();return new DirectMessage(keys.getLong(1),sender,recipient,senderName,body,now);}
        }
    });}
    private boolean gameMessageNotificationsNow(UUID recipient) throws SQLException {
        try(var s=db.prepareStatement("SELECT game_message_notifications FROM accounts WHERE uuid=?")) {
            s.setString(1,recipient.toString());try(var rs=s.executeQuery()) {return rs.next()&&rs.getBoolean(1);}
        }
    }
    CompletableFuture<Boolean> gameMessageNotifications(long user) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        return gameMessageNotificationsNow(account.uuid());
    });}
    CompletableFuture<Void> toggleGameMessageNotifications(long user) {return submit(()->{
        Account account=accountNow(user);if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        boolean enabled=!gameMessageNotificationsNow(account.uuid());
        execute("UPDATE accounts SET game_message_notifications=? WHERE tg=?",enabled,user);
        if(!enabled) execute("UPDATE player_messages SET notified_at=? WHERE recipient_uuid=? AND from_game=TRUE AND read_at IS NULL AND notified_at IS NULL",clock.getAsLong(),account.uuid().toString());
        return null;
    });}
    private Account linkedPlayerNow(UUID uuid) throws SQLException {
        try(var s=db.prepareStatement("SELECT nickname,alerts FROM accounts WHERE uuid=?")) {s.setString(1,uuid.toString());try(var rs=s.executeQuery()) {return rs.next()?new Account(uuid,rs.getString(1),rs.getBoolean(2)):null;}}
    }
    CompletableFuture<Void> directDelivered(long id,UUID recipient) {return submit(()->{execute("UPDATE player_messages SET read_at=? WHERE id=? AND recipient_uuid=? AND read_at IS NULL",clock.getAsLong(),id,recipient.toString());return null;});}
    CompletableFuture<List<PendingDirect>> pendingDirectNotifications(int limit) {return submit(()->{
        List<PendingDirect> result=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT m.id,a.tg,m.sender_uuid,m.sender_name,m.body FROM player_messages m JOIN accounts a ON a.uuid=m.recipient_uuid WHERE m.read_at IS NULL AND m.notified_at IS NULL ORDER BY m.id LIMIT ?")) {
            s.setInt(1,Math.max(1,Math.min(limit,1000)));
            try(var rs=s.executeQuery()) {while(rs.next()) result.add(new PendingDirect(rs.getLong(1),rs.getLong(2),UUID.fromString(rs.getString(3)),rs.getString(4),rs.getString(5)));}
        }return List.copyOf(result);
    });}
    CompletableFuture<Void> directNotified(long id) {return submit(()->{execute("UPDATE player_messages SET notified_at=? WHERE id=? AND notified_at IS NULL",clock.getAsLong(),id);return null;});}
    CompletableFuture<Integer> notificationTopic(long user) {return submit(()->{
        try(var s=db.prepareStatement("SELECT thread_id FROM telegram_notification_topics WHERE tg=?")) {s.setLong(1,user);try(var rs=s.executeQuery()) {return rs.next()?rs.getInt(1):0;}}
    });}
    CompletableFuture<Void> notificationTopic(long user,int threadId) {return submit(()->{
        if(threadId<=0) throw new IllegalArgumentException("Некорректный ID темы");
        execute("MERGE INTO telegram_notification_topics(tg,thread_id) KEY(tg) VALUES(?,?)",user,threadId);return null;
    });}
    CompletableFuture<List<Dialog>> dialogs(long user,int page,int size) {return submit(()->{
        Account owner=accountNow(user);if(owner==null) throw new SecurityException("Сначала привяжите персонажа");
        String id=owner.uuid().toString();List<Dialog> result=new ArrayList<>();
        String sql="SELECT peer,MAX(id) latest,SUM(unread) unread FROM (SELECT id,CASE WHEN sender_uuid=? THEN recipient_uuid ELSE sender_uuid END peer,CASE WHEN recipient_uuid=? AND read_at IS NULL THEN 1 ELSE 0 END unread FROM player_messages WHERE sender_uuid=? OR recipient_uuid=?) GROUP BY peer ORDER BY CASE WHEN SUM(unread)>0 THEN 0 ELSE 1 END,MAX(id) DESC LIMIT ? OFFSET ?";
        try(var s=db.prepareStatement(sql)) {for(int i=1;i<=4;i++)s.setString(i,id);s.setInt(5,size+1);s.setLong(6,(long)Math.max(0,page)*size);
            try(var rs=s.executeQuery()) {while(rs.next()) {UUID peer=UUID.fromString(rs.getString(1));result.add(new Dialog(peer,displayNameNow(peer),rs.getLong(3),rs.getLong(2)));}}
        }return List.copyOf(result);
    });}
    private String displayNameNow(UUID uuid) throws SQLException {
        Account linked=linkedPlayerNow(uuid);if(linked!=null) return linked.name();
        try(var s=db.prepareStatement("SELECT CASE WHEN sender_uuid=? THEN sender_name ELSE recipient_name END FROM player_messages WHERE sender_uuid=? OR recipient_uuid=? ORDER BY id DESC LIMIT 1")) {
            String id=uuid.toString();s.setString(1,id);s.setString(2,id);s.setString(3,id);try(var rs=s.executeQuery()) {return rs.next()?rs.getString(1):uuid.toString().substring(0,8);}
        }
    }
    CompletableFuture<String> directDisplayName(UUID uuid) {return submit(()->displayNameNow(uuid));}
    CompletableFuture<Conversation> conversation(long user,UUID peer,int page,int size) {return submit(()->{
        Account owner=accountNow(user);if(owner==null) throw new SecurityException("Сначала привяжите персонажа");
        String self=owner.uuid().toString(),other=peer.toString();
        execute("UPDATE player_messages SET read_at=? WHERE recipient_uuid=? AND sender_uuid=? AND read_at IS NULL",clock.getAsLong(),self,other);
        List<DirectMessage> rows=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT id,sender_uuid,recipient_uuid,sender_name,body,created FROM player_messages WHERE (sender_uuid=? AND recipient_uuid=?) OR (sender_uuid=? AND recipient_uuid=?) ORDER BY id DESC LIMIT ? OFFSET ?")) {
            s.setString(1,self);s.setString(2,other);s.setString(3,other);s.setString(4,self);s.setInt(5,size+1);s.setLong(6,(long)Math.max(0,page)*size);
            try(var rs=s.executeQuery()) {while(rs.next()) rows.add(new DirectMessage(rs.getLong(1),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getLong(6)));}
        }
        boolean older=rows.size()>size;if(older) rows.removeLast();Collections.reverse(rows);return new Conversation(List.copyOf(rows),older);
    });}
    CompletableFuture<Boolean> donationsVisible() {return submit(()->{
        try(var s=db.createStatement();var rs=s.executeQuery("SELECT visible FROM donation_settings WHERE id=1")) {return rs.next()&&rs.getBoolean(1);}
    });}
    CompletableFuture<Void> donationsVisible(long user,boolean visible) {return submit(()->{
        requireRoot(user);execute("MERGE INTO donation_settings KEY(id) VALUES(1,?)",visible);return null;
    });}
    private Account accountNow(long user) throws Exception {
        try(var s=db.prepareStatement("SELECT uuid,nickname,alerts FROM accounts WHERE tg=?")) {
            s.setLong(1,user);try(var rs=s.executeQuery()) {return rs.next() ? new Account(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getBoolean(3)) : null;}
        }
    }
    CompletableFuture<Void> toggleOwn(long user) {return submit(()-> {execute("UPDATE accounts SET alerts=NOT alerts WHERE tg=?",user);return null;});}
    CompletableFuture<Boolean> friendAlerts(long user) {return submit(()->friendAlertsNow(user));}
    private boolean friendAlertsNow(long user) throws Exception {
        try(var s=db.prepareStatement("SELECT friend_alerts FROM accounts WHERE tg=?")) {
            s.setLong(1,user);try(var rs=s.executeQuery()) {if(!rs.next()) throw new SecurityException("Сначала привяжите персонажа");return rs.getBoolean(1);}
        }
    }
    CompletableFuture<Void> toggleFriendAlerts(long user) {return submit(()->{
        if(accountNow(user)==null) throw new SecurityException("Сначала привяжите персонажа");
        execute("UPDATE accounts SET friend_alerts=NOT friend_alerts WHERE tg=?",user);
        if(!friendAlertsNow(user)) execute("DELETE FROM outbox WHERE tg=? AND own_channel=FALSE AND admin_channel=FALSE",user);
        return null;
    });}
    CompletableFuture<Boolean> adminAlerts(long user) {return submit(()->adminAlertsNow(user));}
    private boolean adminAlertsNow(long user) throws Exception {
        try(var s=db.prepareStatement("SELECT alerts FROM preferences WHERE tg=?")) {
            s.setLong(1,user);try(var rs=s.executeQuery()) {return !rs.next() || rs.getBoolean(1);}
        }
    }
    CompletableFuture<Void> toggleAdmin(long user) {return submit(()-> {
        if(!admin(user)) throw new SecurityException("Нет доступа");
        execute("MERGE INTO preferences KEY(tg) VALUES(?,?)",user,!adminAlertsNow(user));return null;
    });}
    CompletableFuture<List<Event>> history(long user,int page,int size) {return submit(()-> {
        if(!admin(user)) throw new SecurityException("Нет доступа");
        prune(); List<Event> result=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT id,payload FROM events WHERE happened>=? ORDER BY id DESC LIMIT ? OFFSET ?")) {
            s.setLong(1,clock.getAsLong()-RETENTION);s.setInt(2,size);s.setLong(3,(long)Math.max(0,page)*size);
            try(var rs=s.executeQuery()) {while(rs.next()) {Event e=gson.fromJson(rs.getString(2),Event.class);result.add(new Event(rs.getLong(1),e.time(),e.name(),readableLocation(e.location()),e.changes(),e.removed(),e.actor(),e.actorUuid()));}}
        }
        return result;
    });}
    CompletableFuture<List<Event>> searchHistory(long user,String query,int page,int size) {return submit(()-> {
        requireAdmin(user);String needle=query.toLowerCase(Locale.ROOT).replace('ё','е').trim();
        List<Event> result=new ArrayList<>();long skip=(long)Math.max(0,page)*size;
        try(var s=db.prepareStatement("SELECT id,payload FROM events WHERE happened>=? ORDER BY id DESC")) {
            s.setLong(1,clock.getAsLong()-RETENTION);
            try(var rs=s.executeQuery()) {while(rs.next()) {
                Event e=gson.fromJson(rs.getString(2),Event.class);long id=rs.getLong(1);
                String searchable="#"+id+" "+e.name()+" "+Objects.toString(e.actor(),"")+" "+readableLocation(e.location())+" "+e.changes().keySet()+" "+e.changes().keySet().stream().map(RussianItems::name).toList();
                if(!searchable.toLowerCase(Locale.ROOT).replace('ё','е').contains(needle)) continue;
                if(skip-->0) continue;
                result.add(new Event(id,e.time(),e.name(),readableLocation(e.location()),e.changes(),e.removed(),e.actor(),e.actorUuid()));
                if(result.size()>=size) break;
            }}
        }return List.copyOf(result);
    });}
    CompletableFuture<List<Event>> ownHistory(long user,int page,int size) {return submit(()-> {
        Account a=accountNow(user);if(a==null) throw new SecurityException("Нет привязки");
        List<Event> result=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT id,payload FROM events WHERE owner_uuid=? AND happened>=? ORDER BY id DESC LIMIT ? OFFSET ?")) {
            s.setString(1,a.uuid().toString());s.setLong(2,clock.getAsLong()-2L*86400_000);s.setInt(3,size);s.setLong(4,(long)Math.max(0,page)*size);
            try(var rs=s.executeQuery()) {while(rs.next()) {Event e=gson.fromJson(rs.getString(2),Event.class);result.add(new Event(rs.getLong(1),e.time(),e.name(),readableLocation(e.location()),e.changes(),e.removed(),e.actor(),admin(user)?e.actorUuid():null));}}
        }return result;
    });}
    CompletableFuture<Event> ownEvent(long user,long id) {return submit(()-> {
        Account a=accountNow(user);if(a==null) throw new SecurityException("Нет привязки");
        try(var s=db.prepareStatement("SELECT payload FROM events WHERE id=? AND owner_uuid=? AND happened>=?")) {
            s.setLong(1,id);s.setString(2,a.uuid().toString());s.setLong(3,clock.getAsLong()-2L*86400_000);
            try(var rs=s.executeQuery()) {if(!rs.next()) return null;Event e=gson.fromJson(rs.getString(1),Event.class);return new Event(id,e.time(),e.name(),readableLocation(e.location()),e.changes(),e.removed(),e.actor(),admin(user)?e.actorUuid():null);}
        }
    });}
    CompletableFuture<Event> event(long user,long id) {return submit(()-> {
        if(!admin(user)) throw new SecurityException("Нет доступа");
        try(var s=db.prepareStatement("SELECT payload FROM events WHERE id=? AND happened>=?")) {
            s.setLong(1,id);s.setLong(2,clock.getAsLong()-RETENTION);
            try(var rs=s.executeQuery()) {if(!rs.next()) return null;Event e=gson.fromJson(rs.getString(1),Event.class);return new Event(id,e.time(),e.name(),readableLocation(e.location()),e.changes(),e.removed(),e.actor(),e.actorUuid());}
        }
    });}
    CompletableFuture<List<Delivery>> deliveries() {return submit(()-> {
        prune(); if(!config.enabled()) return List.of();
        List<Delivery> result=new ArrayList<>();List<Long> discard=new ArrayList<>();
        try(var s=db.prepareStatement("SELECT id,tg,owner_uuid,body,own_channel,admin_channel,batch FROM outbox WHERE due<=? ORDER BY id LIMIT 20")) {
            s.setLong(1,clock.getAsLong());try(var rs=s.executeQuery()) {while(rs.next()) {
                long id=rs.getLong(1),tg=rs.getLong(2);Account a=accountNow(tg);
                UUID storageOwner=UUID.fromString(rs.getString(3));
                boolean friendChannel=!rs.getBoolean(5)&&!rs.getBoolean(6);
                boolean friendAllowed=friendChannel&&a!=null&&friendAlertsNow(tg)
                        &&Optional.ofNullable(friends.getOrDefault(storageOwner,Map.of()).get(a.uuid())).map(Friend::notified).orElse(false);
                if((rs.getBoolean(6) && admin(tg) && adminAlertsNow(tg)) || (rs.getBoolean(5) && a!=null && a.notifications() && a.uuid().equals(storageOwner)) || friendAllowed) {
                    boolean canModerate=admin(tg);
                    Event[] batch=rs.getString(7)==null?new Event[0]:gson.fromJson(rs.getString(7),Event[].class);
                    String text=batch.length==0?rs.getString(4):batchText(Arrays.asList(batch),true);
                    Event first=batch.length==0?null:batch[0];
                    // Old batches may contain several actors: never attach one player's actions to those.
                    boolean single=first!=null && Arrays.stream(batch).allMatch(e->Objects.equals(e.actorUuid(),first.actorUuid()));
                    result.add(new Delivery(id,tg,readableLocation(text),canModerate&&single?first.actorUuid():null,canModerate&&single?first.actor():null));
                }
                else discard.add(id);
            }}
        }
        for(long id:discard) execute("DELETE FROM outbox WHERE id=?",id);
        return result;
    });}
    CompletableFuture<Void> delivered(long id,boolean success) {return submit(()-> {
        if(success) execute("DELETE FROM outbox WHERE id=?",id);
        else execute("UPDATE outbox SET due=? WHERE id=?",clock.getAsLong()+300_000,id);
        return null;
    });}
    private void prune() throws Exception {
        long now=clock.getAsLong();if(now-lastPrune<60_000) return;
        execute("DELETE FROM outbox WHERE created<?",now-RETENTION);
        execute("DELETE FROM events WHERE happened<?",now-RETENTION);lastPrune=now;
    }
    static String eventText(Event event,int offset,int limit) {
        return eventText(event,offset,limit,true);
    }
    static String eventText(Event event,int offset,int limit,boolean showActor) {
        StringBuilder text=new StringBuilder(event.name()).append(" • ")
                .append(java.time.Instant.ofEpochMilli(event.time()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")))
                .append('\n').append(event.location()).append('\n');
        if(event.removed()) {
            text.append("Хранилище снято с учёта: табличка/контейнер удалены или регистрация отменена. Это не подтверждение кражи вещей.");
            if(showActor) text.append("\nКто изменил: ").append(event.actor()==null ? "не определено" : event.actor());
            return text.toString();
        }
        var rows=event.changes().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        for(int i=offset;i<Math.min(rows.size(),offset+limit);i++) {
            var r=rows.get(i);text.append(TelegramItemIcons.label(r.getKey())).append(": ").append(r.getValue()>0 ? "+" : "").append(r.getValue()).append(" шт.\n");
        }
        if(rows.size()>offset+limit) text.append("Ещё позиций: ").append(rows.size()-offset-limit).append('\n');
        if(showActor) text.append("Кто изменил: ").append(event.actor()==null ? "не определено" : event.actor()).append('\n');
        return text.toString().stripTrailing();
    }
    private void execute(String sql,Object... args) throws SQLException {
        try(var s=db.prepareStatement(sql)) {for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);s.executeUpdate();}
    }
    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future=new CompletableFuture<>();
        try {disk.execute(()-> {try {future.complete(task.call());} catch(Exception e) {future.completeExceptionally(e);}});}
        catch(RejectedExecutionException e) {future.completeExceptionally(e);}return future;
    }
    private void enqueue(Checked task) {submit(()-> {task.run();return null;}).exceptionally(e-> {log.severe("Ошибка сохранения охраны: "+e.getClass().getSimpleName()+". Проверьте guard-data и доступ к диску.");return null;});}
    @FunctionalInterface private interface Checked {void run() throws Exception;}
    @Override public void close() {
        enqueue(()-> {try {execute("UPDATE presence SET departed=? WHERE departed<0",clock.getAsLong());} finally {db.close();}});
        disk.shutdown();try {if(!disk.awaitTermination(10,TimeUnit.SECONDS)) log.severe("Охрана не завершила сохранение за 10 секунд.");}
        catch(InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
