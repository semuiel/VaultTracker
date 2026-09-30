package ru.vaulttracker;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramChatApiTest {
    @TempDir Path topicFolder;
    @Test void detectsPrivateTopicsAndCreatesTopicThroughBotApi() throws Exception {
        var requests=new java.util.concurrent.CopyOnWriteArrayList<JsonObject>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/bottest/",exchange->{
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
            String response=exchange.getRequestURI().getPath().endsWith("getMe")
                    ?"{\"ok\":true,\"result\":{\"username\":\"TestBot\",\"has_topics_enabled\":true}}"
                    :"{\"ok\":true,\"result\":{\"message_thread_id\":314,\"name\":\"Уведомления\"}}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var config=new TelegramConfig(true,"test",Set.of(),Set.of(),List.of(),20,5,300,"http://127.0.0.1:"+server.getAddress().getPort(),new TelegramConfig.Proxy(TelegramConfig.ProxyType.NONE,"",0,"",""),new TelegramConfig.Retry(0,1000,1000),topicFolder.resolve("offset"));
        try(var api=new TelegramApi(config)) {
            assertEquals("TestBot",api.verify());assertTrue(api.privateTopicsEnabled());
            assertEquals(314,api.createPrivateTopic(42,"Уведомления"));
        } finally {server.stop(0);}
        assertEquals(42,requests.getLast().get("chat_id").getAsInt());
        assertEquals("Уведомления",requests.getLast().get("name").getAsString());
    }
    @Test void replyTargetComesFromOriginalMessageAndAnonymousPostsAreRejected() {
        var json=JsonParser.parseString("""
            {"update_id":1,"message":{"message_id":3,"chat":{"id":-100123,"type":"supergroup"},"from":{"id":42,"first_name":"Reporter"},"text":"/report bad","reply_to_message":{"message_id":2,"from":{"id":55,"first_name":"Target"},"text":"quoted"}}}
            """).getAsJsonObject();
        var incoming=TelegramApi.parseUpdate(json);assertEquals(55,incoming.reply().userId());assertEquals("quoted",incoming.reply().text());assertFalse(incoming.anonymous());
        json.getAsJsonObject("message").getAsJsonObject("reply_to_message").add("sender_chat",new JsonObject());
        assertEquals(0,TelegramApi.parseUpdate(json).reply().userId());
        assertTrue(TelegramApi.parseUpdate(json).reply().bot());
        json.getAsJsonObject("message").add("sender_chat",new JsonObject());assertTrue(TelegramApi.parseUpdate(json).anonymous());
    }
    @Test void replyMediaUsesExistingMediaLabels() {
        for(var item:Map.of("photo","[Фото]","animation","[GIF]","video","[Видео]","sticker","[Стикер]").entrySet()) {
            var reply=new JsonObject();reply.addProperty("message_id",2);reply.add("from",JsonParser.parseString("{\"id\":55,\"first_name\":\"Друг\"}"));reply.add(item.getKey(),new JsonArray());
            var message=JsonParser.parseString("{\"message_id\":3,\"chat\":{\"id\":-100,\"type\":\"supergroup\"},\"from\":{\"id\":42},\"text\":\"Ответ\"}").getAsJsonObject();message.add("reply_to_message",reply);
            var update=new JsonObject();update.addProperty("update_id",1);update.add("message",message);
            assertEquals(item.getValue(),TelegramApi.parseUpdate(update).reply().text());
        }
    }
    @Test void topicServiceReferenceIsNotTreatedAsReply() {
        var update=JsonParser.parseString("""
            {"update_id":4,"message":{"message_id":10,"chat":{"id":-100,"type":"supergroup"},"message_thread_id":486,
             "from":{"id":42,"first_name":"Semi"},"text":"test",
             "reply_to_message":{"message_id":486,"from":{"id":55,"first_name":"Semi"},"forum_topic_created":{"name":"Торговля"}}}}
            """).getAsJsonObject();
        assertNull(TelegramApi.parseUpdate(update).reply());
        update.getAsJsonObject("message").getAsJsonObject("reply_to_message").addProperty("text","настоящее сообщение");
        assertEquals("настоящее сообщение",TelegramApi.parseUpdate(update).reply().text());
    }
    @Test void displayNameExcludesUsernameButAdminProfilePreservesIt() {
        var update=TelegramApi.parseUpdate(JsonParser.parseString("{\"update_id\":1,\"message\":{\"message_id\":1,\"chat\":{\"id\":-100,\"type\":\"supergroup\"},\"from\":{\"id\":42,\"first_name\":\"Gaben\",\"last_name\":\"Example\",\"username\":\"GabenMax\"},\"text\":\"Hi\"}}").getAsJsonObject());
        assertEquals("Gaben Example",update.displayName());assertTrue(update.profile().contains("@GabenMax"));
    }
    @TempDir Path folder;
    @Test void customEmojiRejectionFallsBackToOrdinaryEmojiInSameTopic() throws Exception {
        var requests=new java.util.concurrent.CopyOnWriteArrayList<JsonObject>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/bottest/sendMessage",exchange->{
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
            boolean first=requests.size()==1;byte[] response=(first?"{\"ok\":false,\"error_code\":400,\"description\":\"CUSTOM_EMOJI_INVALID\"}":"{\"ok\":true,\"result\":{\"message_id\":9}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first?400:200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        var config=new TelegramConfig(true,"test",Set.of(),Set.of(),List.of(),20,5,300,"http://127.0.0.1:"+server.getAddress().getPort(),new TelegramConfig.Proxy(TelegramConfig.ProxyType.NONE,"",0,"",""),new TelegramConfig.Retry(0,1000,1000),folder.resolve("offset"));
        try(var api=new TelegramApi(config)) {api.sendHtml(-1001,486,"<tg-emoji emoji-id=\"1234\">🔥</tg-emoji> Alex",true);} finally {server.stop(0);}
        assertEquals(2,requests.size());assertEquals("🔥 Alex",requests.get(1).get("text").getAsString());
        for(var request:requests) {assertEquals(486,request.get("message_thread_id").getAsInt());assertTrue(request.get("disable_notification").getAsBoolean());assertEquals("HTML",request.get("parse_mode").getAsString());}
    }
    @Test void parsesCaptionsAndBotFlagWithoutTreatingServiceEventsAsChat() {
        var parsed=TelegramApi.parseUpdate(JsonParser.parseString("""
            {"update_id":3,"message":{"message_id":4,"chat":{"id":-1001,"type":"supergroup"},"message_thread_id":486,"from":{"id":42,"is_bot":true,"first_name":"Name"},"photo":[],"caption":"Caption"}}
            """).getAsJsonObject());
        assertEquals("[Фото] Caption",parsed.text());assertTrue(parsed.media());assertTrue(parsed.senderBot());assertEquals("Name",parsed.profile());
    }
}
