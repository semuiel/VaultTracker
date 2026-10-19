package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LinkCommandTest {
    @TempDir Path path;

    @Test void gameCommandAcceptsGeneratedShortCodeAndDelegatesValidationToGuard() throws Exception {
        try(var guard=new GuardService(path,new GuardConfig(true,0,99),Logger.getAnonymousLogger())) {
            var plugin=mock(VaultTrackerPlugin.class);
            var player=mock(Player.class,RETURNS_DEEP_STUBS);
            var command=mock(Command.class);
            var bastion=mock(BastionTrust.class);
            UUID uuid=UUID.randomUUID();
            when(command.getName()).thenReturn("vtrack");
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.getName()).thenReturn("Alex");
            when(player.hasPermission("vaulttracker.link")).thenReturn(true);
            when(bastion.change(uuid,true)).thenReturn(CompletableFuture.completedFuture(new BastionTrust.Outcome(false,"Недоступен")));
            for(var entry:Map.of("guard",guard,"bastionTrust",bastion).entrySet()) {
                var field=VaultTrackerPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());
            }
            when(plugin.onCommand(eq(player),eq(command),anyString(),any(String[].class))).thenCallRealMethod();
            String code=TelegramEmojiMarkup.plain(guard.generate(42).get()).split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
            assertEquals(10,code.length());
            assertTrue(plugin.onCommand(player,command,"vtrack",new String[]{"link",code.toLowerCase(Locale.ROOT)}));
            assertEquals(uuid,guard.account(42).get().uuid());
            verify(bastion,timeout(2000)).change(uuid,true);

            when(command.getName()).thenReturn("unlink");
            when(player.isOp()).thenReturn(true);
            assertTrue(plugin.onCommand(player,command,"unlink",new String[0]));
            assertNull(guard.account(42).get());
            assertFalse(guard.linked(uuid));

            // A guessed or expired token must never create an account or grant trust.
            when(command.getName()).thenReturn("vtrack");
            clearInvocations(bastion);
            UUID other=UUID.randomUUID();when(player.getUniqueId()).thenReturn(other);
            plugin.onCommand(player,command,"vtrack",new String[]{"link",code});
            assertNull(guard.linkedPlayer(other).get());
            verifyNoInteractions(bastion);
        }
    }

    @Test void unlinkRequiresActualOpEvenWithAllPermissions() throws Exception {
        for(String form:List.of("unlink")) {
            try(var guard=new GuardService(path.resolve(form),new GuardConfig(true,0,99),Logger.getAnonymousLogger())) {
                var plugin=mock(VaultTrackerPlugin.class);var player=mock(Player.class,RETURNS_DEEP_STUBS);var command=mock(Command.class);
                UUID uuid=UUID.randomUUID();when(player.getUniqueId()).thenReturn(uuid);when(player.hasPermission(anyString())).thenReturn(true);
                var field=VaultTrackerPlugin.class.getDeclaredField("guard");field.setAccessible(true);field.set(plugin,guard);
                String code=TelegramEmojiMarkup.plain(guard.generate(42).get()).split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
                guard.link(uuid,"Alex",code).get();when(command.getName()).thenReturn(form);
                when(plugin.onCommand(eq(player),eq(command),anyString(),any(String[].class))).thenCallRealMethod();
                String[] args=new String[0];
                assertTrue(plugin.onCommand(player,command,form,args));assertTrue(guard.linked(uuid));
                when(player.isOp()).thenReturn(true);when(player.hasPermission(anyString())).thenReturn(false);
                assertTrue(plugin.onCommand(player,command,form,args));assertNull(guard.account(42).get());
            }
        }
    }
}
