package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Temporary player compass target. Never rewrites compass items or lodestone bindings. */
final class SearchCompass implements Listener {
    private static final class Session {
        final Location original,target;
        volatile ScheduledTask timer,marker;
        Session(Location original,Location target) {this.original=original;this.target=target;}
    }
    private final JavaPlugin plugin;
    private final Catalogue catalogue;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    SearchCompass(JavaPlugin plugin) {this(plugin,null);}
    SearchCompass(JavaPlugin plugin,Catalogue catalogue) {this.plugin=plugin;this.catalogue=catalogue;}

    /** Recheck ownership against the current catalogue, never trust old search results. */
    String select(Player player,BlockKey chest) {
        if(!player.isOnline()) return "Игрок не в сети.";
        if(!player.hasPermission("vaulttracker.search")) return "Нет права vaulttracker.search.";
        if(catalogue==null || !catalogue.all().stream().anyMatch(v->v.active() && v.owner().equals(player.getUniqueId()) && v.chests().contains(chest)))
            return "Сундук больше не зарегистрирован за вами. Повторите поиск.";
        if(!player.getWorld().getUID().equals(chest.world())) return "Сундук в другом мире. Сначала перейдите в его мир.";
        point(player,chest);
        return "Компас направлен на сундук: "+chest.x()+" "+chest.y()+" "+chest.z()+" на 2 минуты.";
    }

    /** Must run on the player's entity thread. Recompute distance here after any Telegram delay. */
    void point(Player player,List<OwnResourceSearch.Row> rows) {
        if(!player.isOnline()) return;
        Location origin=player.getLocation();
        BlockKey nearest=nearest(rows,origin);
        if(nearest==null) {clear(player);return;}
        point(player,nearest);
    }
    private void point(Player player,BlockKey nearest) {
        Location origin=player.getLocation();
        UUID uuid=player.getUniqueId();Session previous=sessions.remove(uuid);
        if(previous!=null) cancel(previous);
        Location current=player.getCompassTarget();
        Location original=previous!=null&&sameBlock(current,previous.target)?previous.original:current.clone();
        Location target=new Location(origin.getWorld(),nearest.x()+0.5,nearest.y()+0.5,nearest.z()+0.5);
        player.setCompassTarget(target);
        // Compare the exact session: an old timeout must not reset a newer search.
        Session session=new Session(original,target);sessions.put(uuid,session);
        session.timer=plugin.getServer().getAsyncScheduler().runDelayed(plugin,task->
            player.getScheduler().run(plugin,t->{
                if(sessions.remove(uuid,session)) {cancel(session);restore(player,session);}
            },()->{if(sessions.remove(uuid,session)) cancel(session);}),120,TimeUnit.SECONDS);
        session.marker=player.getScheduler().runAtFixedRate(plugin,t->{
            if(sessions.get(uuid)!=session) {t.cancel();return;}
            Location here=player.getLocation();
            if(!here.getWorld().equals(target.getWorld()) || !sameBlock(player.getCompassTarget(),target)) {clear(player);return;}
            double distance=here.distance(target);
            if(distance<=24) {
                // Per-player particles outline the actual block, without reading/loading its chunk.
                for(double x:new double[]{-0.48,0.48}) for(double y:new double[]{-0.48,0.48}) for(double z:new double[]{-0.48,0.48})
                    player.spawnParticle(Particle.END_ROD,target.clone().add(x,y,z),1,0,0,0,0);
                player.sendActionBar(Component.text("Сундук: "+nearest.x()+" "+nearest.y()+" "+nearest.z()+" · "+Math.round(distance)+" м"));
            }
        },()->{if(sessions.remove(uuid,session)) cancel(session);},1,20);
    }
    private static void cancel(Session session) {if(session.timer!=null) session.timer.cancel();if(session.marker!=null) session.marker.cancel();}
    static BlockKey nearest(List<OwnResourceSearch.Row> rows,Location origin) {
        UUID world=origin.getWorld().getUID();
        return rows.stream().map(OwnResourceSearch.Row::chest).filter(c->c.world().equals(world))
            .min(Comparator.comparingDouble(c->Math.pow(c.x()+0.5-origin.getX(),2)+Math.pow(c.y()+0.5-origin.getY(),2)+Math.pow(c.z()+0.5-origin.getZ(),2))).orElse(null);
    }
    private static boolean sameBlock(Location a,Location b) {
        return a.getWorld().equals(b.getWorld())&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();
    }
    private void restore(Player player,Session session) {
        // Do not overwrite a compass target installed by another plugin in the meantime.
        if(sameBlock(player.getCompassTarget(),session.target)) player.setCompassTarget(session.original);
    }
    void clear(Player player) {
        Session session=sessions.remove(player.getUniqueId());if(session==null) return;
        cancel(session);restore(player,session);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {clear(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void world(PlayerChangedWorldEvent event) {clear(event.getPlayer());}
}
