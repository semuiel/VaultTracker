package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameMessagesTest {
    @Test void playerMessagesUseFlexityNameAndConsoleRetainsVaultTrackerName() {
        Player player=mock(Player.class);CommandSender console=mock(CommandSender.class);
        GameMessages.send(player,"Каталог готов.",GameMessages.Tone.SUCCESS);
        GameMessages.send(console,"Каталог готов.",GameMessages.Tone.SUCCESS);
        var message=mockingDetails(player).getInvocations().stream().filter(i->i.getMethod().getName().equals("sendMessage"))
                .map(i->(Component)i.getArgument(0)).findFirst().orElseThrow();
        String plain=PlainTextComponentSerializer.plainText().serialize(message);
        assertTrue(plain.contains("FLEXITY"));assertTrue(plain.contains("Каталог готов."));assertFalse(plain.contains("[VaultTracker]"));
        assertEquals(TextColor.color(0x10B981),message.children().getLast().color());
        assertEquals(TextColor.color(0xF87171),GameMessages.format("Ошибка",GameMessages.Tone.ERROR).children().getLast().color());
        verify(console).sendMessage(Component.text("[VaultTracker] Каталог готов."));
    }
}
