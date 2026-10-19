package ru.vaulttracker;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TelegramAnalyticsOutgoingTest {
    @TempDir Path folder;
    final long chat=-1001;
    final ZoneId zone=ZoneId.of("Asia/Novosibirsk");
    final long now=LocalDate.parse("2026-10-17").atTime(12,0).atZone(zone).toEpochSecond();
    final AtomicReference<String> response=new AtomicReference<>();
    final AtomicInteger status=new AtomicInteger(200);
    TelegramAnalyticsService analytics;
    TelegramAnalyticsConfig config;
    TelegramApi api;
    HttpServer server;
    @BeforeEach void setup() throws Exception {
        config=new TelegramAnalyticsConfig(true,Set.of(chat),zone,chat,"https://example.test/telegram-stats/",60);
        analytics=new TelegramAnalyticsService(folder,config,()->now);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try(var body=exchange.getResponseBody()){body.write(bytes);}
        });server.start();api=transport("123:fixture");analytics.attach(api);
    }
    TelegramApi transport(String token) {
        return new TelegramApi(new TelegramConfig(true,token,Set.of(chat),Set.of(),List.of(),8,5,300,
                "http://127.0.0.1:"+server.getAddress().getPort(),new TelegramConfig.Proxy(TelegramConfig.ProxyType.NONE,"",0,"",""),
                new TelegramConfig.Retry(0,1000,1000),folder.resolve("telegram.offset")));
    }
    @AfterEach void close(){if(analytics!=null)analytics.close();if(api!=null)api.close();if(server!=null)server.stop(0);}
    JsonObject message(long target,int id,String type) {
        var message=new JsonObject();message.addProperty("message_id",id);message.addProperty("date",now);
        var targetChat=new JsonObject();targetChat.addProperty("id",target);targetChat.addProperty("type",target>0?"private":"supergroup");message.add("chat",targetChat);
        message.addProperty("message_thread_id",7);message.add("from",JsonParser.parseString("{\"id\":999,\"is_bot\":true}"));
        if(type.equals("text"))message.addProperty("text","Player: PRIVATE FIXTURE BODY");else message.add(type,new JsonObject());
        return message;
    }
    void answer(JsonElement value){var result=new JsonObject();result.addProperty("ok",true);result.add("result",value);response.set(result.toString());}
    JsonObject update(JsonObject message){var update=new JsonObject();update.add("message",message);return update;}
    Map<String,Object> report() throws Exception {
        assertTrue(analytics.awaitIdle(5000));
        var day=LocalDate.parse("2026-10-17");
        return analytics.queries.report(TelegramAnalyticsQuery.parse(Map.of("period","all"),config,day,day),now);
    }
    @SuppressWarnings("unchecked") Map<String,Object> summary() throws Exception {return (Map<String,Object>)report().get("summary");}
    long count(String field) throws Exception {return TelegramAnalyticsQueries.n(summary(),field);}
    @Test void actualSuccessfulSendCountsWithoutPollingAndEditsOrDuplicatesDoNotAddMessages() throws Exception {
        var sent=message(chat,100,"text");answer(sent);
        assertEquals(100,api.sendHtml(chat,7,"Player: PRIVATE FIXTURE BODY",false));
        assertEquals(1,count("messages"));assertEquals(0,count("authors"));
        api.editHtml(chat,100,"Edited",List.of());
        analytics.capture(update(sent));analytics.capture(update(sent));
        assertEquals(1,count("messages"));assertEquals(0,count("authors"));
        try(var db=analytics.repository.read()) {
            assertEquals(7,TelegramAnalyticsQueries.n(TelegramAnalyticsQueries.one(db,"SELECT topic FROM ta_messages WHERE chat=? AND msg=?",chat,100),"topic"));
        }
        assertFalse(report().toString().contains("PRIVATE FIXTURE BODY"));
    }
    @Test void bothDocumentUploadsCountFromTheirRealMultipartResponses() throws Exception {
        answer(message(chat,100,"document"));api.sendDocument(chat,7,"fixture.md","PRIVATE DOCUMENT BODY");
        Path file=folder.resolve("fixture.txt");Files.writeString(file,"PRIVATE FILE BODY");
        answer(message(chat,101,"document"));api.sendDocument(chat,file,"fixture.txt");
        assertEquals(2,count("messages"));assertEquals(0,count("authors"));
        try(var db=analytics.repository.read()) {assertEquals(2,TelegramAnalyticsQueries.n(TelegramAnalyticsQueries.one(db,"SELECT SUM(n) n FROM ta_daily WHERE kind='document'"),"n"));}
        assertFalse(report().toString().contains("PRIVATE"));
    }
    @Test void copiedMessageCountsAndDoesNotInventOrChangeChatKind() throws Exception {
        analytics.submit(r->r.snapshot(chat,now,10,60,"Channel","channel"));assertTrue(analytics.awaitIdle(5000));
        answer(JsonParser.parseString("{\"message_id\":100}"));
        assertEquals(100,api.copyMessage(42,88,chat,7));
        assertEquals(1,count("messages"));assertEquals(0,count("authors"));
        try(var db=analytics.repository.read()) {assertEquals("channel",TelegramAnalyticsQueries.one(db,"SELECT kind FROM ta_chats WHERE chat=?",chat).get("kind"));}
    }
    @Test void privateUntrackedAndFailedSendsNeverEnterStatistics() throws Exception {
        answer(message(42,100,"text"));api.send(42,0,new TelegramCommands.View("Private"));
        answer(message(-777,101,"text"));api.send(-777,0,new TelegramCommands.View("Untracked"));
        status.set(500);response.set("{\"ok\":false,\"error_code\":500,\"description\":\"fixture failure\"}");
        assertThrows(java.io.IOException.class,()->api.send(chat,7,new TelegramCommands.View("Failed")));
        assertEquals(0,count("messages"));assertEquals(0,count("authors"));
    }
    @Test void bothBotTransportsAndAvailableBotUpdatesCountButOnlyHumansAreAuthors() throws Exception {
        answer(message(chat,100,"text"));api.send(chat,7,new TelegramCommands.View("Bot"));
        try(var bridge=transport("456:fixture")) {
            analytics.attach(bridge);answer(message(chat,101,"text"));bridge.sendHtml(chat,7,"Game chat",false);
        }
        analytics.capture(update(message(chat,102,"text")));
        var human=message(chat,103,"text");human.add("from",JsonParser.parseString("{\"id\":7,\"is_bot\":false}"));analytics.capture(update(human));
        assertEquals(4,count("messages"));assertEquals(1,count("authors"));
        assertEquals(0,analytics.failures());
    }
    @Test void mediaGroupsForwardedOutputAndNonMessageResultsAreHandled() throws Exception {
        var album=new JsonArray();album.add(message(chat,100,"photo"));album.add(message(chat,101,"video"));
        analytics.outgoing("sendMediaGroup",album,null);
        var forwarded=message(chat,102,"text");forwarded.add("forward_origin",new JsonObject());
        forwarded.add("from",JsonParser.parseString("{\"id\":7,\"is_bot\":false}"));
        analytics.outgoing("forwardMessage",forwarded,null);
        analytics.outgoing("sendChatAction",new JsonPrimitive(true),null);
        analytics.outgoing("getChat",message(chat,103,"text"),null);
        assertEquals(3,count("messages"));assertEquals(0,count("authors"));
        assertEquals(2,count("media"));assertEquals(1,count("forwards"));
    }
    @Test void stoppingAnalyticsDoesNotBreakBotSending() throws Exception {
        analytics.close();answer(message(chat,100,"text"));
        assertEquals(100,api.send(chat,7,new TelegramCommands.View("Continues to work")));
    }
}
