package ru.vaulttracker;

import java.util.*;

public record Snapshot(BlockKey sign, UUID generation, UUID owner, String playerName,
                       List<BlockKey> chests, Map<String, Long> items,
                       boolean active, long checkedAt, long revision) {
    public Snapshot {
        Objects.requireNonNull(sign);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(playerName);
        chests = List.copyOf(chests);
        items = Map.copyOf(items);
        if (chests.isEmpty() || chests.size() > 2 || new HashSet<>(chests).size() != chests.size())
            throw new IllegalArgumentException("A vault must have one or two distinct physical chests");
        if (chests.stream().anyMatch(p -> !p.world().equals(sign.world())))
            throw new IllegalArgumentException("World mismatch");
        if (items.values().stream().anyMatch(n -> n <= 0)) throw new IllegalArgumentException("Invalid amount");
        if (!active && !items.isEmpty()) throw new IllegalArgumentException("Deleted vault must have no items");
    }
}
