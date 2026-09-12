package ru.vaulttracker;

import com.google.gson.*;
import okhttp3.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class TelegramApi implements AutoCloseable {
    record Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,
                    String callbackId,String callbackData) {
        boolean callback() { return callbackId!=null; }
    }
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
    private final TelegramConfig config;
    private final String baseUrl;
    private final Gson gson=new Gson();
    private final OkHttpClient client;
    private final java.net.Authenticator previousAuthenticator, socksAuthenticator;

    TelegramApi(TelegramConfig config) {
        this.config=config; this.baseUrl=config.botApiUrl()+"/bot"+config.token()+"/";
        OkHttpClient.Builder builder=new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15)).writeTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(config.pollTimeoutSeconds()+20L));
        java.net.Authenticator previous=null, installed=null;
        if(config.proxy().enabled()) {
            java.net.Proxy.Type javaType=config.proxy().type()==TelegramConfig.ProxyType.SOCKS5
                    ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP;
            builder.proxy(new java.net.Proxy(javaType,InetSocketAddress.createUnresolved(config.proxy().host(),config.proxy().port())));
            if(config.proxy().authenticated() && config.proxy().type()==TelegramConfig.ProxyType.HTTP)
                builder.proxyAuthenticator((route,response) -> response.request().newBuilder()
                        .header("Proxy-Authorization",Credentials.basic(config.proxy().username(),config.proxy().password())).build());
            if(config.proxy().authenticated() && config.proxy().type()==TelegramConfig.ProxyType.SOCKS5) {
                previous=java.net.Authenticator.getDefault();
                TelegramConfig.Proxy proxy=config.proxy();
                installed=new java.net.Authenticator() {
                    @Override protected PasswordAuthentication getPasswordAuthentication() {
                        if(getRequestingPort()==proxy.port() && proxy.host().equalsIgnoreCase(getRequestingHost()))
                            return new PasswordAuthentication(proxy.username(),proxy.password().toCharArray());
                        return null;
                    }
                };
                java.net.Authenticator.setDefault(installed);
            }
        }
        previousAuthenticator=previous; socksAuthenticator=installed; client=builder.build();
    }
    String verify() throws IOException { return call("getMe",new JsonObject()).getAsJsonObject("result").get("username").getAsString(); }
    void registerCommands() throws IOException {
        JsonArray commands=new JsonArray();
        commands.add(command("topitem","Найти владельцев ресурса"));
        JsonObject body=new JsonObject(); body.add("commands",commands); call("setMyCommands",body);
    }
    private static JsonObject command(String name,String description) {
        JsonObject value=new JsonObject(); value.addProperty("command",name); value.addProperty("description",description); return value;
    }
    List<Incoming> updates(long offset) throws IOException {
        JsonObject body=new JsonObject(); body.addProperty("offset",offset); body.addProperty("timeout",config.pollTimeoutSeconds());
        body.addProperty("limit",50); JsonArray allowed=new JsonArray(); allowed.add("message"); allowed.add("callback_query"); body.add("allowed_updates",allowed);
        List<Incoming> result=new ArrayList<>();
        for(JsonElement element:call("getUpdates",body).getAsJsonArray("result")) {
            result.add(parseUpdate(element.getAsJsonObject()));
        }
        return List.copyOf(result);
    }
    static Incoming parseUpdate(JsonObject update) {
        long id=update.get("update_id").getAsLong();
        if(update.has("message")) return incomingMessage(id,update.getAsJsonObject("message"));
        if(update.has("callback_query")) {
            JsonObject callback=update.getAsJsonObject("callback_query");
            JsonObject message=callback.has("message") ? callback.getAsJsonObject("message") : null;
            if(message==null) return new Incoming(id,0,0,userId(callback),0,null,
                    callback.get("id").getAsString(),callback.has("data") ? callback.get("data").getAsString() : null);
            return new Incoming(id,message.getAsJsonObject("chat").get("id").getAsLong(),
                    message.has("message_thread_id") ? message.get("message_thread_id").getAsInt() : 0,
                    userId(callback),message.get("message_id").getAsInt(),null,callback.get("id").getAsString(),
                    callback.has("data") ? callback.get("data").getAsString() : null);
        }
        return new Incoming(id,0,0,0,0,null,null,null);
    }
    private static Incoming incomingMessage(long updateId,JsonObject message) {
        return new Incoming(updateId,message.getAsJsonObject("chat").get("id").getAsLong(),
                message.has("message_thread_id") ? message.get("message_thread_id").getAsInt() : 0,
                userId(message),message.get("message_id").getAsInt(),message.has("text") ? message.get("text").getAsString() : null,
                null,null);
    }
    private static long userId(JsonObject object) {
        return object.has("from") && object.getAsJsonObject("from").has("id")
                ? object.getAsJsonObject("from").get("id").getAsLong() : 0;
    }
    int send(long chatId,int topicId,TelegramCommands.View view) throws IOException {
        JsonObject body=messageBody(chatId,view);
        if(topicId>0) body.addProperty("message_thread_id",topicId);
        return call("sendMessage",body).getAsJsonObject("result").get("message_id").getAsInt();
    }
    void edit(long chatId,int messageId,TelegramCommands.View view) throws IOException {
        JsonObject body=messageBody(chatId,view); body.addProperty("message_id",messageId);
        call("editMessageText",body);
    }
    void answerCallback(String callbackId,String text,boolean alert) throws IOException {
        JsonObject body=new JsonObject(); body.addProperty("callback_query_id",callbackId);
        if(text!=null && !text.isBlank()) body.addProperty("text",text);
        if(alert) body.addProperty("show_alert",true);
        call("answerCallbackQuery",body);
    }
    void delete(long chatId,int messageId) throws IOException {
        JsonObject body=new JsonObject(); body.addProperty("chat_id",chatId); body.addProperty("message_id",messageId);
        call("deleteMessage",body);
    }
    static JsonObject messageBody(long chatId,TelegramCommands.View view) {
        JsonObject body=new JsonObject(); body.addProperty("chat_id",chatId); body.addProperty("text",view.text());
        body.addProperty("disable_web_page_preview",true);
        JsonArray keyboard=new JsonArray();
        if(!view.buttons().isEmpty()) {
            JsonArray row=new JsonArray();
            for(var button:view.buttons()) {
                JsonObject value=new JsonObject(); value.addProperty("text",button.text()); value.addProperty("callback_data",button.data()); row.add(value);
            }
            keyboard.add(row);
        }
        JsonObject markup=new JsonObject(); markup.add("inline_keyboard",keyboard); body.add("reply_markup",markup);
        return body;
    }
    private JsonObject call(String method,JsonObject body) throws IOException {
        Request request=new Request.Builder().url(baseUrl+method).post(RequestBody.create(gson.toJson(body),JSON)).build();
        try(Response response=client.newCall(request).execute()) {
            String raw=response.body()==null ? "" : response.body().string(); JsonObject json;
            try { json=JsonParser.parseString(raw).getAsJsonObject(); }
            catch(RuntimeException e) { throw new IOException("Telegram вернул некорректный ответ (HTTP "+response.code()+")"); }
            if(!response.isSuccessful() || !json.has("ok") || !json.get("ok").getAsBoolean())
                throw new IOException("Telegram API: "+(json.has("description") ? json.get("description").getAsString() : "HTTP "+response.code()));
            return json;
        }
    }
    static List<String> split(String text,int limit) {
        if(text.length()<=limit) return List.of(text); List<String> result=new ArrayList<>();
        while(text.length()>limit) { int cut=text.lastIndexOf('\n',limit); if(cut<limit/2) cut=limit; result.add(text.substring(0,cut)); text=text.substring(cut).stripLeading(); }
        if(!text.isEmpty()) result.add(text); return List.copyOf(result);
    }
    static long loadOffset(Path path) { try { return Long.parseLong(Files.readString(path).trim()); } catch(Exception ignored) { return 0; } }
    static void saveOffset(Path path,long offset) throws IOException {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp"); Files.writeString(temporary,Long.toString(offset),StandardCharsets.UTF_8);
        try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
    }
    String safe(Throwable error) {
        String message=error.getMessage()==null ? error.getClass().getSimpleName() : error.getMessage();
        message=message.replace(config.token(),"<скрытый токен>");
        if(!config.proxy().password().isBlank()) message=message.replace(config.proxy().password(),"<скрытый пароль>");
        return message;
    }
    @Override public void close() {
        client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
        if(socksAuthenticator!=null && java.net.Authenticator.getDefault()==socksAuthenticator)
            java.net.Authenticator.setDefault(previousAuthenticator);
    }
}
