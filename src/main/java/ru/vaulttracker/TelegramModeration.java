package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

/** Executes only fixed, authorized actions. Telegram text is never a console command. */
class TelegramModeration {
    record Person(UUID uuid,String name,boolean online,String details) {}
    private final JavaPlugin plugin;
    private final GuardService guard;
    TelegramModeration(JavaPlugin plugin,GuardService guard) {this.plugin=plugin;this.guard=guard;}
    private <T> CompletableFuture<T> global(Callable<T> job) {
        CompletableFuture<T> result=new CompletableFuture<>();
        try {plugin.getServer().getGlobalRegionScheduler().run(plugin,task-> {if(result.isDone()) return;try {result.complete(job.call());} catch(Exception e) {result.completeExceptionally(e);}});}
        catch(Exception e) {result.completeExceptionally(e);}return result.orTimeout(15,TimeUnit.SECONDS);
    }
    CompletableFuture<List<Person>> players(long user,String mode) {return global(()-> {
        guard.requireAdmin(user);
        if(mode.equals("banned")) guard.requireSuper(user);
        Map<UUID,Person> people=new HashMap<>();
        if(mode.equals("banned")) {
            ProfileBanList bans=plugin.getServer().getBanList(BanList.Type.PROFILE);
            for(var entry:bans.<org.bukkit.BanEntry<com.destroystokyo.paper.profile.PlayerProfile>>getEntries()) {
                var profile=entry.getBanTarget();if(profile==null || profile.getId()==null) continue;
                if(entry.getExpiration()!=null && entry.getExpiration().before(new Date())) continue;
                people.put(profile.getId(),new Person(profile.getId(),Objects.toString(profile.getName(),profile.getId().toString()),false,
                        "Причина: "+Objects.toString(entry.getReason(),"не указана")+"\nДо: "+Objects.toString(entry.getExpiration(),"бессрочно")));
            }
        } else {
            Collection<? extends OfflinePlayer> source=mode.equals("online") ? plugin.getServer().getOnlinePlayers() : Arrays.asList(plugin.getServer().getOfflinePlayers());
            for(OfflinePlayer p:source) people.put(p.getUniqueId(),person(p));
            if(!mode.equals("online")) for(Player p:plugin.getServer().getOnlinePlayers()) people.put(p.getUniqueId(),person(p));
        }
        return people.values().stream().sorted(Comparator.comparing(Person::name,String.CASE_INSENSITIVE_ORDER)).toList();
    });}
    private Person person(OfflinePlayer p) {
        ProfileBanList bans=plugin.getServer().getBanList(BanList.Type.PROFILE);
        var ban=bans.getBanEntry(p.getPlayerProfile());
        String banInfo=ban==null ? "" : "\nПричина бана: "+Objects.toString(ban.getReason(),"нет")+"\nБан до: "+Objects.toString(ban.getExpiration(),"бессрочно");
        return new Person(p.getUniqueId(),Objects.toString(p.getName(),p.getUniqueId().toString()),p.isOnline(),
                "UUID: "+p.getUniqueId()+"\nОнлайн: "+p.isOnline()+"\nOP: "+p.isOp()+"\nЗабанен: "+p.isBanned()
                +"\nПервый вход: "+date(p.getFirstPlayed())+"\nПоследний вход: "+date(p.getLastLogin())+"\nПоследний выход: "+date(p.getLastSeen())+banInfo);
    }
    private static String date(long ms) {return ms<=0 ? "нет данных" : java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toString();}
    CompletableFuture<Person> info(long user,UUID uuid) {
        return global(()-> {guard.requireAdmin(user);return person(plugin.getServer().getOfflinePlayer(uuid));}).thenCompose(base-> {
            Player online=plugin.getServer().getPlayer(uuid);if(online==null) return CompletableFuture.completedFuture(base);
            CompletableFuture<Person> result=new CompletableFuture<>();
            online.getScheduler().run(plugin,t-> {
                if(result.isDone()) return;
                try {guard.requireAdmin(user);var loc=online.getLocation();result.complete(new Person(base.uuid(),base.name(),true,base.details()+"\nМир: "+loc.getWorld().getName()
                        +"\nКоординаты: "+loc.getBlockX()+" "+loc.getBlockY()+" "+loc.getBlockZ()+"\nРежим: "+online.getGameMode()+"\nЗдоровье: "+online.getHealth()));}
                catch(Exception e) {result.completeExceptionally(e);}
            },()->result.complete(base));return result.orTimeout(15,TimeUnit.SECONDS);
        });
    }
    CompletableFuture<String> act(long user,String operation,UUID uuid) {
        if(operation.equals("kick")) return global(()-> {
            guard.requireAdmin(user);OfflinePlayer offline=plugin.getServer().getOfflinePlayer(uuid);return offline.getPlayer();
        }).thenCompose(player-> {
            if(player==null) return CompletableFuture.completedFuture("Игрок уже не в сети.");
            CompletableFuture<String> result=new CompletableFuture<>();
            player.getScheduler().run(plugin,task-> {
                if(result.isDone()) return;
                try {guard.requireAdmin(user);player.kick(Component.text("Пока идёт расследование"));audit(user,operation,uuid.toString());result.complete("Игрок отключён от сервера.");}
                catch(Exception e) {result.completeExceptionally(e);}
            },()->result.complete("Игрок уже не в сети."));return result.orTimeout(15,TimeUnit.SECONDS);
        });
        return global(()-> {
            guard.requireAdmin(user);if(operation.equals("unban") || operation.equals("removeLp")) guard.requireSuper(user);
            OfflinePlayer target=plugin.getServer().getOfflinePlayer(uuid);
            ProfileBanList bans=plugin.getServer().getBanList(BanList.Type.PROFILE);
            if(operation.equals("ban")) {
                if(bans.isBanned(target.getPlayerProfile())) return "Игрок уже забанен. Существующий бан сохранён.";
                bans.addBan(target.getPlayerProfile(),"Пока идёт расследование",java.time.Duration.ofMinutes(5),"Telegram "+user);
                Player online=target.getPlayer();
                if(online!=null) online.getScheduler().run(plugin,t->online.kick(Component.text("Бан на 5 минут: пока идёт расследование")),null);
                audit(user,operation,uuid.toString());return "Бан на 5 минут: пока идёт расследование.";
            }
            if(operation.equals("unban")) {bans.pardon(target.getPlayerProfile());audit(user,operation,uuid.toString());return "Бан снят.";}
            if(operation.equals("removeLp")) {
                if(plugin.getServer().getPluginManager().getPlugin("LuckPerms")==null) return "LuckPerms не установлен.";
                boolean sent=plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"lp user "+uuid+" parent remove admin");
                audit(user,operation,uuid.toString());return sent ? "Команда удаления группы admin отправлена LuckPerms. Результат — в консоли сервера. OP и другие группы не изменяются." : "LuckPerms не принял команду; проверьте консоль.";
            }
            throw new IllegalArgumentException("Неизвестное действие");
        });
    }
    CompletableFuture<String> broadcast(long user,String text) {return global(()-> {
        guard.requireSuper(user);if(text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r")) throw new IllegalArgumentException("Сообщение: одна строка, до 500 символов");
        Component message=Component.text("[Консоль] "+text);
        for(Player p:plugin.getServer().getOnlinePlayers()) p.getScheduler().run(plugin,t->p.sendMessage(message),null);
        plugin.getServer().getConsoleSender().sendMessage(message);audit(user,"chat",text);return "Сообщение отправлено в игровой чат.";
    });}
    private void audit(long user,String action,String target) {plugin.getLogger().info("Telegram "+user+": "+action+" → "+target);}
}
