package ru.vaulttracker;

import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

/** Uses Flexity's own persistent punishment service, including its live freeze cache. */
final class FlexityFreeze {
    private final Object service;
    private final Method freeze,unfreeze;
    FlexityFreeze(Plugin plugin) throws Exception {
        this(service(plugin));
    }
    FlexityFreeze(Object service) throws Exception {
        this.service=service;
        freeze=service.getClass().getMethod("freezePlayer",UUID.class,UUID.class);
        unfreeze=service.getClass().getMethod("unfreezePlayer",UUID.class,UUID.class);
    }
    private static Object service(Plugin plugin) throws Exception {
        Object registry=field(field(plugin,"bootstrap"),"serviceRegistry");
        return registry.getClass().getMethod("getPunishmentService").invoke(registry);
    }
    CompletableFuture<Boolean> change(UUID target,UUID actor,boolean enabled) throws Exception {
        Object result=(enabled?freeze:unfreeze).invoke(service,target,actor);
        if(!(result instanceof CompletableFuture<?> future)) throw new IllegalStateException("Неизвестный API заморозки Flexity");
        return future.thenApply(Boolean.TRUE::equals);
    }
    private static Object field(Object owner,String name) throws Exception {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
            catch(NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
