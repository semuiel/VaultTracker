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
    private static final class ApiError extends IOException {
        final int code;
        ApiError(int code,String message) {super(message);this.code=code;}
    }
    static boolean permanentFailure(Throwable failure) {return failure instanceof ApiError error && error.code>=400 && error.code<500 && error.code!=429;}
    record Reply(int messageId,long userId,String name,String text,boolean bot) {}
    record Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,
                    String callbackId,String callbackData,boolean privateChat,String profile,boolean senderBot,boolean media,String displayName,Reply reply,boolean anonymous) {
        Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,String callbackId,String callbackData,boolean privateChat,String profile,boolean senderBot,boolean media,String displayName) {this(updateId,chatId,topicId,userId,messageId,text,callbackId,callbackData,privateChat,profile,senderBot,media,displayName,null,false);}
        Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,String callbackId,String callbackData,boolean privateChat,String profile,boolean senderBot,boolean media) {this(updateId,chatId,topicId,userId,messageId,text,callbackId,callbackData,privateChat,profile,senderBot,media,profile);}
        Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,String callbackId,String callbackData,boolean privateChat,String profile) {this(updateId,chatId,topicId,userId,messageId,text,callbackId,callbackData,privateChat,profile,false,false);}
        Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,String callbackId,String callbackData,boolean privateChat) {this(updateId,chatId,topicId,userId,messageId,text,callbackId,callbackData,privateChat,"");}
        Incoming(long updateId,long chatId,int topicId,long userId,int messageId,String text,
                 String callbackId,String callbackData) {
            this(updateId,chatId,topicId,userId,messageId,text,callbackId,callbackData,false);
        }
        boolean callback() { return callbackId!=null; }
    }
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
    private final TelegramConfig config;
    private final String baseUrl;
    private final Gson gson=new Gson();
    private final List<OkHttpClient> clients;
    private final List<TelegramConfig.Proxy> proxies;
    private final java.util.concurrent.atomic.AtomicInteger route=new java.util.concurrent.atomic.AtomicInteger();

    TelegramApi(TelegramConfig config) {
        this.config=config; this.baseUrl=config.botApiUrl()+"/bot"+config.token()+"/";
        try {proxies=TelegramProxies.load(config);} catch(IOException e) {throw new java.io.UncheckedIOException(e);}
        clients=proxies.stream().map(this::client).toList();
    }
    private OkHttpClient client(TelegramConfig.Proxy proxy) {
        OkHttpClient.Builder builder=new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15)).writeTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(config.pollTimeoutSeconds()+35L))
                .readTimeout(Duration.ofSeconds(config.pollTimeoutSeconds()+20L));
        builder.proxy(java.net.Proxy.NO_PROXY);
        if(proxy.enabled()) {
            if(proxy.type()==TelegramConfig.ProxyType.SOCKS5) {
                // Do not configure OkHttp SOCKS proxy: it uses the JVM global client.
                // This factory performs the complete SOCKS5 handshake per socket.
                builder.proxy(java.net.Proxy.NO_PROXY)
                        .socketFactory(new Socks5SocketFactory(proxy))
                        .dns(host->List.of(InetAddress.getByAddress(host,new byte[]{0,0,0,1})));
            } else {
                builder.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new InetSocketAddress(proxy.host(),proxy.port())));
                if(proxy.authenticated())
                    builder.proxyAuthenticator((route,response) -> response.request().header("Proxy-Authorization")!=null ? null : response.request().newBuilder()
                            .header("Proxy-Authorization",Credentials.basic(proxy.username(),proxy.password())).build());
            }
        }
        return builder.retryOnConnectionFailure(false).build();
    }
    String verify() throws IOException { return call("getMe",new JsonObject()).getAsJsonObject("result").get("username").getAsString(); }
    void registerCommands() throws IOException {
        JsonArray commands=new JsonArray();
        commands.add(command("item","Поиск ресурсов и игроков"));
        JsonObject body=new JsonObject(); body.add("commands",commands); call("setMyCommands",body);
        JsonArray privateCommands=new JsonArray();
        privateCommands.add(command("menu","Открыть меню поиска"));
        privateCommands.add(command("search","Поиск игрока или предмета"));
        privateCommands.add(command("item","Поиск ресурсов командой"));
        privateCommands.add(command("cancel","Отменить ввод для поиска"));
        privateCommands.add(command("help","Помощь"));
        JsonObject scope=new JsonObject(); scope.addProperty("type","all_private_chats");
        body.add("scope",scope); body.add("commands",privateCommands); call("setMyCommands",body);
    }
    private static JsonObject command(String name,String description) {
        JsonObject value=new JsonObject(); value.addProperty("command",name); value.addProperty("description",description); return value;
    }
    void registerChatCommands(boolean catalogue) throws IOException {
        registerChatCommands(catalogue,false);
    }
    void registerChatCommands(boolean catalogue,boolean reports) throws IOException {
        JsonArray commands=new JsonArray();if(catalogue) commands.add(command("item","Поиск ресурсов и игроков"));commands.add(command("list","Игроки онлайн и их измерения"));
        if(reports) commands.add(command("report","Пожаловаться ответом на сообщение"));
        JsonObject body=new JsonObject(),scope=new JsonObject();scope.addProperty("type","all_group_chats");body.add("scope",scope);body.add("commands",commands);call("setMyCommands",body);
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
                    callback.has("data") ? callback.get("data").getAsString() : null,isPrivate(message),profile(callback));
        }
        return new Incoming(id,0,0,0,0,null,null,null);
    }
    private static Incoming incomingMessage(long updateId,JsonObject message) {
        return new Incoming(updateId,message.getAsJsonObject("chat").get("id").getAsLong(),
                message.has("message_thread_id") ? message.get("message_thread_id").getAsInt() : 0,
                userId(message),message.get("message_id").getAsInt(),messageText(message),
                null,null,isPrivate(message),profile(message),message.has("from") && message.getAsJsonObject("from").has("is_bot") && message.getAsJsonObject("from").get("is_bot").getAsBoolean(),!message.has("text"),displayName(message),reply(message),message.has("sender_chat"));
    }
    private static Reply reply(JsonObject message) {
        if(!message.has("reply_to_message")) return null;
        var value=message.getAsJsonObject("reply_to_message");
        if(value.has("sender_chat")||!value.has("from")) return null;
        var from=value.getAsJsonObject("from");
        return new Reply(value.get("message_id").getAsInt(),userId(value),displayName(value),Objects.toString(messageText(value),"[без текста]"),from.has("is_bot")&&from.get("is_bot").getAsBoolean());
    }
    private static String messageText(JsonObject message) {
        if(message.has("text")) return message.get("text").getAsString();
        String label=null;
        for(String type:List.of("photo","video","animation","document","audio","voice","video_note","sticker","poll")) if(message.has(type)) {label=switch(type){case "photo"->"[Фото]";case "video"->"[Видео]";case "animation"->"[GIF]";case "document"->"[Документ]";case "audio"->"[Аудио]";case "voice"->"[Голосовое сообщение]";case "video_note"->"[Видеосообщение]";case "sticker"->"[Стикер]";default->"[Опрос]";};break;}
        return label==null?null:label+(message.has("caption")?" "+message.get("caption").getAsString():"");
    }
    private static String displayName(JsonObject object) {
        if(!object.has("from")) return "";JsonObject from=object.getAsJsonObject("from");List<String> parts=new ArrayList<>();
        for(String key:List.of("first_name","last_name")) if(from.has(key)) parts.add(from.get(key).getAsString());
        return String.join(" ",parts);
    }
    private static String profile(JsonObject object) {
        if(!object.has("from")) return "";JsonObject from=object.getAsJsonObject("from");List<String> parts=new ArrayList<>();
        for(String key:List.of("first_name","last_name","username")) if(from.has(key)) parts.add((key.equals("username")?"@":"")+from.get(key).getAsString());
        return String.join(" ",parts);
    }
    private static boolean isPrivate(JsonObject message) {
        JsonObject chat=message.getAsJsonObject("chat");
        return chat!=null && chat.has("type") && "private".equals(chat.get("type").getAsString());
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
    int sendHtml(long chatId,int topicId,String html,boolean silent) throws IOException {
        return sendHtml(chatId,topicId,html,silent,List.of());
    }
    int sendHtml(long chatId,int topicId,String html,boolean silent,List<TelegramCommands.Button> buttons) throws IOException {
        JsonObject body=new JsonObject();body.addProperty("chat_id",chatId);if(topicId>0) body.addProperty("message_thread_id",topicId);
        body.addProperty("text",html);body.addProperty("parse_mode","HTML");body.addProperty("disable_notification",silent);body.addProperty("disable_web_page_preview",true);
        addKeyboard(body,buttons);
        try {return call("sendMessage",body).getAsJsonObject("result").get("message_id").getAsInt();} catch(ApiError e) {
            String plainEmoji=TelegramChatFormat.withoutCustomEmoji(html);
            if(plainEmoji.equals(html) || e.code!=400) throw e;
            body.addProperty("text",plainEmoji);return call("sendMessage",body).getAsJsonObject("result").get("message_id").getAsInt();
        }
    }
    void editHtml(long chatId,int messageId,String html,List<TelegramCommands.Button> buttons) throws IOException {
        JsonObject body=new JsonObject();body.addProperty("chat_id",chatId);body.addProperty("message_id",messageId);
        body.addProperty("text",html);body.addProperty("parse_mode","HTML");body.addProperty("disable_web_page_preview",true);addKeyboard(body,buttons);
        try {call("editMessageText",body);} catch(ApiError e) {
            String plainEmoji=TelegramChatFormat.withoutCustomEmoji(html);
            if(plainEmoji.equals(html) || e.code!=400) {if(!benignCallbackError(e)) throw e;return;}
            body.addProperty("text",plainEmoji);try {call("editMessageText",body);} catch(IOException retry) {if(!benignCallbackError(retry)) throw retry;}
        }
    }
    void edit(long chatId,int messageId,TelegramCommands.View view) throws IOException {
        JsonObject body=messageBody(chatId,view); body.addProperty("message_id",messageId);
        try {call("editMessageText",body);} catch(IOException e) {if(!benignCallbackError(e)) throw e;}
    }
    void answerCallback(String callbackId,String text,boolean alert) throws IOException {
        JsonObject body=new JsonObject(); body.addProperty("callback_query_id",callbackId);
        if(text!=null && !text.isBlank()) body.addProperty("text",text);
        if(alert) body.addProperty("show_alert",true);
        try {call("answerCallbackQuery",body);} catch(IOException e) {if(!benignCallbackError(e)) throw e;}
    }
    static boolean benignCallbackError(Throwable error) {
        String text=String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return text.contains("message is not modified") || text.contains("query is too old") || text.contains("query id is invalid");
    }
    void delete(long chatId,int messageId) throws IOException {
        JsonObject body=new JsonObject(); body.addProperty("chat_id",chatId); body.addProperty("message_id",messageId);
        call("deleteMessage",body);
    }
    String memberStatus(long chatId,long userId) throws IOException {
        JsonObject body=new JsonObject();body.addProperty("chat_id",chatId);body.addProperty("user_id",userId);
        return call("getChatMember",body).getAsJsonObject("result").get("status").getAsString();
    }
    void banMember(long chatId,long userId,long until) throws IOException {var body=memberBody(chatId,userId);body.addProperty("until_date",until);body.addProperty("revoke_messages",false);call("banChatMember",body);}
    void unbanMember(long chatId,long userId) throws IOException {var body=memberBody(chatId,userId);body.addProperty("only_if_banned",true);call("unbanChatMember",body);}
    void muteMember(long chatId,long userId,long until,boolean mute) throws IOException {
        var body=memberBody(chatId,userId);JsonObject permissions;
        if(mute) {permissions=new JsonObject();for(String key:List.of("can_send_messages","can_send_audios","can_send_documents","can_send_photos","can_send_videos","can_send_video_notes","can_send_voice_notes","can_send_polls","can_send_other_messages","can_add_web_page_previews")) permissions.addProperty(key,false);}
        else {var chat=call("getChat",chatIdBody(chatId)).getAsJsonObject("result");if(!chat.has("permissions")) throw new IOException("Не удалось получить стандартные разрешения группы");permissions=chat.getAsJsonObject("permissions");}
        body.add("permissions",permissions);body.addProperty("use_independent_chat_permissions",true);body.addProperty("until_date",until);call("restrictChatMember",body);
    }
    private static JsonObject memberBody(long chatId,long userId) {var body=chatIdBody(chatId);body.addProperty("user_id",userId);return body;}
    String chatTitle(long chatId) throws IOException {
        JsonObject chat=call("getChat",chatIdBody(chatId)).getAsJsonObject("result");
        for(String key:List.of("title","username","first_name")) if(chat.has(key) && !chat.get(key).getAsString().isBlank()) return (key.equals("username")?"@":"")+chat.get(key).getAsString();
        return "Группа Telegram";
    }
    String userLabel(long userId) throws IOException {
        JsonObject user=call("getChat",chatIdBody(userId)).getAsJsonObject("result");StringBuilder label=new StringBuilder();
        if(user.has("first_name")) label.append(user.get("first_name").getAsString());if(user.has("last_name")) label.append(label.isEmpty()?"":" ").append(user.get("last_name").getAsString());
        if(user.has("username")) label.append(label.isEmpty()?"":" · ").append('@').append(user.get("username").getAsString());
        return label.isEmpty()?"Telegram-пользователь":label.toString();
    }
    private static JsonObject chatIdBody(long chatId) {JsonObject body=new JsonObject();body.addProperty("chat_id",chatId);return body;}
    void setMemberTag(long chatId,long userId,String tag) throws IOException {
        JsonObject body=new JsonObject();body.addProperty("chat_id",chatId);body.addProperty("user_id",userId);
        if(tag!=null && !tag.isBlank()) body.addProperty("tag",tag);
        call("setChatMemberTag",body);
    }
    void setAdministratorTitle(long chatId,long userId,String title) throws IOException {
        JsonObject body=new JsonObject();body.addProperty("chat_id",chatId);body.addProperty("user_id",userId);
        body.addProperty("custom_title",title==null ? "" : title);
        call("setChatAdministratorCustomTitle",body);
    }
    static JsonObject messageBody(long chatId,TelegramCommands.View view) {
        JsonObject body=new JsonObject(); body.addProperty("chat_id",chatId); body.addProperty("text",view.text());
        body.addProperty("disable_web_page_preview",true);
        addKeyboard(body,view.buttons());
        return body;
    }
    private static void addKeyboard(JsonObject body,List<TelegramCommands.Button> buttons) {
        JsonArray keyboard=new JsonArray();
        if(!buttons.isEmpty()) {
            JsonArray row=null; int rowNumber=-1;
            for(var button:buttons) {
                if(row==null || button.row()!=rowNumber) {
                    row=new JsonArray(); keyboard.add(row); rowNumber=button.row();
                }
                JsonObject value=new JsonObject(); value.addProperty("text",button.text()); value.addProperty(button.url()==null?"callback_data":"url",button.url()==null?button.data():button.url()); row.add(value);
            }
        }
        JsonObject markup=new JsonObject(); markup.add("inline_keyboard",keyboard); body.add("reply_markup",markup);
    }
    private JsonObject call(String method,JsonObject body) throws IOException {
        Request request=new Request.Builder().url(baseUrl+method).post(RequestBody.create(gson.toJson(body),JSON)).build();
        int current=route.get();OkHttpClient client=clients.get(current);
        try(Response response=client.newCall(request).execute()) {
            String raw=response.body()==null ? "" : response.body().string(); JsonObject json;
            try { json=JsonParser.parseString(raw).getAsJsonObject(); }
            catch(RuntimeException e) { throw new IOException("Telegram вернул некорректный ответ (HTTP "+response.code()+")"); }
            if(!response.isSuccessful() || !json.has("ok") || !json.get("ok").getAsBoolean())
                throw new ApiError(json.has("error_code")?json.get("error_code").getAsInt():response.code(),"Telegram API: "+(json.has("description") ? json.get("description").getAsString() : "HTTP "+response.code()));
            return json;
        } catch(IOException failure) {
            if(failure instanceof ApiError) throw failure;
            // Do not replay a possibly accepted send/action. The next request uses the next route.
            route.compareAndSet(current,(current+1)%clients.size());
            client.connectionPool().evictAll();
            throw failure;
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
        for(var proxy:proxies) if(!proxy.password().isBlank()) message=message.replace(proxy.password(),"<скрытый пароль>");
        return message;
    }
    @Override public void close() {
        for(OkHttpClient client:clients) {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
        }
    }
}
