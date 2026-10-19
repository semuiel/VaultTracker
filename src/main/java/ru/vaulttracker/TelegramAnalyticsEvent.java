package ru.vaulttracker;

import com.google.gson.*;
import java.util.*;
import java.util.function.LongFunction;

/** Transient raw Telegram JSON is reduced before entering the bounded queue. No text or user IDs survive. */
record TelegramAnalyticsEvent(long chat,int message,int topic,long at,long revision,String author,String type,
                              boolean reply,boolean forwarded,boolean media,int textLength,String chatTitle,String chatType,String topicTitle) {
    static final List<String> MEDIA=List.of("photo","video","animation","document","audio","voice","video_note","sticker","poll","contact","location","venue","dice","game","story","paid_media","live_photo");
    static final List<String> SERVICES=List.of("new_chat_members","left_chat_member","new_chat_title","new_chat_photo","delete_chat_photo","group_chat_created","supergroup_chat_created","channel_chat_created","message_auto_delete_timer_changed","migrate_to_chat_id","migrate_from_chat_id","pinned_message","forum_topic_created","forum_topic_edited","forum_topic_closed","forum_topic_reopened","general_forum_topic_hidden","general_forum_topic_unhidden","video_chat_started","video_chat_ended","video_chat_scheduled","video_chat_participants_invited","boost_added","giveaway_created","giveaway_completed","successful_payment","refunded_payment","users_shared","chat_shared","write_access_allowed");
    static TelegramAnalyticsEvent parse(JsonObject update,Set<Long> tracked,LongFunction<String> pseudonym) {
        JsonObject m=null;
        for(String kind:List.of("message","edited_message","channel_post","edited_channel_post"))if(update.has(kind)){m=update.getAsJsonObject(kind);break;}
        if(m==null||!m.has("chat"))return null;
        JsonObject chat=m.getAsJsonObject("chat");long id=number(chat,"id");String chatType=text(chat,"type");
        if(!tracked.contains(id)||!Set.of("group","supergroup","channel").contains(chatType)||!m.has("message_id")||!m.has("date"))return null;
        String type=m.has("text")?"text":"other";boolean media=false;
        for(String t:MEDIA)if(m.has(t)){type=t;media=!t.equals("poll");break;}
        for(String t:SERVICES)if(m.has(t)){type="service";media=false;break;}
        String author=null;
        if(!m.has("sender_chat")&&m.has("from")) {var from=m.getAsJsonObject("from");if(!bool(from,"is_bot")&&number(from,"id")>0)author=pseudonym.apply(number(from,"id"));}
        String topicTitle="";for(String t:List.of("forum_topic_created","forum_topic_edited"))if(m.has(t))topicTitle=text(m.getAsJsonObject(t),"name");
        int length=text(m,"text").length()+text(m,"caption").length();
        boolean reply=m.has("reply_to_message")&&SERVICES.stream().noneMatch(m.getAsJsonObject("reply_to_message")::has);
        return new TelegramAnalyticsEvent(id,(int)number(m,"message_id"),(int)number(m,"message_thread_id"),number(m,"date"),number(m,"edit_date"),author,type,
                reply,m.has("forward_origin")||m.has("forward_date"),media,length,limited(text(chat,"title")),chatType,limited(topicTitle));
    }
    record TopicName(long chat,int topic,String title,boolean renamed) {}
    static TopicName topicName(JsonObject update,Set<Long> tracked) {
        JsonObject message=null;
        for(String kind:List.of("message","edited_message","channel_post","edited_channel_post"))
            if(update.has(kind)){message=update.getAsJsonObject(kind);break;}
        if(message==null||!message.has("chat"))return null;
        var chat=message.getAsJsonObject("chat");long id=number(chat,"id"),thread=number(message,"message_thread_id");
        if(!tracked.contains(id)||!text(chat,"type").equals("supergroup")||thread<0||thread>Integer.MAX_VALUE)return null;
        JsonObject source=message;boolean inherited=false;
        if(!source.has("forum_topic_created")&&!source.has("forum_topic_edited")) {
            if(thread==0||!source.has("reply_to_message"))return null;
            source=source.getAsJsonObject("reply_to_message");inherited=true;
            if(number(source,"message_id")!=thread||!source.has("chat")||number(source.getAsJsonObject("chat"),"id")!=id)return null;
        }
        for(String kind:List.of("forum_topic_edited","forum_topic_created"))if(source.has(kind)) {
            String title=limited(text(source.getAsJsonObject(kind),"name").strip());
            if(!title.isBlank())return new TopicName(id,(int)thread,title,!inherited&&kind.equals("forum_topic_edited"));
        }
        return null;
    }
    static boolean member(JsonObject m) {String s=text(m,"status");return Set.of("creator","administrator","member").contains(s)||(s.equals("restricted")&&bool(m,"is_member"));}
    static String limited(String text){return text.length()>200?text.substring(0,200):text;}
    static String text(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString():"";}
    static long number(JsonObject o,String key){return o.has(key)?o.get(key).getAsLong():0;}
    static boolean bool(JsonObject o,String key){return o.has(key)&&o.get(key).getAsBoolean();}
}
