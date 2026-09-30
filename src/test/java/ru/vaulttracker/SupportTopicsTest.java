package ru.vaulttracker;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SupportTopicsTest {
    @TempDir Path folder;
    @Test void readsOnlyExistingAdminChatAndSupportTopicIds() throws Exception {
        Files.writeString(folder.resolve("telegramchat.yml"),"""
                reports:
                  chatId: -1002748891862
                  topicId: 34339
                support:
                  complaintsTopicId: 10
                  suggestionsTopicId: 11
                  bugsTopicId: 0
                  unbanRequestsTopicId: 14
                """);
        var topics=SupportTopics.load(folder);
        assertEquals(-1002748891862L,topics.chatId());
        assertTrue(topics.configured(SupportTicket.Type.COMPLAINT));
        assertTrue(topics.configured(SupportTicket.Type.SUGGESTION));
        assertFalse(topics.configured(SupportTicket.Type.BUG));
        assertTrue(topics.configured(SupportTicket.Type.UNBAN));
    }
}
