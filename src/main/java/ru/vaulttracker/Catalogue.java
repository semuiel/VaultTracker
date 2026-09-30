package ru.vaulttracker;

import java.util.*;
import java.util.function.Consumer;

/** Synchronizes metadata only. Never calls Bukkit, SQL, or other blocking I/O. */
public final class Catalogue {
    private final Map<BlockKey, Snapshot> vaults = new HashMap<>();
    private final Map<BlockKey, BlockKey> claims = new HashMap<>();
    private final Map<BlockKey.ChunkKey, Set<BlockKey>> chunks = new HashMap<>();
    private final Map<String,Map<UUID,Long>> totalsByMaterial = new HashMap<>();
    private final Map<UUID,Map<String,Long>> totalsByOwner = new HashMap<>();
    private final Map<UUID,Long> ownerTotals = new HashMap<>();
    private final Map<UUID,Set<BlockKey>> ownerVaults = new HashMap<>();
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
        ownerVaults.computeIfAbsent(v.owner(),ignored->new HashSet<>()).add(v.sign());
        Map<String,Long> ownerItems=totalsByOwner.computeIfAbsent(v.owner(),ignored->new HashMap<>());
        ownerTotals.putIfAbsent(v.owner(),0L);
        v.items().forEach((material,amount)->{
            ownerItems.merge(material,amount,Long::sum);ownerTotals.merge(v.owner(),amount,Long::sum);
        });
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
        var owned=ownerVaults.get(v.owner());owned.remove(v.sign());
        if(owned.isEmpty()) {
            ownerVaults.remove(v.owner());totalsByOwner.remove(v.owner());ownerTotals.remove(v.owner());
        } else {
            var ownerItems=totalsByOwner.get(v.owner());
            v.items().forEach((material,amount)->{
                ownerItems.computeIfPresent(material,(ignored,total)->total<=amount?null:total-amount);
                ownerTotals.computeIfPresent(v.owner(),(ignored,total)->total-amount);
            });
        }
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
        if (limit>0 && ownerVaults.getOrDefault(owner,Set.of()).size() >= limit)
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
        return new OwnerItems(owner,indexedNames.getOrDefault(owner,owner.toString()),Map.copyOf(totalsByOwner.getOrDefault(owner,Map.of())));
    }
    public synchronized List<OwnerItems> ownerItems(String nickname) {
        return ownerVaults.keySet().stream().filter(owner->indexedNames.get(owner).equalsIgnoreCase(nickname))
                .map(this::ownerItems).toList();
    }
    public synchronized List<Snapshot> ownerVaults(UUID owner) {
        return ownerVaults.getOrDefault(owner,Set.of()).stream().map(vaults::get).toList();
    }
    public synchronized List<String> ownerNames() {
        return ownerVaults.keySet().stream().map(indexedNames::get).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    /** Only copy owner aggregates under the catalogue lock; sort outside it. */
    private synchronized List<OwnerAmount> ownerTotalsSnapshot(boolean includeEmpty) {
        return ownerTotals.entrySet().stream().filter(entry->includeEmpty || entry.getValue()>0)
                .map(entry->new OwnerAmount(entry.getKey(),indexedNames.get(entry.getKey()),entry.getValue())).toList();
    }
    private static final Comparator<OwnerAmount> OWNER_RANK=Comparator.comparingLong(OwnerAmount::amount).reversed()
            .thenComparing(OwnerAmount::name,String.CASE_INSENSITIVE_ORDER).thenComparing(OwnerAmount::owner);
    public List<String> ownerNamesByTotalItems() {
        return ownerTotalsSnapshot(true).stream().sorted(OWNER_RANK).map(OwnerAmount::name).distinct().toList();
    }
    public synchronized List<String> find(String material, int limit) {
        return findTotals(material,limit).stream().map(row -> row.name()+" — "+row.amount()).toList();
    }
    public record OwnerAmount(UUID owner,String name,long amount) {}
    public List<OwnerAmount> allItemTotals() {
        return ownerTotalsSnapshot(false).stream().sorted(OWNER_RANK).toList();
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
