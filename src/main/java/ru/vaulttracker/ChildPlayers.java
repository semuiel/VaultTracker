package ru.vaulttracker;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Modifiers belong only to VaultTracker; base attributes and other plugins are preserved. */
final class ChildPlayers implements Listener {
    static final NamespacedKey HEALTH=new NamespacedKey("vaulttracker","child_health"),SIZE=new NamespacedKey("vaulttracker","child_size");
    private final JavaPlugin plugin;
    private final GuardService guard;
    ChildPlayers(JavaPlugin plugin,GuardService guard) {this.plugin=plugin;this.guard=guard;}
    void refresh(UUID uuid) {plugin.getServer().getGlobalRegionScheduler().run(plugin,t->{var player=plugin.getServer().getPlayer(uuid);if(player!=null) schedule(player);});}
    void start() {plugin.getServer().getGlobalRegionScheduler().run(plugin,t->{for(var p:plugin.getServer().getOnlinePlayers()) schedule(p);});}
    private void schedule(Player player) {player.getScheduler().run(plugin,t->apply(player),null);}
    @EventHandler public void joined(PlayerJoinEvent event) {schedule(event.getPlayer());}
    @EventHandler public void respawn(PlayerRespawnEvent event) {schedule(event.getPlayer());}
    void apply(Player player) {
        apply(player,player.getAttribute(Attribute.MAX_HEALTH),player.getAttribute(Attribute.SCALE));
    }
    void apply(Player player,AttributeInstance health,AttributeInstance scale) {
        var child=guard.child(player.getUniqueId());
        modifier(health,HEALTH,child==null?null:4.0);
        modifier(scale,SIZE,child==null?null:child.size()-(scale==null?1:scale.getBaseValue()));
        if(health!=null&&player.getHealth()>health.getValue()) player.setHealth(health.getValue());
    }
    private static void modifier(AttributeInstance attribute,NamespacedKey key,Double amount) {
        if(attribute==null) return;
        var existing=attribute.getModifier(key);
        if(existing!=null) attribute.removeModifier(existing);
        if(amount!=null&&amount!=0) attribute.addTransientModifier(new AttributeModifier(key,amount,AttributeModifier.Operation.ADD_NUMBER));
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player p)||guard.child(p.getUniqueId())==null||Set.of("VOID","KILL","SUICIDE").contains(event.getCause().name())) return;
        // Preserve existing armour/resistance modifiers, reduce the resulting damage by exactly 20%.
        double reduction=Math.max(0,event.getFinalDamage())*0.2;
        event.setDamage(EntityDamageEvent.DamageModifier.BASE,event.getDamage(EntityDamageEvent.DamageModifier.BASE)-reduction);
    }
}
