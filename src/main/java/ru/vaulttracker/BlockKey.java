package ru.vaulttracker;

import java.util.UUID;

/** Coordinates only: safe to pass between region threads and the database worker. */
public record BlockKey(UUID world, int x, int y, int z) {
    public String id() { return world + ":" + x + ":" + y + ":" + z; }
    public ChunkKey chunk() { return new ChunkKey(world, x >> 4, z >> 4); }
    public record ChunkKey(UUID world, int x, int z) {}
}
