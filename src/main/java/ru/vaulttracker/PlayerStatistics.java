package ru.vaulttracker;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import java.util.*;

/** Sparse snapshots from Bukkit's vanilla counters, keyed only by player UUID in GuardService. */
final class PlayerStatistics {
    record Counter(long total,int lastVanilla) {}

    static Counter merge(Counter previous,int raw) {
        if(raw<=0) return previous;
        if(previous==null) return new Counter(raw,raw);
        // A lower positive value means the world's vanilla counter restarted. Keep the
        // historical total and start a new epoch; an absent/zero value is ignored.
        long delta=raw>=previous.lastVanilla()?raw-previous.lastVanilla():raw;
        return new Counter(Math.addExact(previous.total(),delta),raw);
    }

    static Map<String,Integer> capture(OfflinePlayer player) {return capture(player,Arrays.asList(Material.values()));}
    static Map<String,Integer> capture(OfflinePlayer player,Collection<Material> materials) {
        Map<String,Integer> result=new HashMap<>();
        for(Statistic statistic:Statistic.values()) if(statistic.getType()==Statistic.Type.UNTYPED)
            read(result,"custom:"+statistic.name(),()->player.getStatistic(statistic));
        for(Material material:materials) {
            if(material.name().startsWith("LEGACY_")) continue;
            if(material.isBlock()) read(result,"mine:"+material.name(),()->player.getStatistic(Statistic.MINE_BLOCK,material));
            if(!material.isItem()) continue;
            read(result,"craft:"+material.name(),()->player.getStatistic(Statistic.CRAFT_ITEM,material));
            read(result,"use:"+material.name(),()->player.getStatistic(Statistic.USE_ITEM,material));
            read(result,"break:"+material.name(),()->player.getStatistic(Statistic.BREAK_ITEM,material));
            read(result,"pickup:"+material.name(),()->player.getStatistic(Statistic.PICKUP,material));
            read(result,"drop:"+material.name(),()->player.getStatistic(Statistic.DROP,material));
        }
        return Map.copyOf(result);
    }

    private static void read(Map<String,Integer> result,String key,java.util.function.IntSupplier source) {
        try {int value=source.getAsInt();if(value>0) result.put(key,value);}
        catch(IllegalArgumentException | UnsupportedOperationException unavailable) { /* Not a statistic for this material/server. */ }
    }

    static long value(Map<String,Long> totals,String statistic) {return totals.getOrDefault("custom:"+statistic,0L);}
    static long sum(Map<String,Long> totals,String prefix) {
        return totals.entrySet().stream().filter(e->e.getKey().startsWith(prefix+":")).mapToLong(Map.Entry::getValue).sum();
    }
    static List<Map.Entry<String,Long>> top(Map<String,Long> totals,String prefix) {
        return totals.entrySet().stream().filter(e->e.getKey().startsWith(prefix+":") && e.getValue()>0)
                .sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
    }
    private PlayerStatistics() {}
}
