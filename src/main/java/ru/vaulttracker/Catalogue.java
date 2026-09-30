package ru.vaulttracker;

import java.util.*;
import java.util.function.Consumer;

/** Synchronizes metadata only. Never calls Bukkit, SQL, or other blocking I/O. */
public final class Catalogue {
    private final Map<BlockKey, Snapshot> vaults = new HashMap<>();
    private final Map<BlockKey, BlockKey> claims = new HashMap<>();
    private final Map<BlockKey.ChunkKey, Set<BlockKey>> chunks = new HashMap<>();
    private final Map<String,Map<UUID,Long>> totalsByMaterial = new HashMap<>();
    private final Map<UUID,String> indexedNames = new HashMap<>();
    private final Consumer<Snapshot> output;
    private long revision;

    public Catalogue(Consumer<Snapshot> output) { this.output = output; }

    public synchronized void restore(Collection<Snapshot> stored) {
        revision = stored.stream().mapToLong(Snapshot::revision).max().orElse(0);
        for (Snapshot v : stored) {
            if (!v.active()) continue;
            if (v.chests().stream().anyMatch(claims::containsKey))
                throw new IllegalStateException("Duplicate chest ownership in local catalogue");
            index(v);
        }
    }
    public synchronized List<Snapshot> restoreMissing(Collection<Snapshot> stored) {
        List<Snapshot> added=new ArrayList<>();
        for(Snapshot snapshot:stored) {
            if(!snapshot.active() || vaults.containsKey(snapshot.sign())) continue;
            if(snapshot.chests().stream().anyMatch(claims::containsKey)) continue;
            revision=Math.max(revision,snapshot.revision());index(snapshot);added.add(snapshot);
        }
        return List.copyOf(added);
    }

    private void index(Snapshot v) {
        vaults.put(v.sign(), v);
        indexedNames.put(v.owner(),v.playerName());
        v.items().forEach((material,amount)->{
            if(amount>0) totalsByMaterial.computeIfAbsent(material,ignored->new HashMap<>()).merge(v.owner(),amount,Long::sum);
        });
        for (BlockKey p : v.chests()) claims.put(p, v.sign());
        for (var chunk : chunkKeys(v)) chunks.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(v.sign());
    }
    private Set<BlockKey.ChunkKey> chunkKeys(Snapshot v) {
        Set<BlockKey.ChunkKey> result = new HashSet<>();
        result.add(v.sign().chunk());
        for (BlockKey p : v.chests()) result.add(p.chunk());
        return result;
    }
    private void unindex(Snapshot v) {
        vaults.remove(v.sign());
        v.items().forEach((material,amount)->{
            if(amount<=0) return;
            Map<UUID,Long> owners=totalsByMaterial.get(material);
            if(owners==null) return;
            owners.computeIfPresent(v.owner(),(ignored,total)->total<=amount?null:total-amount);
            if(owners.isEmpty()) totalsByMaterial.remove(material);
        });
        for (BlockKey p : v.chests()) claims.remove(p, v.sign());
        for (var chunk : chunkKeys(v)) {
            Set<BlockKey> keys = chunks.get(chunk);
            if (keys != null) { keys.remove(v.sign()); if (keys.isEmpty()) chunks.remove(chunk); }
        }
    }
    private void publish(Snapshot v) { index(v); output.accept(v); }

    public synchronized Snapshot register(BlockKey sign, UUID owner, String name,
                                          List<BlockKey> chests, Map<String, Long> items, long now, int limit) {
        Snapshot existing = vaults.get(sign);
        if (existing != null) throw new IllegalArgumentException("Это хранилище уже зарегистрировано.");
        if (limit>0 && vaults.values().stream().filter(v -> v.owner().equals(owner)).count() >= limit)
            throw new IllegalArgumentException("Достигнут лимит зарегистрированных сундуков.");
        for (BlockKey chest : chests) if (claims.containsKey(chest))
            throw new IllegalArgumentException("Этот сундук уже учтён другим маркером.");
        Snapshot v = new Snapshot(sign, UUID.randomUUID(), owner, name, chests, items, true, now, ++revision);
        publish(v);
        return v;
    }

