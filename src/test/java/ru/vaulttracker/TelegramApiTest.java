package ru.vaulttracker;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TelegramApiTest {
    @Test void distinguishesPrivateMessagesAndCallbacksFromGroupMessages() {
        var message=JsonParser.parseString("""
                {"update_id":1,"message":{"message_id":2,"from":{"id":42},"chat":{"id":42,"type":"private"},"text":"Alex"}}
                """).getAsJsonObject();
        assertTrue(TelegramApi.parseUpdate(message).privateChat());
        message.getAsJsonObject("message").getAsJsonObject("chat").addProperty("type","supergroup");
        assertFalse(TelegramApi.parseUpdate(message).privateChat());
        var callback=JsonParser.parseString("""
                {"update_id":9,"callback_query":{"id":"c1","from":{"id":42},"data":"vm:token:1",
                "message":{"message_id":55,"chat":{"id":42,"type":"private"}}}}
                """).getAsJsonObject();
        assertTrue(TelegramApi.parseUpdate(callback).privateChat());
    }

    @Test void putsChoicesOnSeparateRowsAndNavigationTogether() {
        var view=new TelegramCommands.View("Игроки",List.of(
                new TelegramCommands.Button("Alex","vm:t:0",0),new TelegramCommands.Button("Bob","vm:t:1",1),
                new TelegramCommands.Button("Назад","vm:t:2",2),new TelegramCommands.Button("Вперёд","vm:t:3",2)));
        var rows=TelegramApi.messageBody(42,view).getAsJsonObject("reply_markup").getAsJsonArray("inline_keyboard");
        assertEquals(3,rows.size()); assertEquals(1,rows.get(0).getAsJsonArray().size());
        assertEquals(2,rows.get(2).getAsJsonArray().size());
        assertEquals("Bob",rows.get(1).getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString());
    }
    @Test void parsesTopicCallbackWithAuthorAndMessage() {
        var json=JsonParser.parseString("""
                {"update_id":9,"callback_query":{"id":"callback-1","from":{"id":777},"data":"vt:token:2",
                "message":{"message_id":55,"message_thread_id":12345,"chat":{"id":-1001}}}}
                """).getAsJsonObject();
        var update=TelegramApi.parseUpdate(json);
        assertTrue(update.callback()); assertEquals(777,update.userId()); assertEquals(-1001,update.chatId());
        assertEquals(12345,update.topicId()); assertEquals(55,update.messageId()); assertEquals("vt:token:2",update.callbackData());
    }

    @Test void serializesInlineKeyboardForSendAndEdit() {
        var view=new TelegramCommands.View("Страница 1",List.of(new TelegramCommands.Button("Вперёд ▶","vt:token:2")));
        var body=TelegramApi.messageBody(-1001,view);
        var button=body.getAsJsonObject("reply_markup").getAsJsonArray("inline_keyboard")
                .get(0).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("Страница 1",body.get("text").getAsString());
        assertEquals("Вперёд ▶",button.get("text").getAsString());
        assertEquals("vt:token:2",button.get("callback_data").getAsString());
    }
    @Test void unchangedFormattedMessageIsBenignAndMustNotBeRewrittenAsPlainText() {
        assertTrue(TelegramApi.benignCallbackError(new java.io.IOException("Bad Request: message is not modified")));
    }
}
