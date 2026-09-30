package ru.vaulttracker;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FriendFindCommandTest {
    @Test void nicknameSearchSupportsAllItemsFilteringAndRevocation() throws Exception {
        var plugin=mock(VaultTrackerPlugin.class);var player=mock(Player.class);var world=mock(World.class);var server=mock(Server.class);
        UUID viewer=UUID.randomUUID(),owner=UUID.randomUUID(),worldId=UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);when(server.getWorld(worldId)).thenReturn(world);
        when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(viewer);when(player.hasPermission("vaulttracker.search")).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world,0,64,0));
        var storage=mock(StorageEngine.class);when(storage.ready()).thenReturn(true);
        var guard=mock(GuardService.class);when(guard.canSearch(viewer,owner)).thenReturn(true);
        var compass=mock(SearchCompass.class);var catalogue=new Catalogue(v->{});
        BlockKey chest=new BlockKey(worldId,1,64,0);
        catalogue.register(new BlockKey(worldId,1,65,0),owner,"Friend",List.of(chest),Map.of("DIAMOND",5L,"STONE",7L),1,0);
        for(var entry:Map.of("storage",storage,"guard",guard,"searchCompass",compass,"catalogue",catalogue).entrySet()) {
            var field=VaultTrackerPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());
        }
        var find=VaultTrackerPlugin.class.getDeclaredMethod("findOwn",org.bukkit.command.CommandSender.class,String[].class);find.setAccessible(true);
        catalogue.register(new BlockKey(worldId,2,65,0),viewer,"Viewer",List.of(new BlockKey(worldId,2,64,0)),Map.of("DIAMOND",3L),1,0);
        find.invoke(plugin,player,new String[]{"алмаз"});
        verify(compass).point(eq(player),argThat((List<OwnResourceSearch.Row> rows)->rows.size()==1&&rows.getFirst().amount()==3));
        verify(guard).recordItemSearch("DIAMOND");
        find.invoke(plugin,player,new String[]{"алмаз","2"});
        verify(guard,times(1)).recordItemSearch("DIAMOND");
        clearInvocations(compass);
        find.invoke(plugin,player,new String[]{"Friend"});
        verify(compass).point(eq(player),argThat((List<OwnResourceSearch.Row> rows)->rows.size()==2));
        clearInvocations(compass);
        find.invoke(plugin,player,new String[]{"Friend","алмаз"});
        verify(compass).point(eq(player),argThat((List<OwnResourceSearch.Row> rows)->rows.size()==1&&rows.getFirst().material().equals("DIAMOND")));
        clearInvocations(compass);when(guard.canSearch(viewer,owner)).thenReturn(false);
        find.invoke(plugin,player,new String[]{"Friend"});verifyNoInteractions(compass);
    }
}