    public synchronized boolean observe(BlockKey sign, UUID generation, List<BlockKey> chests,
                                        Map<String, Long> items, long now) {
        Snapshot old = vaults.get(sign);
        if (old == null || !old.generation().equals(generation)) return false;
        Set<BlockKey> conflicts = new HashSet<>();
        for (BlockKey chest : chests) {
            BlockKey other = claims.get(chest);
            if (other != null && !other.equals(sign)) conflicts.add(other);
        }
        if (!conflicts.isEmpty()) {
            // A plugin/world edit merged two separately registered inventories.
            // Remove both claims; do not silently transfer another player's resources.
            conflicts.add(sign);
            for (BlockKey key : conflicts) { Snapshot v = vaults.get(key); if (v != null) remove(key, v.generation(), now); }
            return false;
        }
        // Event bursts from automated blocks (including crafters) often observe
        // the same inventory several times. Avoid publishing redundant snapshots.
        if (old.chests().equals(chests) && old.items().equals(items)) return true;
        unindex(old);
        publish(new Snapshot(sign, generation, old.owner(), old.playerName(), chests, items, true, now, ++revision));
        return true;
    }

    public synchronized void remove(BlockKey sign, UUID generation, long now) {
        Snapshot old = vaults.get(sign);
        if (old == null || !old.generation().equals(generation)) return;
        unindex(old);
        output.accept(new Snapshot(sign, generation, old.owner(), old.playerName(), old.chests(), Map.of(), false, now, ++revision));
    }
    public synchronized void rename(UUID owner, String name) {
        for (Snapshot old : List.copyOf(vaults.values())) {
            if (!old.owner().equals(owner) || old.playerName().equals(name)) continue;
            unindex(old);
            publish(new Snapshot(old.sign(), old.generation(), owner, name, old.chests(), old.items(), true, old.checkedAt(), ++revision));
        }
    }
    public synchronized Snapshot get(BlockKey sign) { return vaults.get(sign); }
    public synchronized BlockKey claimedBy(BlockKey chest) { return claims.get(chest); }
    public synchronized List<Snapshot> all() { return List.copyOf(vaults.values()); }
    public synchronized List<Snapshot> inChunk(BlockKey.ChunkKey key) {
        return chunks.getOrDefault(key, Set.of()).stream().map(vaults::get).filter(Objects::nonNull).toList();
    }
    public synchronized int size() { return vaults.size(); }
    public record OwnerItems(UUID owner, String name, Map<String, Long> items) {}
    public synchronized OwnerItems ownerItems(UUID owner) {
        Map<String,Long> items=new HashMap<>();String name=owner.toString();
        for(Snapshot v:vaults.values()) if(v.owner().equals(owner)) {
            name=v.playerName();v.items().forEach((item,amount)->items.merge(item,amount,Long::sum));
        }
        return new OwnerItems(owner,name,Map.copyOf(items));
    }
    public synchronized List<OwnerItems> ownerItems(String nickname) {
        Map<UUID, String> matches = new HashMap<>();
        for (Snapshot v : vaults.values()) if (v.playerName().equalsIgnoreCase(nickname)) matches.put(v.owner(),v.playerName());
        List<OwnerItems> result = new ArrayList<>();
        for (var match : matches.entrySet()) {
            Map<String,Long> items = new HashMap<>();
            for (Snapshot v : vaults.values()) if (v.owner().equals(match.getKey()))
                v.items().forEach((material, amount) -> { if (amount > 0) items.merge(material,amount,Long::sum); });
            result.add(new OwnerItems(match.getKey(),match.getValue(),Map.copyOf(items)));
        }
        return List.copyOf(result);
    }
    public synchronized List<String> ownerNames() {
        return vaults.values().stream().map(Snapshot::playerName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    /** Registered players ordered by the summed amount of every item in all their vaults. */
    public synchronized List<String> ownerNamesByTotalItems() {
        Map<UUID,Long> amounts=new HashMap<>();Map<UUID,String> names=new HashMap<>();
        for(Snapshot vault:vaults.values()) {
            names.put(vault.owner(),vault.playerName());amounts.putIfAbsent(vault.owner(),0L);
            for(long amount:vault.items().values()) if(amount>0) amounts.merge(vault.owner(),amount,Math::addExact);
        }
        return amounts.entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed()
                        .thenComparing(entry->names.get(entry.getKey()),String.CASE_INSENSITIVE_ORDER).thenComparing(Map.Entry::getKey))
                .map(entry->names.get(entry.getKey())).distinct().toList();
    }
    public synchronized List<String> find(String material, int limit) {
        return findTotals(material,limit).stream().map(row -> row.name()+" — "+row.amount()).toList();
    }
    public record OwnerAmount(UUID owner,String name,long amount) {}
    public synchronized List<OwnerAmount> allItemTotals() {
        Map<UUID,Long> amounts=new HashMap<>();Map<UUID,String> names=new HashMap<>();
        for(Snapshot vault:vaults.values()) {
            names.put(vault.owner(),vault.playerName());
            for(long amount:vault.items().values()) if(amount>0) amounts.merge(vault.owner(),amount,Math::addExact);
        }
        return amounts.entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed()
                .thenComparing(e->names.get(e.getKey()),String.CASE_INSENSITIVE_ORDER).thenComparing(Map.Entry::getKey))
                .map(e->new OwnerAmount(e.getKey(),names.get(e.getKey()),e.getValue())).toList();
    }
    public synchronized List<OwnerAmount> findTotals(String material, int limit) {
        return ranked(totalsByMaterial.getOrDefault(material,Map.of()),limit);
    }
    public synchronized List<OwnerAmount> findTotals(List<String> materials, int limit) {
        Map<UUID, Long> amounts = new HashMap<>();
        for(String material:materials) totalsByMaterial.getOrDefault(material,Map.of()).forEach((owner,amount)->amounts.merge(owner,amount,Long::sum));
        return ranked(amounts,limit);
    }
    private List<OwnerAmount> ranked(Map<UUID,Long> amounts,int limit) {
        return amounts.entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed()
                        .thenComparing(entry->indexedNames.getOrDefault(entry.getKey(),""),String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Map.Entry::getKey))
                .limit(limit).map(entry->new OwnerAmount(entry.getKey(),indexedNames.get(entry.getKey()),entry.getValue())).toList();
    }
    public record PersonalRank(int place,String material,String label,long amount) {}
    /** The same indexed owner totals used by findTotals and the market leaderboard. */
    public synchronized List<PersonalRank> personalTop(UUID owner,int topLimit) {
        if(topLimit<1) return List.of();
        List<PersonalRank> result=new ArrayList<>();
        for(String material:totalsByMaterial.keySet()) addPersonalRank(result,owner,topLimit,material,RussianItems.name(material),findTotals(material,topLimit));
        for(var group:ResourceGroups.all())
            addPersonalRank(result,owner,topLimit,group.code(),group.title(),findTotals(group.materials(),topLimit));
        result.sort(Comparator.comparingInt(PersonalRank::place).thenComparing(PersonalRank::label,String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PersonalRank::material));
        return List.copyOf(result);
    }
    private static void addPersonalRank(List<PersonalRank> result,UUID owner,int limit,String material,String label,List<OwnerAmount> ranked) {
        for(int i=0;i<ranked.size()&&i<limit;i++) if(ranked.get(i).owner().equals(owner)) {
            result.add(new PersonalRank(i+1,material,label,ranked.get(i).amount()));return;
        }
    }
}
