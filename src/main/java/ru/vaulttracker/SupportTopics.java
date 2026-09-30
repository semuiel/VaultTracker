package ru.vaulttracker;

import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;

/** Support topics in the existing telegramchat.yml admin group. Zero means not configured. */
record SupportTopics(long chatId,int complaints,int suggestions,int bugs,int unbanRequests) {
    SupportTopics(long chatId,int complaints,int suggestions,int bugs) {this(chatId,complaints,suggestions,bugs,0);}
    static SupportTopics load(Path folder) {
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(folder.resolve("telegramchat.yml").toFile());
        return new SupportTopics(yaml.getLong("reports.chatId",0),topic(yaml,"support.complaintsTopicId"),
                topic(yaml,"support.suggestionsTopicId"),topic(yaml,"support.bugsTopicId"),
                topic(yaml,"support.unbanRequestsTopicId"));
    }
    private static int topic(YamlConfiguration yaml,String key) {
        long value=yaml.getLong(key,0);
        if(value<0 || value>Integer.MAX_VALUE) throw new IllegalArgumentException("telegramchat.yml: "+key+" должен быть от 0 до 2147483647");
        return (int)value;
    }
    int topic(SupportTicket.Type type) {
        return switch(type) {case COMPLAINT -> complaints;case SUGGESTION -> suggestions;case BUG -> bugs;case UNBAN -> unbanRequests;};
    }
    boolean configured(SupportTicket.Type type) {return chatId<0 && topic(type)>0;}
}
