package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Temporary player compass target. Never rewrites compass items or lodestone bindings. */
final class SearchCompass implements Listener {
    private static final class Session {
        final Location original,target;
        volatile ScheduledTask timer;
        Session(Location original,Location target) {this.original=original;this.target=target;}
    }
    private final JavaPlugin plugin;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    SearchCompass(JavaPlugin plugin) {this.plugin=plugin;}

    /** Must run on the player's entity thread. Recompute distance here after any Telegram delay. */
    void point(Player player,List<OwnResourceSearch.Row> rows) {
        if(!player.isOnline()) return;
        Location origin=player.getLocation();
        BlockKey nearest=nearest(rows,origin);
        if(nearest==null) {clear(player);return;}
        UUID uuid=player.getUniqueId();Session previous=sessions.remove(uuid);
        if(previous!=null&&previous.timer!=null) previous.timer.cancel();
        Location current=player.getCompassTarget();
        Location original=previous!=null&&sameBlock(current,previous.target)?previous.original:current.clone();
        Location target=new Location(origin.getWorld(),nearest.x()+0.5,nearest.y()+0.5,nearest.z()+0.5);
        player.setCompassTarget(target);
        // Compare the exact session: an old timeout must not reset a newer search.
        Session session=new Session(original,target);sessions.put(uuid,session);
        session.timer=plugin.getServer().getAsyncScheduler().runDelayed(plugin,task->
            player.getScheduler().run(plugin,t->{
                if(sessions.remove(uuid,session)) restore(player,session);
            },()->sessions.remove(uuid,session)),120,TimeUnit.SECONDS);
    }
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
        if(session.timer!=null) session.timer.cancel();restore(player,session);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {clear(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void world(PlayerChangedWorldEvent event) {clear(event.getPlayer());}
}
