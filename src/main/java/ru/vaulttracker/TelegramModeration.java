package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

/** Executes only fixed, authorized actions. Telegram text is never a console command. */
class TelegramModeration {
    private static final List<String> PLAYER_CAPABILITIES=GuardService.ADMIN_CAPABILITIES.stream().filter(c->!Set.of("chat","manageAdmins").contains(c)).toList();
    record Person(UUID uuid,String name,boolean online,String details,boolean vanished,boolean op,boolean banned,long firstPlayed,long lastLogin,long lastSeen) {
        Person(UUID uuid,String name,boolean online,String details) {this(uuid,name,online,details,false,false,false,0,0,0);}
        Person(UUID uuid,String name,boolean online,String details,boolean vanished) {this(uuid,name,online,details,vanished,false,false,0,0,0);}
    }
    record ItemView(String label,int amount,List<ItemView> contents,boolean container) {
        ItemView(String label,int amount,List<ItemView> contents) { this(label,amount,contents,!contents.isEmpty()); }
        boolean shulker() { return container; }
    }
    record CabinetProfile(boolean online,Double health,double maxHealth,Integer food,String world,int x,int y,int z,long firstPlayed,Long playtimeSeconds) {}
    private record CabinetSeed(Player online,Location location,List<java.nio.file.Path> folders,double offlineMax,long firstPlayed) {}
    private final JavaPlugin plugin;
    private final GuardService guard;
    private final SearchCompass searchCompass;
    private final ChildPlayers children;
    private final DonationPremium premium;
    private final Map<UUID,Boolean> lpAdminState=new ConcurrentHashMap<>();
    private volatile Plugin flexityPlugin;
    private volatile FlexityVanish flexityVanish;
    private volatile FlexityPlaytime flexityPlaytime;
    private volatile long lastVanishWarning;
    TelegramModeration(JavaPlugin plugin,GuardService guard) {this(plugin,guard,null);}
    TelegramModeration(JavaPlugin plugin,GuardService guard,SearchCompass searchCompass) {this(plugin,guard,searchCompass,null);}
    TelegramModeration(JavaPlugin plugin,GuardService guard,SearchCompass searchCompass,ChildPlayers children) {this.plugin=plugin;this.guard=guard;this.searchCompass=searchCompass;this.children=children;this.premium=new DonationPremium(guard.donations);}
    void reloadPlugin(long user) {
        guard.requireSuper(user);
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,task->{
            try {
                guard.requireSuper(user);
                if(!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"vtrack reload"))
                    plugin.getLogger().warning("Команда vtrack reload не была выполнена");
            } catch(Exception failure) {
                plugin.getLogger().warning("Не удалось перезагрузить VaultTracker из Telegram: "+failure.getMessage());
            }
        },20L);
    }
    long premiumExpiry(UUID owner) throws Exception {return premium.expiry(owner);}
    void syncDonations() throws Exception {premium.sync();}
    com.google.gson.JsonObject donationChange(long actor,UUID owner,String name,String id,String action,int days,long amount) throws Exception {
        if(action.equals("buy")) {var account=guard.account(actor).get();if(account==null||!account.uuid().equals(owner)) throw new SecurityException("Нет доступа");}
        else guard.requireSuper(actor);
        return premium.change(owner,name,actor,id,action,days,amount);
    }
    CompletableFuture<String> changeChild(long user,String nickname,boolean enabled) {
        guard.requireSuper(user);
        if(nickname.matches("[0-9]{1,20}")) {
            long id;try {id=Long.parseLong(nickname);} catch(NumberFormatException invalid) {return CompletableFuture.failedFuture(new IllegalArgumentException("Некорректный Telegram ID"));}
            return guard.account(id).thenCompose(account->{
                if(account==null) return CompletableFuture.failedFuture(new IllegalArgumentException("Этот Telegram ID не привязан. Сначала свяжите аккаунт или используйте игровой ник."));
                return guard.child(user,account.uuid(),account.name(),enabled).thenApply(v->{if(children!=null) children.refresh(account.uuid());return account.name()+(enabled?" добавлен в список детей.":" удалён из списка детей.");});
            });
        }
        if(!enabled) {
            var matches=guard.children(user).stream().filter(p->p.name().equalsIgnoreCase(nickname)).toList();
            if(matches.size()==1) {var child=matches.getFirst();return guard.child(user,child.uuid(),child.name(),false).thenApply(v->{if(children!=null) children.refresh(child.uuid());return child.name()+" удалён из списка детей.";});}
        }
        return players(user,"all").thenCompose(rows->{
            var matches=rows.stream().filter(p->p.name().equalsIgnoreCase(nickname)).toList();
            if(matches.size()!=1) return CompletableFuture.failedFuture(new IllegalArgumentException("Укажите точный ник игрока, который уже заходил на сервер."));
            var person=matches.getFirst();return guard.child(user,person.uuid(),person.name(),enabled).thenApply(v->{if(children!=null) children.refresh(person.uuid());return person.name()+(enabled?" добавлен в список детей.":" удалён из списка детей.");});
        });
    }
    CompletableFuture<String> childSize(long user,double value) {return guard.childSize(user,value).thenApply(uuid->{if(children!=null) children.refresh(uuid);return "Размер сохранён: "+value+". Если персонаж офлайн, применится при входе.";});}
    void pointOwnCompass(long user,UUID uuid,List<OwnResourceSearch.Row> rows) {
        if(searchCompass==null) return;
        guard.account(user).thenCompose(account->{
            if(account==null||!account.uuid().equals(uuid)) return CompletableFuture.completedFuture(null);
            return global(()->plugin.getServer().getPlayer(uuid));
        }).thenAccept(player->{if(player!=null) player.getScheduler().run(plugin,t->searchCompass.point(player,rows),null);})
            .exceptionally(error->null);
    }
    CompletableFuture<BlockKey> ownPosition(long user,UUID uuid) {
        return guard.account(user).thenCompose(account->{
            if(account==null||!account.uuid().equals(uuid)) return CompletableFuture.failedFuture(new SecurityException("Нет доступа"));
            return global(()->plugin.getServer().getPlayer(uuid)).thenCompose(player->{
                if(player==null) return CompletableFuture.completedFuture(null);
                CompletableFuture<BlockKey> result=new CompletableFuture<>();
                player.getScheduler().run(plugin,t->{try {var loc=player.getLocation();result.complete(new BlockKey(loc.getWorld().getUID(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()));}catch(Exception e){result.completeExceptionally(e);}},()->result.complete(null));
                return result.orTimeout(10,TimeUnit.SECONDS);
            });
        });
    }
    CompletableFuture<String> selectOwnCompass(long user,UUID uuid,BlockKey chest) {
        if(searchCompass==null) return CompletableFuture.completedFuture("Навигация недоступна.");
        return guard.account(user).thenCompose(account->{
            if(account==null || !account.uuid().equals(uuid)) throw new SecurityException("Персонаж не привязан к вашему аккаунту.");
            return global(()->plugin.getServer().getPlayer(uuid));
        }).thenCompose(player->{
            if(player==null) return CompletableFuture.completedFuture("Войдите в игру, чтобы выбрать цель компаса.");
            CompletableFuture<String> result=new CompletableFuture<>();
            player.getScheduler().run(plugin,t->{
                if(result.isDone()) return;
                try {result.complete(searchCompass.select(player,chest));} catch(Exception error) {result.completeExceptionally(error);}
            },()->result.complete("Игрок вышел. Повторите после входа."));
            return result.orTimeout(10,TimeUnit.SECONDS);
        });
    }
    CompletableFuture<Map<UUID,String>> worldLabels() {return global(()->{Map<UUID,String> result=new HashMap<>();for(World world:plugin.getServer().getWorlds()) result.put(world.getUID(),world.getName());return Map.copyOf(result);});}
    CompletableFuture<CabinetProfile> cabinetProfile(long user,UUID uuid) {
        return guard.account(user).thenCompose(account->{
            if(account==null||!account.uuid().equals(uuid)) return CompletableFuture.failedFuture(new SecurityException("Нет доступа"));
            return profile(uuid);
        });
    }
    CompletableFuture<CabinetProfile> playerProfile(long user,UUID uuid) {
        guard.requireAdmin(user);return profile(uuid).thenApply(profile->{guard.requireAdmin(user);return profile;});
    }
    private CompletableFuture<CabinetProfile> profile(UUID uuid) {
        return global(()->{
                Player online=plugin.getServer().getPlayer(uuid);OfflinePlayer saved=plugin.getServer().getOfflinePlayer(uuid);
                Location location=online!=null?null:saved.getLocation();
                List<java.nio.file.Path> folders=plugin.getServer().getWorlds().stream().map(world->world.getWorldFolder().toPath()).toList();
                return new CabinetSeed(online,location,folders,guard.child(uuid)==null?20.0:24.0,saved.getFirstPlayed());
            }).thenCompose(seed->{
            if(seed.online()!=null) {
                CompletableFuture<CabinetProfile> result=new CompletableFuture<>();
                seed.online().getScheduler().run(plugin,task->{try {
                    var player=seed.online();var location=player.getLocation();var attribute=player.getAttribute(Attribute.MAX_HEALTH);
                    result.complete(new CabinetProfile(true,player.getHealth(),attribute==null?20.0:attribute.getValue(),player.getFoodLevel(),location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ(),seed.firstPlayed(),null));
                } catch(Exception failure) {result.completeExceptionally(failure);}},()->result.complete(null));
                return result.orTimeout(10,TimeUnit.SECONDS);
            }
            return CompletableFuture.supplyAsync(()->{
                Double health=null;Integer food=null;try {health=OfflineInventory.health(seed.folders(),uuid);food=OfflineInventory.food(seed.folders(),uuid);} catch(Exception ignored) {}
                var location=seed.location();if(location==null||location.getWorld()==null) return new CabinetProfile(false,health,seed.offlineMax(),food,null,0,0,0,seed.firstPlayed(),null);
                return new CabinetProfile(false,health,seed.offlineMax(),food,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ(),seed.firstPlayed(),null);
            });
            }).thenCompose(profile->flexityPlaytime(uuid).handle((seconds,error)->new CabinetProfile(profile.online(),profile.health(),profile.maxHealth(),profile.food(),profile.world(),profile.x(),profile.y(),profile.z(),profile.firstPlayed(),error==null?seconds:null)));
    }
    private CompletableFuture<Long> flexityPlaytime(UUID uuid) {
        try {
            var manager=plugin.getServer().getPluginManager();Plugin current=manager==null?null:manager.getPlugin("flexity");
            if(current==null||!current.isEnabled()) return CompletableFuture.completedFuture(null);
            if(current!=flexityPlugin||flexityPlaytime==null) flexityPlaytime=new FlexityPlaytime(current);
            return flexityPlaytime.seconds(uuid);
        } catch(Exception failure) {return CompletableFuture.completedFuture(null);}
    }
    private <T> CompletableFuture<T> global(Callable<T> job) {
        CompletableFuture<T> result=new CompletableFuture<>();
        try {plugin.getServer().getGlobalRegionScheduler().run(plugin,task-> {if(result.isDone()) return;try {result.complete(job.call());} catch(Exception e) {result.completeExceptionally(e);}});}
        catch(Exception e) {result.completeExceptionally(e);}return result.orTimeout(15,TimeUnit.SECONDS);
    }
    CompletableFuture<List<Person>> players(long user,String mode) {return global(()-> {
        if(mode.equals("banned")) guard.requireCapability(user,"unban");else guard.requireAdmin(user);
        Map<UUID,Person> people=new HashMap<>();
        Set<UUID> vanished=mode.equals("banned")?Set.of():vanishedPlayers();
        if(mode.equals("banned")) {
            ProfileBanList bans=plugin.getServer().getBanList(BanList.Type.PROFILE);
            for(var entry:bans.<org.bukkit.BanEntry<com.destroystokyo.paper.profile.PlayerProfile>>getEntries()) {
                var profile=entry.getBanTarget();if(profile==null || profile.getId()==null) continue;
                if(entry.getExpiration()!=null && entry.getExpiration().before(new Date())) continue;
                people.put(profile.getId(),new Person(profile.getId(),Objects.toString(profile.getName(),profile.getId().toString()),false,
                        "Причина: "+Objects.toString(entry.getReason(),"не указана")+"\nДо: "+Objects.toString(entry.getExpiration(),"бессрочно"),false));
            }
        } else {
            Collection<? extends OfflinePlayer> source=mode.equals("online") ? plugin.getServer().getOnlinePlayers() : Arrays.asList(plugin.getServer().getOfflinePlayers());
            for(OfflinePlayer p:source) people.put(p.getUniqueId(),person(p,vanished.contains(p.getUniqueId())));
            if(!mode.equals("online")) for(Player p:plugin.getServer().getOnlinePlayers()) people.put(p.getUniqueId(),person(p,vanished.contains(p.getUniqueId())));
        }
        return people.values().stream().sorted(Comparator.comparing(Person::name,String.CASE_INSENSITIVE_ORDER)).toList();
    });}
    CompletableFuture<Person> playerByName(String nickname) {return global(()-> {
        String wanted=Objects.requireNonNull(nickname).strip();
        if(wanted.isEmpty()||wanted.length()>64) throw new IllegalArgumentException("Введите игровой ник длиной от 1 до 64 символов");
        Map<UUID,OfflinePlayer> people=new LinkedHashMap<>();
        for(OfflinePlayer player:plugin.getServer().getOfflinePlayers()) people.put(player.getUniqueId(),player);
        for(Player player:plugin.getServer().getOnlinePlayers()) people.put(player.getUniqueId(),player);
        Set<UUID> vanished=vanishedPlayers();
        return people.values().stream().filter(player->player.getName()!=null&&player.getName().equalsIgnoreCase(wanted))
                .findFirst().map(player->person(player,vanished.contains(player.getUniqueId()))).orElseThrow(()->new IllegalArgumentException("Игрок с таким точным ником не найден на сервере"));
    });}
    private Person person(OfflinePlayer p) {
        return person(p,vanishedPlayers().contains(p.getUniqueId()));
    }
    private Person person(OfflinePlayer p,boolean vanished) {
        ProfileBanList bans=plugin.getServer().getBanList(BanList.Type.PROFILE);
        var ban=bans.getBanEntry(p.getPlayerProfile());
        String banInfo=ban==null ? "" : "\nПричина бана: "+Objects.toString(ban.getReason(),"нет")+"\nБан до: "+Objects.toString(ban.getExpiration(),"бессрочно");
        return new Person(p.getUniqueId(),Objects.toString(p.getName(),p.getUniqueId().toString()),p.isOnline(),
                "UUID: "+p.getUniqueId()+"\nОнлайн: "+p.isOnline()+"\nOP: "+p.isOp()+"\nЗабанен: "+p.isBanned()
                +"\nПервый вход: "+date(p.getFirstPlayed())+"\nПоследний вход: "+date(p.getLastLogin())+"\nПоследний выход: "+date(p.getLastSeen())+banInfo,vanished,p.isOp(),p.isBanned(),p.getFirstPlayed(),p.getLastLogin(),p.getLastSeen());
    }
    private Set<UUID> vanishedPlayers() {
        try {
            var manager=plugin.getServer().getPluginManager();if(manager==null) return Set.of();
            Plugin current=manager.getPlugin("flexity");
            if(current==null||!current.isEnabled()) {flexityPlugin=null;flexityVanish=null;return Set.of();}
            if(current!=flexityPlugin||flexityVanish==null) {flexityVanish=new FlexityVanish(current);flexityPlugin=current;}
            return flexityVanish.snapshot();
        } catch(Exception failure) {
            long now=System.currentTimeMillis();
            if(now-lastVanishWarning>60000) {lastVanishWarning=now;plugin.getLogger().warning("Не удалось прочитать /vanish Flexity для списка игроков: "+failure.getClass().getSimpleName());}
            return Set.of();
        }
    }
    private static String date(long ms) {return ms<=0 ? "нет данных" : java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toString();}
    CompletableFuture<Person> info(long user,UUID uuid) {
        return global(()-> {guard.requireAdmin(user);return person(plugin.getServer().getOfflinePlayer(uuid));}).thenCompose(base-> {
            Player online=plugin.getServer().getPlayer(uuid);if(online==null) return CompletableFuture.completedFuture(base);
            CompletableFuture<Person> result=new CompletableFuture<>();
            online.getScheduler().run(plugin,t-> {
                if(result.isDone()) return;
                try {guard.requireAdmin(user);var loc=online.getLocation();result.complete(new Person(base.uuid(),base.name(),true,base.details()+"\nМир: "+loc.getWorld().getName()
                        +"\nКоординаты: "+loc.getBlockX()+" "+loc.getBlockY()+" "+loc.getBlockZ()+"\nРежим: "+online.getGameMode()+"\nЗдоровье: "+online.getHealth(),base.vanished(),base.op(),base.banned(),base.firstPlayed(),base.lastLogin(),base.lastSeen()));}
                catch(Exception e) {result.completeExceptionally(e);}
            },()->result.complete(base));return result.orTimeout(15,TimeUnit.SECONDS);
        });
    }
    CompletableFuture<List<ItemView>> inventory(long user,UUID uuid,boolean ender) {
        return global(()-> { guard.requireCapability(user,ender?"ender":"inventory"); return plugin.getServer().getPlayer(uuid); }).thenCompose(player -> {
            if(player==null) return global(()-> {guard.requireCapability(user,ender?"ender":"inventory");return plugin.getServer().getWorlds().stream().map(w->w.getWorldFolder().toPath()).toList();})
                .thenApplyAsync(folders-> {try {guard.requireCapability(user,ender?"ender":"inventory");return OfflineInventory.read(folders,uuid,ender);} catch(java.io.IOException e) {throw new CompletionException(e);}})
                .thenCompose(saved->global(()-> {guard.requireCapability(user,ender?"ender":"inventory");return plugin.getServer().getPlayer(uuid)!=null;}).thenCompose(joined->joined?inventory(user,uuid,ender):CompletableFuture.completedFuture(saved)));
            CompletableFuture<List<ItemView>> result=new CompletableFuture<>();
            player.getScheduler().run(plugin,task -> { try { guard.requireCapability(user,ender?"ender":"inventory"); result.complete(readItems(ender ? player.getEnderChest() : player.getInventory())); } catch(Exception e) { result.completeExceptionally(e); } },()->result.complete(List.of()));
            return result.orTimeout(15,TimeUnit.SECONDS);
        });
    }
    private static List<ItemView> readItems(org.bukkit.inventory.Inventory inventory) {
        List<ItemView> result=new ArrayList<>();
        Map<String,Integer> regularIndexes=new LinkedHashMap<>();
        for(ItemStack item:inventory.getContents()) if(item!=null && !item.getType().isAir() && item.getAmount()>0) {
            List<ItemView> nested=List.of();
            boolean shulker=item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox;
            if(shulker) {
                BlockStateMeta meta=(BlockStateMeta)item.getItemMeta();
                nested=readItems(((ShulkerBox)meta.getBlockState()).getSnapshotInventory());
            }
            String material=item.getType().name();
            if(shulker) result.add(new ItemView(TelegramItemIcons.label(material),item.getAmount(),nested,true));
            else {
                Integer index=regularIndexes.get(material);
                if(index==null) { regularIndexes.put(material,result.size());result.add(new ItemView(TelegramItemIcons.label(material),item.getAmount(),List.of(),false)); }
                else { ItemView previous=result.get(index);result.set(index,new ItemView(previous.label(),previous.amount()+item.getAmount(),List.of(),false)); }
            }
        }
        return List.copyOf(result);
    }
    CompletableFuture<String> act(long user,String operation,UUID uuid) {
        if(operation.equals("freeze") || operation.equals("unfreeze")) return freeze(user,uuid,operation.equals("freeze"));
        if(operation.equals("kick")) return global(()-> {
            guard.requireCapability(user,"kick");OfflinePlayer offline=plugin.getServer().getOfflinePlayer(uuid);return offline.getPlayer();
        }).thenCompose(player-> {
            if(player==null) return CompletableFuture.completedFuture("Игрок уже не в сети.");
            CompletableFuture<String> result=new CompletableFuture<>();
            player.getScheduler().run(plugin,task-> {
                if(result.isDone()) return;
                try {guard.requireCapability(user,"kick");player.kick(Component.text("Пока идёт расследование"));audit(user,operation,uuid.toString());result.complete("Игрок отключён от сервера.");}
                catch(Exception e) {result.completeExceptionally(e);}
            },()->result.complete("Игрок уже не в сети."));return result.orTimeout(15,TimeUnit.SECONDS);
        });
        if(Set.of("heal","kill","repair").contains(operation)) return onlineAction(user,operation,uuid);
        return global(()-> {
            requireOperation(user,operation);
            OfflinePlayer target=plugin.getServer().getOfflinePlayer(uuid);
            ProfileBanList bans=plugin.getServer().getBanList(BanList.Type.PROFILE);
            if(operation.equals("ban")) {
                if(bans.isBanned(target.getPlayerProfile())) return "Игрок уже забанен. Существующий бан сохранён.";
                bans.addBan(target.getPlayerProfile(),"Пока идёт расследование",java.time.Duration.ofMinutes(5),"Telegram "+user);
                Player online=target.getPlayer();
                if(online!=null) online.getScheduler().run(plugin,t->online.kick(Component.text("Бан на 5 минут: пока идёт расследование")),null);
                audit(user,operation,uuid.toString());return "Бан на 5 минут: пока идёт расследование.";
            }
            if(operation.equals("permanentBan")) {
                if(bans.isBanned(target.getPlayerProfile())) return "Игрок уже забанен. Существующий бан сохранён.";
                bans.addBan(target.getPlayerProfile(),"Бессрочный бан",(java.util.Date)null,"Telegram "+user);
                Player online=target.getPlayer();
                if(online!=null) online.getScheduler().run(plugin,t->online.kick(Component.text("Вы забанены на сервере")),null);
                audit(user,operation,uuid.toString());return "Игрок забанен бессрочно.";
            }
            if(operation.equals("unban")) {bans.pardon(target.getPlayerProfile());audit(user,operation,uuid.toString());return "Бан снят.";}
            if(operation.equals("op") || operation.equals("deop")) {boolean enabled=operation.equals("op");target.setOp(enabled);audit(user,operation,uuid.toString());return enabled?"Игроку выдан OP.":"OP у игрока снят.";}
            if(operation.equals("toggleLp") || operation.equals("addLp") || operation.equals("removeLp")) {
                if(plugin.getServer().getPluginManager().getPlugin("LuckPerms")==null) return "LuckPerms не установлен.";
                boolean current=lpAdminState.containsKey(uuid) ? lpAdminState.get(uuid) : luckPermsAdmin(uuid);
                boolean add=operation.equals("toggleLp") ? !current : operation.equals("addLp");
                String verb=add ? "add" : "remove";
                boolean sent=plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"lp user "+uuid+" parent "+verb+" admin");
                audit(user,operation,uuid.toString());
                if(sent) lpAdminState.put(uuid,add);
                String action=add ? "выдачи" : "удаления";
                return sent ? "Команда "+action+" группы admin отправлена LuckPerms. Результат — в консоли сервера. OP и другие группы не изменяются." : "LuckPerms не принял команду; проверьте консоль.";
            }
            throw new IllegalArgumentException("Неизвестное действие");
        });
    }
    CompletableFuture<Boolean> adminGroup(long user,UUID uuid) {
        return global(()-> { guard.requireCapability(user,"luckPerms"); return lpAdminState.containsKey(uuid) ? lpAdminState.get(uuid) : luckPermsAdmin(uuid); });
    }
    CompletableFuture<String> scale(long user,UUID uuid,double value) {
        if(!Double.isFinite(value) || value<0.1 || value>20) return CompletableFuture.failedFuture(new IllegalArgumentException("Размер должен быть от 0.1 до 20."));
        return onlineAction(user,"scale",uuid, value);
    }
    CompletableFuture<String> speed(long user,UUID uuid,String kind,double multiplier) {
        if(!Double.isFinite(multiplier) || multiplier<0.1 || multiplier>20) return CompletableFuture.failedFuture(new IllegalArgumentException("Скорость должна быть от 0.1 до 20."));
        if(!kind.equals("fly") && !kind.equals("walk")) return CompletableFuture.failedFuture(new IllegalArgumentException("Неизвестный вид скорости."));
        return onlineAction(user,kind.equals("fly") ? "flySpeed" : "walkSpeed",uuid,multiplier);
    }
    private CompletableFuture<String> onlineAction(long user,String operation,UUID uuid) { return onlineAction(user,operation,uuid,0); }
    private CompletableFuture<String> onlineAction(long user,String operation,UUID uuid,double scale) {
        return global(()-> { requireOperation(user,operation); return plugin.getServer().getPlayer(uuid); }).thenCompose(player -> {
            if(player==null) return CompletableFuture.completedFuture("Игрок должен быть онлайн для этого действия.");
            CompletableFuture<String> result=new CompletableFuture<>();
            player.getScheduler().run(plugin,task -> {
                if(result.isDone()) return;
                try {
                    requireOperation(user,operation);
                    switch(operation) {
                        case "heal" -> { player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue()); player.setFireTicks(0); result.complete("Игрок полностью вылечен."); }
                        case "kill" -> { player.setHealth(0); result.complete("Игрок убит."); }
                        case "repair" -> { repair(player); result.complete("Предметы в инвентаре отремонтированы."); }
                        case "scale" -> { var attribute=player.getAttribute(Attribute.SCALE); if(attribute==null) throw new IllegalStateException("Атрибут размера недоступен на этой версии сервера."); attribute.setBaseValue(scale); result.complete("Размер игрока установлен: "+scale+"."); }
                        case "flySpeed" -> {
                            float apiSpeed=(float)Math.min(1.0,scale/10.0);
                            player.setFlySpeed(apiSpeed);
                            var attribute=player.getAttribute(Attribute.FLYING_SPEED);
                            if(attribute!=null) attribute.setBaseValue(attribute.getDefaultValue()*scale);
                            result.complete(attribute==null && apiSpeed<scale/10.0 ? "Скорость полёта установлена на пределе Paper (10x); запрошено: "+scale+"x." : "Скорость полёта установлена: "+scale+"x.");
                        }
                        case "walkSpeed" -> {
                            var attribute=player.getAttribute(Attribute.MOVEMENT_SPEED); if(attribute==null) throw new IllegalStateException("Атрибут скорости передвижения недоступен на этой версии сервера.");
                            attribute.setBaseValue(attribute.getDefaultValue()*scale); result.complete("Скорость передвижения установлена: "+scale+"x.");
                        }
                        default -> throw new IllegalArgumentException("Неизвестное действие");
                    }
                    audit(user,operation,uuid.toString());
                } catch(Exception e) { result.completeExceptionally(e); }
            },()->result.complete("Игрок уже не в сети."));
            return result.orTimeout(15,TimeUnit.SECONDS);
        });
    }
    static double[] coordinates(String text) {
        String[] parts=text.trim().split("\\s+");
        if(parts.length!=3) throw new IllegalArgumentException("Введите координаты в формате x y z, например: 223 200 1004.");
        double[] values=new double[3];
        try {for(int i=0;i<3;i++) {values[i]=Double.parseDouble(parts[i]);if(!Double.isFinite(values[i]) || Math.abs(values[i])>30_000_000) throw new NumberFormatException();}}
        catch(NumberFormatException e) {throw new IllegalArgumentException("Нужны три конечных числа в пределах координат мира.");}
        return values;
    }
    CompletableFuture<String> teleport(long user,UUID source,UUID destination,String coordinates) {
        return global(()-> {guard.requireCapability(user,destination==null?"teleportCoords":"teleport");return plugin.getServer().getPlayer(destination==null?source:destination);}).thenCompose(anchor-> {
            if(anchor==null) return CompletableFuture.completedFuture("Игрок должен быть онлайн для телепортации.");
            CompletableFuture<Location> location=new CompletableFuture<>();
            anchor.getScheduler().run(plugin,t-> {try {
                guard.requireCapability(user,destination==null?"teleportCoords":"teleport");Location loc=anchor.getLocation().clone();
                if(destination==null) {double[] xyz=coordinates(coordinates);loc.setX(xyz[0]);loc.setY(xyz[1]);loc.setZ(xyz[2]);}
                if(loc.getY()<loc.getWorld().getMinHeight() || loc.getY()>=loc.getWorld().getMaxHeight() || !loc.getWorld().getWorldBorder().isInside(loc)) throw new IllegalArgumentException("Координаты за пределами высоты или границы мира.");
                location.complete(loc);
            } catch(Exception e) {location.completeExceptionally(e);}},()->location.completeExceptionally(new IllegalStateException("Игрок вышел с сервера.")));
            return location.orTimeout(15,TimeUnit.SECONDS).thenCompose(loc->global(()-> {guard.requireCapability(user,destination==null?"teleportCoords":"teleport");return plugin.getServer().getPlayer(source);}).thenCompose(player-> {
                if(player==null) return CompletableFuture.completedFuture("Перемещаемый игрок уже не в сети.");
                CompletableFuture<String> result=new CompletableFuture<>();
                player.getScheduler().run(plugin,t-> {try {
                    if(result.isDone()) return;guard.requireCapability(user,destination==null?"teleportCoords":"teleport");
                    player.teleportAsync(loc).whenComplete((ok,error)-> {if(error!=null) result.completeExceptionally(error);else result.complete(Boolean.TRUE.equals(ok)?"Игрок телепортирован: "+loc.getWorld().getName()+" · "+loc.getX()+" "+loc.getY()+" "+loc.getZ():"Телепортация отменена сервером.");});
                } catch(Exception e) {result.completeExceptionally(e);}},()->result.complete("Игрок вышел с сервера."));
                return result.orTimeout(30,TimeUnit.SECONDS);
            }));
        });
    }
    private static void repair(Player player) {
        for(ItemStack item:player.getInventory().getContents()) if(item!=null && item.hasItemMeta() && item.getItemMeta() instanceof Damageable damageable) { damageable.setDamage(0); item.setItemMeta(damageable); }
    }
    private boolean luckPermsAdmin(UUID uuid) throws Exception {
        try {
            Object lp=plugin.getServer().getPluginManager().getPlugin("LuckPerms");
            if(lp==null) return false;
            Object manager=lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user=manager.getClass().getMethod("getUser",UUID.class).invoke(manager,uuid);
            if(user==null) {
                Object future=manager.getClass().getMethod("loadUser",UUID.class).invoke(manager,uuid);
                if(future instanceof Future<?> f) user=f.get(10,TimeUnit.SECONDS);
            }
            if(user==null) return false;
            Object nodes=user.getClass().getMethod("getNodes").invoke(user);
            if(!(nodes instanceof Iterable<?> iterable)) return false;
            for(Object node:iterable) {
                String key=Objects.toString(node.getClass().getMethod("getKey").invoke(node),"");
                Object value=node.getClass().getMethod("getValue").invoke(node);
                if("group.admin".equalsIgnoreCase(key) && Boolean.TRUE.equals(value)) return true;
            }
            return false;
        } catch(Exception e) {
            plugin.getLogger().fine("Не удалось определить группу LuckPerms admin: "+e.getClass().getSimpleName());
            return false;
        }
    }
    CompletableFuture<String> broadcast(long user,String text) {return global(()-> {
        guard.requireCapability(user,"chat");if(text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r")) throw new IllegalArgumentException("Сообщение: одна строка, до 500 символов");
        Component message=Component.text("[Консоль] "+text);
        for(Player p:plugin.getServer().getOnlinePlayers()) p.getScheduler().run(plugin,t->p.sendMessage(message),null);
        plugin.getServer().getConsoleSender().sendMessage(message);audit(user,"chat",text);return "Сообщение отправлено в игровой чат.";
    });}
    boolean freezeAvailable() {
        try {freezeBridge();return true;} catch(Exception unavailable) {return false;}
    }
    private FlexityFreeze freezeBridge() throws Exception {
        Plugin flexity=plugin.getServer().getPluginManager().getPlugin("flexity");
        if(flexity==null || !flexity.isEnabled()) throw new IllegalStateException("Заморозка Flexity недоступна");
        return new FlexityFreeze(flexity);
    }
    private CompletableFuture<String> freeze(long user,UUID target,boolean enabled) {
        guard.requireCapability(user,"freeze");
        return guard.account(user).thenCompose(account->global(()->{
            guard.requireCapability(user,"freeze");
            if(enabled && plugin.getServer().getPlayer(target)==null) throw new IllegalArgumentException("Для заморозки игрок должен быть онлайн.");
            UUID actor=account!=null?account.uuid():UUID.nameUUIDFromBytes(("VaultTracker:Telegram:"+user).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var future=freezeBridge().change(target,actor,enabled);
            plugin.getLogger().info("Telegram "+user+" ("+actor+"): "+(enabled?"freeze":"unfreeze")+" → "+target);
            return future;
        }).thenCompose(f->f)).thenCompose(changed->global(()->{
            Player player=plugin.getServer().getPlayer(target);
            if(changed && player!=null) player.getScheduler().run(plugin,t->player.sendMessage(Component.text(enabled?"[FLEXITY] Вы заморожены на время проверки.":"[FLEXITY] Заморозка снята.")),null);
            return changed?(enabled?"Игрок заморожен через Flexity.":"Игрок разморожен через Flexity."):"Игрок уже не заморожен.";
        }));
    }
    CompletableFuture<String> privateMessage(long user,UUID target,String text) {
        guard.requireAdmin(user);
        if(text==null || text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Сообщение: одна строка, до 500 символов."));
        return global(()->{guard.requireAdmin(user);return plugin.getServer().getPlayer(target);}).thenCompose(player->{
            if(player==null) return CompletableFuture.completedFuture("Игрок не в сети. Сообщение не отправлено.");
            CompletableFuture<String> result=new CompletableFuture<>();
            player.getScheduler().run(plugin,t->{
                if(result.isDone()) return;
                try {guard.requireAdmin(user);player.sendMessage(Component.text("[FLEXITY] "+text));audit(user,"privateMessage",target+": "+text);result.complete("Личное сообщение отправлено игроку от имени FLEXITY.");}
                catch(Exception error) {result.completeExceptionally(error);}
            },()->result.complete("Игрок вышел. Сообщение не отправлено."));
            return result.orTimeout(15,TimeUnit.SECONDS);
        });
    }
    private void audit(long user,String action,String target) {if(guard.superAdmin(user)) return;plugin.getLogger().info("Telegram "+user+": "+action+" → "+target);}
    private void requireOperation(long user,String operation) {
        String capability=switch(operation) {
            case "freeze","unfreeze" -> "freeze";
            case "ban" -> "ban";case "permanentBan" -> "permanentBan";case "kick" -> "kick";case "unban" -> "unban";
            case "toggleLp","addLp","removeLp" -> "luckPerms";case "op" -> "op";case "deop" -> "deop";
            case "heal" -> "heal";case "kill" -> "kill";case "repair" -> "repair";
            case "scale" -> "scale";case "flySpeed" -> "flySpeed";case "walkSpeed" -> "walkSpeed";
            default -> throw new IllegalArgumentException("Неизвестное действие");
        };
        guard.requireCapability(user,capability);
    }
}
