package ru.vaulttracker;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.Plugin;

/** Optional adapter for the supplied Flexity version. No player impersonation or chat events. */
final class LinkedChatIdentity {
    record Style(String prefix,String color) {}
    private final Object formatter,settings,database;
    private final Method format,connection;
    private final Map<?,?> cache;
    private final FlexityPremiumStyle premiumStyle;
    LinkedChatIdentity(Plugin flexity) throws ReflectiveOperationException {
        Object bootstrap=field(flexity,"bootstrap");
        formatter=field(bootstrap,"chatFormatter");Object services=field(bootstrap,"serviceRegistry");
        settings=services.getClass().getMethod("getSettingsService").invoke(services);
        database=services.getClass().getMethod("getDatabaseService").invoke(services);
        cache=(Map<?,?>)field(settings,"cache");
        Method legacy=null;FlexityPremiumStyle modern=null;
        try {legacy=formatter.getClass().getMethod("format",boolean.class,String.class,Component.class,String.class);}
        catch(NoSuchMethodException updated) {modern=new FlexityPremiumStyle(flexity,formatter,settings);}
        format=legacy;premiumStyle=modern;
        connection=database.getClass().getMethod("getConnection");
    }
    private static Object field(Object instance,String name) throws ReflectiveOperationException {
        Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    Style style(UUID uuid) throws Exception {
        Object saved=cache.get(uuid);
        if(saved!=null) return new Style((String)saved.getClass().getMethod("getCustomPrefix").invoke(saved),(String)saved.getClass().getMethod("getCustomNickColor").invoke(saved));
        // Read through Flexity's pool on the bridge worker, including players offline since startup.
        try(Connection db=(Connection)connection.invoke(database);PreparedStatement query=db.prepareStatement("SELECT custom_prefix, nick_color FROM flexity_settings WHERE uuid = ?")) {
            query.setString(1,uuid.toString());query.setQueryTimeout(3);
            try(ResultSet row=query.executeQuery()) {return row.next()?new Style(row.getString(1),row.getString(2)):new Style(null,null);}
        }
    }
    Component render(GuardService.Account account,String text) throws Exception {
        return render(account,text,true);
    }
    Component render(GuardService.Account account,String text,boolean showTag) throws Exception {
        if(premiumStyle!=null) return tag(showTag).append(premiumStyle.render(account,text));
        Style style=style(account.uuid());
        // Minecraft account names are inserted into Flexity's trusted template, never Telegram names.
        if(!account.name().matches("[A-Za-z0-9_.-]{1,32}")) return fallback(account.name(),text,showTag);
        Component body=(Component)format.invoke(formatter,true,account.name(),Component.text(text,NamedTextColor.WHITE),style.color());
        Component result=tag(showTag);
        if(style.prefix()!=null&&!style.prefix().isBlank()) result=result.append(MiniMessage.miniMessage().deserialize(style.prefix())).append(Component.space());
        return result.append(body);
    }
    static Component fallback(String name,String text) {
        return fallback(name,text,true);
    }
    static Component tag(boolean show) {return show?Component.text("[TG] ",NamedTextColor.AQUA):Component.empty();}
    static Component fallback(String name,String text,boolean showTag) {
        return tag(showTag).append(Component.text(name,NamedTextColor.WHITE))
                .append(Component.text(": ",NamedTextColor.GRAY)).append(Component.text(text,NamedTextColor.WHITE));
    }
}
