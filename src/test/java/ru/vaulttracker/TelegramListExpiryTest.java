package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramListExpiryTest {
    @TempDir Path folder;
    TelegramApi api=mock(TelegramApi.class);
    Logger logger=mock(Logger.class);

    @Test void deletesAtDeadlineAndKeepsDeadlineAcrossRestart() throws Exception {
        Path file=folder.resolve("telegramchat-123-lists.properties");
        try(var expiry=new TelegramListExpiry(file,api,logger)) {
            expiry.track(-1001,42,300000);
            expiry.deleteDue(299999);verifyNoInteractions(api);
        }
        try(var restored=new TelegramListExpiry(file,api,logger)) {
            restored.deleteDue(300000);verify(api).delete(-1001,42);
        }
        try(var restored=new TelegramListExpiry(file,api,logger)) {
            restored.deleteDue(600000);verifyNoMoreInteractions(api);
        }
    }

    @Test void transientFailureRetriesAndIsNotLostOnRestart() throws Exception {
        Path file=folder.resolve("pending.properties");
        doThrow(new IOException("proxy unavailable")).doNothing().when(api).delete(-1001,42);
        when(api.safe(any())).thenReturn("proxy unavailable");
        try(var expiry=new TelegramListExpiry(file,api,logger)) {
            expiry.track(-1001,42,300000);expiry.deleteDue(300000);expiry.deleteDue(329999);
            verify(api,times(1)).delete(-1001,42);verify(logger).warning(contains("Повтор через 30 секунд"));
            assertTrue(Files.readString(file).contains("300000"));
        }
        try(var restored=new TelegramListExpiry(file,api,logger)) {
            restored.deleteDue(330000);verify(api,times(2)).delete(-1001,42);
            restored.deleteDue(360000);verify(api,times(2)).delete(-1001,42);
        }
    }

    @Test void alreadyDeletedMessageFinishesWithoutRetryOrWarning() throws Exception {
        doThrow(new IOException("Bad Request: message to delete not found")).when(api).delete(-1001,42);
        try(var expiry=new TelegramListExpiry(folder.resolve("pending.properties"),api,logger)) {
            expiry.track(-1001,42,300000);expiry.deleteDue(300000);expiry.deleteDue(330000);
            verify(api,times(1)).delete(-1001,42);verifyNoInteractions(logger);
        }
    }

    @Test void sameMessageIdsInDifferentChatsHaveIndependentDeadlines() throws Exception {
        try(var expiry=new TelegramListExpiry(folder.resolve("pending.properties"),api,logger)) {
            expiry.track(-1001,42,300000);expiry.track(-1002,42,400000);
            expiry.deleteDue(300000);verify(api).delete(-1001,42);verify(api,never()).delete(-1002,42);
            expiry.deleteDue(400000);verify(api).delete(-1002,42);
        }
    }
}
