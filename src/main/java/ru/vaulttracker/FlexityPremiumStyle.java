package ru.vaulttracker;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.plugin.Plugin;

/** Read-only adapter: use Flexity's own visibility rules, colours and chat formatter. */
final class FlexityPremiumStyle {
    @FunctionalInterface interface Permissions {Predicate<String> read(UUID uuid) throws Exception;}
    private final Object formatter,styles,colors;
    private final Method format,read,paint;
    private final Permissions permissions;

    FlexityPremiumStyle(Plugin flexity,Object formatter,Object settings) throws ReflectiveOperationException {
        this(formatter,settings.getClass().getMethod("getStyles").invoke(settings),
                Class.forName("org.examples.flexity.style.StyleColors",true,formatter.getClass().getClassLoader()).getField("INSTANCE").get(null),
                uuid->permissions(flexity,uuid));
    }
    FlexityPremiumStyle(Object formatter,Object styles,Object colors,Permissions permissions) throws ReflectiveOperationException {
        this.formatter=formatter;this.styles=styles;this.colors=colors;this.permissions=permissions;
        format=formatter.getClass().getMethod("format",boolean.class,String.class,Component.class,String.class,String.class,TextColor.class);
        read=styles.getClass().getMethod("readAsync",UUID.class);
        paint=colors.getClass().getMethod("paint",Component.class,String.class);
    }
    Component render(GuardService.Account account,String text) throws Exception {
        // readAsync checks the current live cache, falling back to saved style for offline players.
        Object saved=((CompletableFuture<?>)read.invoke(styles,account.uuid())).get(5,TimeUnit.SECONDS);
        Predicate<String> allowed=permissions.read(account.uuid());
        Method visible=Arrays.stream(saved.getClass().getMethods()).filter(m->m.getName().equals("visible")&&m.getParameterCount()==1).findFirst().orElseThrow();
        Class<?> callbackType=visible.getParameterTypes()[0];
        // Use Flexity's Kotlin interface class: VaultTracker relocates its own Kotlin dependency.
        Object callback=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,method,args)->switch(method.getName()) {
            case "invoke" -> allowed.test((String)args[0]);
            case "toString" -> "VaultTracker style permissions";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object style=visible.invoke(saved,callback);Class<?> type=style.getClass();
        Component message=(Component)paint.invoke(colors,Component.text(text),(String)type.getMethod("getChat").invoke(style));
        // White is only the default. Selected colours and gradients are preserved.
        message=message.colorIfAbsent(NamedTextColor.WHITE);
        Component formatted=(Component)format.invoke(formatter,true,account.name(),message,
                (String)type.getMethod("getNick").invoke(style),(String)type.getMethod("getTitle").invoke(style),type.getMethod("themeColor").invoke(style));
        return formatted.colorIfAbsent(NamedTextColor.WHITE);
    }
    private static Predicate<String> permissions(Plugin plugin,UUID uuid) throws Exception {
        // Permission reads do not access location/entity state. Using the live Permissible
        // exactly matches the check performed by Flexity's own chat formatter.
        var player=plugin.getServer().getPlayer(uuid);
        if(player!=null&&player.isOnline()) return player::hasPermission;
        return OfflinePermissions.read(uuid);
    }
    private static final class OfflinePermissions {
        static Predicate<String> read(UUID uuid) throws Exception {
            try {
                var api=net.luckperms.api.LuckPermsProvider.get();
                var user=api.getUserManager().getUser(uuid);
                if(user==null) user=api.getUserManager().loadUser(uuid).get(5,TimeUnit.SECONDS);
                var data=user.getCachedData().getPermissionData(api.getContextManager().getQueryOptions(user).orElseGet(()->api.getContextManager().getStaticQueryOptions()));
                return permission->data.checkPermission(permission).asBoolean();
            } catch(IllegalStateException | LinkageError unavailable) {return permission->false;}
        }
    }
}
