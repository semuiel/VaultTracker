package ru.vaulttracker;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TelegramApiTest {
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
}
