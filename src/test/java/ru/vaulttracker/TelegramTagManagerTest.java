package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramTagManagerTest {
    @Test void appliesRegularMemberTagOncePerUniqueConfiguredChat() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);TelegramApi api=mock(TelegramApi.class);
        when(config.chats()).thenReturn(List.of(new TelegramConfig.Chat(true,-1001,10),new TelegramConfig.Chat(false,-1001,20)));
        when(config.allowedChatIds()).thenReturn(Set.of(-1001L,-1002L));
        when(api.memberStatus(anyLong(),eq(42L))).thenReturn("member");
        var result=new TelegramTagManager(config,api).apply(42,"Alex");
        assertTrue(result.complete());assertTrue(result.text().contains("применён"));assertFalse(result.text().contains("-1001"));
        verify(api).setMemberTag(-1001,42,"Alex");verify(api).setMemberTag(-1002,42,"Alex");
        verify(api,never()).setAdministratorTitle(anyLong(),anyLong(),anyString());
    }

    @Test void usesAdministratorTitleAndResetUsesEmptyTitle() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);TelegramApi api=mock(TelegramApi.class);
        when(config.chats()).thenReturn(List.of(new TelegramConfig.Chat(true,-1001,0)));
        when(config.allowedChatIds()).thenReturn(Set.of());when(api.memberStatus(-1001,42)).thenReturn("administrator");
        var manager=new TelegramTagManager(config,api);
        assertTrue(manager.apply(42,"12345678901234567").complete());
        verify(api).setAdministratorTitle(-1001,42,"1234567890123456");
        assertTrue(manager.reset(42).complete());verify(api).setAdministratorTitle(-1001,42,"");
    }

    @Test void reportsPerChatFailuresWithoutAbortingOtherChats() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);TelegramApi api=mock(TelegramApi.class);
        when(config.chats()).thenReturn(List.of(new TelegramConfig.Chat(true,-1001,0),new TelegramConfig.Chat(false,-1002,0)));
        when(config.allowedChatIds()).thenReturn(Set.of());when(api.memberStatus(-1001,42)).thenReturn("left");
        when(api.memberStatus(-1002,42)).thenThrow(new IOException("missing can_manage_tags"));when(api.safe(any())).thenAnswer(i->i.getArgument(0,Exception.class).getMessage());
        var result=new TelegramTagManager(config,api).apply(42,"Alex");
        assertFalse(result.complete());assertFalse(result.anySuccess());assertFalse(result.text().contains("-1001"));assertFalse(result.text().contains("-1002"));assertEquals("🏷 Тег не изменён.",result.text());
    }
}
