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
    record Incoming(long updateId,long chatId,String text) {}
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
        commands.add(command("items","Показать ресурсы каталога"));
        commands.add(command("status","Состояние каталога"));
        commands.add(command("id","Показать ID чата"));
        commands.add(command("help","Помощь"));
        JsonObject body=new JsonObject(); body.add("commands",commands); call("setMyCommands",body);
    }
    private static JsonObject command(String name,String description) {
        JsonObject value=new JsonObject(); value.addProperty("command",name); value.addProperty("description",description); return value;
    }
    List<Incoming> updates(long offset) throws IOException {
        JsonObject body=new JsonObject(); body.addProperty("offset",offset); body.addProperty("timeout",config.pollTimeoutSeconds());
        body.addProperty("limit",50); JsonArray allowed=new JsonArray(); allowed.add("message"); body.add("allowed_updates",allowed);
        List<Incoming> result=new ArrayList<>();
        for(JsonElement element:call("getUpdates",body).getAsJsonArray("result")) {
            JsonObject update=element.getAsJsonObject(); long id=update.get("update_id").getAsLong();
            if(!update.has("message")) { result.add(new Incoming(id,0,null)); continue; }
            JsonObject message=update.getAsJsonObject("message");
            result.add(new Incoming(id,message.getAsJsonObject("chat").get("id").getAsLong(),
                    message.has("text") ? message.get("text").getAsString() : null));
        }
        return List.copyOf(result);
    }
    void send(long chatId,String text) throws IOException {
        for(String part:split(text,3900)) {
            JsonObject body=new JsonObject(); body.addProperty("chat_id",chatId); body.addProperty("text",part);
            body.addProperty("disable_web_page_preview",true); call("sendMessage",body);
        }
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
