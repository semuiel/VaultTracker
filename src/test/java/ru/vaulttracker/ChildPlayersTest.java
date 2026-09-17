package ru.vaulttracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.entity.EntityDamageEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChildPlayersTest {
    @TempDir Path folder;
    @Test void superCanAddByLinkedTelegramIdOrNickname() throws Exception {
        UUID id=UUID.randomUUID();
        try(var guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,99),Logger.getAnonymousLogger())) {
            String code=guard.generate(123456).get().split("/vtrack link ")[1].substring(0,32);guard.link(id,"Alex",code).get();
            var moderation=spy(new TelegramModeration(mock(JavaPlugin.class),guard));
            moderation.changeChild(99,"123456",true).get();assertNotNull(guard.child(id));
            moderation.changeChild(99,"Alex",false).get();assertNull(guard.child(id));
            doReturn(CompletableFuture.completedFuture(List.of(new TelegramModeration.Person(id,"Alex",false,"")))).when(moderation).players(99,"all");
            moderation.changeChild(99,"alex",true).get();assertNotNull(guard.child(id));
            assertThrows(SecurityException.class,()->moderation.changeChild(123456,"Alex",false));
        }
    }
    @Test void childrenPersistAndOnlyLinkedChildCanChangeOwnSize() throws Exception {
        UUID id=UUID.randomUUID();var config=new GuardConfig(true,0,99);
        try(var guard=new GuardService(folder.resolve("guard"),config,Logger.getAnonymousLogger())) {
            assertThrows(ExecutionException.class,()->guard.child(42,id,"Alex",true).get());
            guard.child(99,id,"Alex",true).get();
            assertThrows(ExecutionException.class,()->guard.childSize(42,0.6).get());
            String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,32);guard.link(id,"Alex",code).get();
            guard.childSize(42,0.6).get();assertEquals(0.6,guard.child(id).size());
            assertThrows(ExecutionException.class,()->guard.childSize(42,20).get());
            assertThrows(ExecutionException.class,()->guard.childSize(43,0.75).get());
        }
        try(var guard=new GuardService(folder.resolve("guard"),config,Logger.getAnonymousLogger())) {
            assertEquals(0.6,guard.child(id).size());guard.childSize(42,1).get();assertNotNull(guard.child(id));
            guard.child(99,id,"Alex",false).get();assertNull(guard.child(id));assertThrows(ExecutionException.class,()->guard.childSize(42,0.6).get());
        }
    }
    @Test void modifiersAreOwnedAndRemovedWithoutChangingOtherAttributes() {
        GuardService guard=mock(GuardService.class);Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        AttributeInstance health=mock(AttributeInstance.class),size=mock(AttributeInstance.class);
        when(size.getBaseValue()).thenReturn(1.0);when(health.getValue()).thenReturn(24.0);
        when(guard.child(id)).thenReturn(new GuardService.Child(id,"Alex",0.6));var service=new ChildPlayers(mock(JavaPlugin.class),guard);service.apply(player,health,size);
        verify(health).addTransientModifier(argThat(m->m.getKey().equals(ChildPlayers.HEALTH)&&m.getAmount()==4));
        verify(size).addTransientModifier(argThat(m->m.getKey().equals(ChildPlayers.SIZE)&&Math.abs(m.getAmount()+0.4)<1e-8));
        var healthMod=new AttributeModifier(ChildPlayers.HEALTH,4,AttributeModifier.Operation.ADD_NUMBER);when(health.getModifier(ChildPlayers.HEALTH)).thenReturn(healthMod);
        when(guard.child(id)).thenReturn(null);when(health.getValue()).thenReturn(20.0);when(player.getHealth()).thenReturn(24.0);service.apply(player,health,size);
        verify(health).removeModifier(healthMod);verify(player).setHealth(20);verify(health,never()).setBaseValue(anyDouble());verify(size,never()).setBaseValue(anyDouble());
    }
    @SuppressWarnings("deprecation")
    @Test void defenseReducesFinalDamageAndDoesNotProtectAgainstVoid() {
        var guard=mock(GuardService.class);var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(guard.child(id)).thenReturn(new GuardService.Child(id,"Alex",1));
        var event=mock(EntityDamageEvent.class);when(event.getEntity()).thenReturn(player);when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);when(event.getFinalDamage()).thenReturn(5.0);when(event.getDamage(EntityDamageEvent.DamageModifier.BASE)).thenReturn(10.0);
        var service=new ChildPlayers(mock(JavaPlugin.class),guard);service.damage(event);verify(event).setDamage(EntityDamageEvent.DamageModifier.BASE,9.0);
        clearInvocations(event);when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.VOID);service.damage(event);verify(event,never()).setDamage(any(EntityDamageEvent.DamageModifier.class),anyDouble());
    }
    @Test void shortMarkerMatchesFullMarker() {for(String value:List.of("[vault]","[v]"," [V] ","[VAULT]")) assertTrue(VaultTrackerPlugin.marker(value));assertFalse(VaultTrackerPlugin.marker("v"));}
}
