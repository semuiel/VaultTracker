package ru.vaulttracker;

import java.util.*;

/** The web layer can only read normalized history. No game objects or mutation operations. */
interface CoreProtectLookupSource extends AutoCloseable {
    record Category(String id,String label,String emoji,boolean material,boolean text) {}
    record Capabilities(List<Category> categories,List<String> worlds,boolean usernameHistory) {}
    record Event(String id,long timestamp,String player,String uuid,String category,String action,
                 String material,Integer amount,String world,int x,int y,int z,Boolean rolledBack,String text) {}
    record Page(List<Event> events,boolean hasMore) {}
    record Player(String name,String uuid) {}
    record Players(List<Player> players,boolean hasMore) {}
    default Players players(String search,int offset,int limit) throws Exception {throw new UnsupportedOperationException("Player catalogue unavailable");}
    Capabilities capabilities() throws Exception;
    Page lookup(CoreProtectQuery query) throws Exception;
    @Override default void close() {}
}
