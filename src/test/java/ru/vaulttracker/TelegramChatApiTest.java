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
