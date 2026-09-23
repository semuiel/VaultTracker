package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchCompassTest {
    JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);
    Player player=mock(Player.class);World world=mock(World.class);
    AsyncScheduler async=mock(AsyncScheduler.class);EntityScheduler entity=mock(EntityScheduler.class);
    AtomicReference<Location> target=new AtomicReference<>();List<Consumer<ScheduledTask>> timers=new ArrayList<>();
    SearchCompass compass;
    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server);when(server.getAsyncScheduler()).thenReturn(async);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world,0,64,0));when(player.getScheduler()).thenReturn(entity);
        target.set(new Location(world,500,64,500));when(player.getCompassTarget()).thenAnswer(c->target.get());
        doAnswer(c->{target.set(c.getArgument(0));return null;}).when(player).setCompassTarget(any());
        when(async.runDelayed(eq(plugin),any(),eq(120L),eq(TimeUnit.SECONDS))).thenAnswer(c->{timers.add(c.getArgument(1));return mock(ScheduledTask.class);});
        when(entity.run(eq(plugin),any(),any())).thenAnswer(c->{c.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);});
        compass=new SearchCompass(plugin);
    }
    OwnResourceSearch.Row row(int x) {return new OwnResourceSearch.Row(new BlockKey(world.getUID(),x,64,0),"DIAMOND",1,0);}
    @Test void pointsToNearestEvenWhenResultsAreUnsortedAndRestoresAfterTwoMinutes() {
        Location original=target.get();compass.point(player,List.of(row(80),row(3)));
        assertEquals(3,target.get().getBlockX());assertEquals(1,timers.size());
        timers.getFirst().accept(mock(ScheduledTask.class));assertEquals(original,target.get());
    }
    @Test void repeatSearchKeepsOriginalAndOldTimeoutCannotResetNewTarget() {
        Location original=target.get();compass.point(player,List.of(row(3)));compass.point(player,List.of(row(8)));
        timers.get(0).accept(mock(ScheduledTask.class));assertEquals(8,target.get().getBlockX());
        timers.get(1).accept(mock(ScheduledTask.class));assertEquals(original,target.get());
    }
    @Test void skipsOfflineAndOtherDimensionsAndCancelsWhenNothingFound() {
        Location original=target.get();when(player.isOnline()).thenReturn(false);compass.point(player,List.of(row(3)));assertTrue(timers.isEmpty());
        when(player.isOnline()).thenReturn(true);
        compass.point(player,List.of(new OwnResourceSearch.Row(new BlockKey(UUID.randomUUID(),1,64,0),"DIAMOND",1,0)));assertTrue(timers.isEmpty());
        compass.point(player,List.of(row(3)));compass.point(player,List.of());assertEquals(original,target.get());
    }
    @Test void doesNotOverwriteAnotherPluginsTargetAndRestoresOnLogout() {
        compass.point(player,List.of(row(3)));Location external=new Location(world,20,64,20);target.set(external);
        timers.getFirst().accept(mock(ScheduledTask.class));assertEquals(external,target.get());
        compass.point(player,List.of(row(4)));PlayerQuitEvent event=mock(PlayerQuitEvent.class);when(event.getPlayer()).thenReturn(player);compass.quit(event);assertEquals(external,target.get());
    }
    @Test void explicitChoiceTargetsChestCenterAndRejectsForeignRemovedOrOtherWorldChests() {
        Catalogue catalogue=new Catalogue(v->{});compass=new SearchCompass(plugin,catalogue);
        when(player.getWorld()).thenReturn(world);when(player.hasPermission("vaulttracker.search")).thenReturn(true);
        BlockKey near=row(3).chest(),far=row(80).chest();
        var first=catalogue.register(near,player.getUniqueId(),"Alex",List.of(near),Map.of("DIAMOND",1L),0,0);
        var chosen=catalogue.register(far,player.getUniqueId(),"Alex",List.of(far),Map.of("DIAMOND",1L),0,0);
        compass.point(player,List.of(row(3),row(80)));assertEquals(3,target.get().getBlockX());
        assertTrue(compass.select(player,far).contains("направлен"));assertEquals(80.5,target.get().getX());assertEquals(64.5,target.get().getY());
        BlockKey foreign=row(90).chest();catalogue.register(foreign,UUID.randomUUID(),"Other",List.of(foreign),Map.of(),0,0);
        assertTrue(compass.select(player,foreign).contains("не зарегистрирован"));assertEquals(80.5,target.get().getX());
        catalogue.remove(far,chosen.generation(),1);assertTrue(compass.select(player,far).contains("не зарегистрирован"));
        BlockKey dimension=new BlockKey(UUID.randomUUID(),1,2,3);catalogue.register(dimension,player.getUniqueId(),"Alex",List.of(dimension),Map.of(),0,0);
        assertTrue(compass.select(player,dimension).contains("другом мире"));assertEquals(80.5,target.get().getX());
        when(player.hasPermission("vaulttracker.search")).thenReturn(false);assertTrue(compass.select(player,near).contains("Нет права"));
    }
}
