package ru.vaulttracker;

import java.util.*;

/** Applies a linked Minecraft nickname as a Telegram member tag in every configured group. */
final class TelegramTagManager {
    record Result(String text,boolean complete,boolean anySuccess) {}
    private final TelegramConfig config;
    private final TelegramApi api;

    TelegramTagManager(TelegramConfig config,TelegramApi api) {this.config=config;this.api=api;}

    Result apply(long user,String nickname) {return change(user,clean(nickname),false);}
    Result reset(long user) {return change(user,"",true);}

    private Result change(long user,String tag,boolean reset) {
        Set<Long> chats=new LinkedHashSet<>();
        for(var chat:config.chats()) chats.add(chat.chatId());
        chats.addAll(config.allowedChatIds());
        if(chats.isEmpty()) return new Result("🏷 Telegram-тег\n\nВ telegram.yml не перечислены чаты. Добавьте группы в chats или allowedChatIds и выполните /vtrack reload.",false,false);
        List<String> lines=new ArrayList<>();int success=0;
        for(long chat:chats) {
            try {
                String status=api.memberStatus(chat,user);
                if("creator".equals(status)) throw new IllegalStateException("владелец группы меняет свой титул вручную");
                if("administrator".equals(status)) api.setAdministratorTitle(chat,user,tag);
                else if(Set.of("left","kicked").contains(status)) throw new IllegalStateException("игрок не состоит в этом чате");
                else api.setMemberTag(chat,user,tag);
                success++;lines.add("✅ "+chat);
            } catch(Exception e) {
                lines.add("❌ "+chat+" — "+shortError(api.safe(e)));
            }
        }
        String action=reset ? "Тег сброшен" : "Применён тег «"+tag+"»";
        String text="🏷 "+action+"\nУспешно: "+success+" из "+chats.size()+" чатов.\n\n"+String.join("\n",lines);
        if(success<chats.size()) text+="\n\nДля обычного игрока боту нужно право «Управление тегами». Тег администратора бот может менять только если сам назначил этого администратора.";
        return new Result(text,success==chats.size(),success>0);
    }

    private static String clean(String value) {
        String tag=value==null ? "" : value.trim();
        if(tag.length()>16) tag=tag.substring(0,16);
        return tag;
    }
    private static String shortError(String value) {
        String text=value==null ? "неизвестная ошибка" : value.replace('\n',' ').replace('\r',' ').trim();
        return text.length()>160 ? text.substring(0,157)+"..." : text;
    }
}
