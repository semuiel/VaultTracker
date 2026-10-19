package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Topic-local deletion only. Never changes Telegram chat permissions. */
final class TelegramTopicModeration implements AutoCloseable {
    static final int MAX_MINUTES=10_080;
    private record Key(long chat,int topic,long user) {}
    private final TelegramApi api;
    private final TelegramConfig config;
    private final TelegramModerationRepository repository;
    private final TelegramListExpiry expiry;
    private final LongSupplier clock;
    private final Map<Key,Long> notices=new HashMap<>();

    TelegramTopicModeration(TelegramApi api,TelegramConfig config,Path folder,Logger log) throws Exception {
        this(api,config,new TelegramModerationRepository(folder),new TelegramListExpiry(folder.resolve(config.offsetFile().getFileName()+"-offtopic-expiry.properties"),api,log),System::currentTimeMillis);
        expiry.start();
    }
    TelegramTopicModeration(TelegramApi api,TelegramConfig config,TelegramModerationRepository repository,TelegramListExpiry expiry,LongSupplier clock) {
        this.api=api;this.config=config;this.repository=repository;this.expiry=expiry;this.clock=clock;
    }
    boolean consume(TelegramApi.Incoming update,String username) throws Exception {
        if(update.privateChat()||update.callback()||update.chatId()>=0||update.senderBot()) return false;
        String text=update.text()==null?"":update.text().strip();
        String token=text.split("\\s+",2)[0];
        String command=token.split("@",2)[0].toLowerCase(Locale.ROOT);
        boolean addressed=!token.contains("@")||token.substring(token.indexOf('@')+1).equalsIgnoreCase(username);
        boolean action=addressed&&(command.equals("/offtop")||command.equals("/unofftop"));
        if(!chatAllowed(update.chatId())) return false;
        // Chat allowlist applies to commands too, but /unofftop may originate in another topic of that chat.
        long now=clock.getAsLong();
        if(!update.anonymous()&&update.userId()>0&&update.topicId()>0) {
            long until=repository.expiry(update.chatId(),update.topicId(),update.userId());
            if(until>now) {remove(update,now);return true;}
        }
        if(!action) return false;
        try {return command(update,command,text,now);}
        finally {
            try {api.delete(update.chatId(),update.messageId());}
            catch(Exception failed) {expiry.track(update.chatId(),update.messageId(),clock.getAsLong());}
        }
    }
    private boolean command(TelegramApi.Incoming update,String command,String text,long now) throws Exception {
        if(update.anonymous()||update.userId()<=0) {reply(update,"Команда недоступна от имени канала или анонимного администратора.");return true;}
        boolean administrator;
        try {administrator=api.chatAdministrator(update.chatId(),update.userId());}
        catch(Exception failure) {reply(update,"Не удалось проверить права Telegram. Ограничение не изменено.");return true;}
        if(!administrator) {reply(update,"Эта команда доступна только администраторам Telegram этого чата.");return true;}
        var target=update.reply();
        if(target==null||target.userId()<=0||target.bot()) {reply(update,"Ответьте на сообщение пользователя; ботов и анонимных отправителей ограничить нельзя.");return true;}
        String[] parts=text.split("\\s+");
        if(command.equals("/unofftop")) {
            if(parts.length!=1) {reply(update,"Используйте /unofftop ответом на сообщение.");return true;}
            int removed=repository.release(update.chatId(),target.userId(),now);
            reply(update,removed>0?"Ограничение оффтопа снято во всех темах этого чата.":"У пользователя нет активных ограничений в этом чате.");return true;
        }
        if(update.topicId()<=0) {reply(update,"/offtop работает только внутри темы форума.");return true;}
        int minutes;
        try {if(parts.length!=2) throw new IllegalArgumentException();minutes=Integer.parseInt(parts[1]);if(minutes<1||minutes>MAX_MINUTES) throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid) {reply(update,"Ответьте на сообщение: /offtop <минуты>, от 1 до "+MAX_MINUTES+".");return true;}
        try {
            if(!api.canDeleteMessages(update.chatId())) {reply(update,"У бота нет права удалять сообщения. Ограничение не включено.");return true;}
        } catch(Exception failure) {reply(update,"Не удалось проверить право бота удалять сообщения. Ограничение не включено.");return true;}
        repository.restrict(update.chatId(),update.topicId(),target.userId(),now+minutes*60_000L,update.userId());
        reply(update,"Оффтоп ограничен для "+escape(target.name())+" в этой теме на "+minutes+" мин. В других темах можно писать."+floodLink());
        return true;
    }
    private boolean chatAllowed(long chat) {
        if(!config.chats().isEmpty()) return config.chats().stream().anyMatch(value->value.chatId()==chat)||(config.news()!=null&&config.news().chatId()==chat);
        return config.allowedChatIds().isEmpty()||config.allowedChatIds().contains(chat)||(config.news()!=null&&config.news().chatId()==chat);
    }
    private void remove(TelegramApi.Incoming update,long now) throws Exception {
        try {api.delete(update.chatId(),update.messageId());}
        catch(Exception failure) {
            if(notice(update,now)) {
                long actor=repository.actor(update.chatId(),update.topicId(),update.userId());
                if(actor>0) try {api.send(actor,0,new TelegramCommands.View("Не удалось удалить оффтоп в теме "+update.topicId()+". Проверьте права бота и связь с Telegram; ограничение сохранено."));}catch(Exception ignored) {}
                reply(update,"Бот не смог удалить оффтоп: проверьте право удаления сообщений и связь с Telegram.");
            }
            return;
        }
    }
    private String floodLink() {
        var flood=config.moderation();
        return flood!=null&&flood.chatId()!=0&&flood.topicId()>0?" <a href=\"https://t.me/c/"+Long.toString(-flood.chatId()).substring(3)+"/"+flood.topicId()+"\">Открыть флуд ↗</a>":"";
    }
    private synchronized boolean notice(TelegramApi.Incoming update,long now) {
        notices.entrySet().removeIf(value->value.getValue()<=now);
        Key key=new Key(update.chatId(),update.topicId(),update.userId());
        if(notices.containsKey(key)) return false;
        if(notices.size()>=5000) return false;
        notices.put(key,now+60_000);return true;
    }
    private void reply(TelegramApi.Incoming update,String html) throws Exception {
        int id=api.sendHtml(update.chatId(),update.topicId(),html,true);
        expiry.track(update.chatId(),id,clock.getAsLong()+60_000);
    }
    private static String escape(String text) {return text==null?"":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    @Override public void close() {expiry.close();}
}
