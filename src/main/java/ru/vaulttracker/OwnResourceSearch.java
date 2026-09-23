package ru.vaulttracker;

import java.util.*;

/** Searches only the caller's registered inventories; no chunk loads or Bukkit calls. */
final class OwnResourceSearch {
    record Row(BlockKey chest,String material,long amount,double distance) {}
    static List<Row> find(Catalogue catalogue,UUID owner,String query,BlockKey origin) {
        String normalized=RussianItems.normalize(query.strip().replace(' ','_').replace('-','_')).replace("DIAMND","DIAMOND");
        String russian=query.strip().toLowerCase(Locale.ROOT).replace('ё','е');
        if(russian.isEmpty()) return List.of();
        List<Row> rows=new ArrayList<>();
        for(Snapshot vault:catalogue.all()) if(vault.owner().equals(owner)&&!vault.chests().isEmpty()) {
            BlockKey chest=vault.chests().stream().min(Comparator.comparingDouble(c->distance(c,origin))).orElseThrow();
            for(var item:vault.items().entrySet()) if(item.getValue()>0 && (item.getKey().contains(normalized)||RussianItems.name(item.getKey()).toLowerCase(Locale.ROOT).replace('ё','е').contains(russian)))
                rows.add(new Row(chest,item.getKey(),item.getValue(),distance(chest,origin)));
        }
        rows.sort(Comparator.comparingDouble(Row::distance).thenComparing(r->r.chest().world().toString()).thenComparingInt(r->r.chest().x()).thenComparingInt(r->r.chest().y()).thenComparingInt(r->r.chest().z()).thenComparing(Row::material));
        return List.copyOf(rows);
    }
    private static double distance(BlockKey chest,BlockKey origin) {
        if(origin==null||!chest.world().equals(origin.world())) return Double.POSITIVE_INFINITY;
        return Math.sqrt(Math.pow((double)chest.x()-origin.x(),2)+Math.pow((double)chest.y()-origin.y(),2)+Math.pow((double)chest.z()-origin.z(),2));
    }
    static String line(Row row,String world) {
        BlockKey c=row.chest();return RussianItems.name(row.material())+" ×"+row.amount()+" — "+world+" · "+TelegramEmojiMarkup.plainCode(c.x()+" "+c.y()+" "+c.z())+(Double.isFinite(row.distance())?" · "+Math.round(row.distance())+" м":"");
    }
}
