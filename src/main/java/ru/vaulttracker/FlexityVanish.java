package ru.vaulttracker;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Read-only access to Flexity vanish state without a compile-time dependency. */
class FlexityVanish {
    private final Object service;
    private final Method vanishedPlayers;

    FlexityVanish(Plugin flexity) throws Exception {
        Object bootstrap=field(flexity,"bootstrap");
        Object services=field(bootstrap,"serviceRegistry");
        service=services.getClass().getMethod("getVanishService").invoke(services);
        vanishedPlayers=service.getClass().getMethod("getVanishedPlayers");
    }

    @SuppressWarnings("unchecked")
    Set<UUID> snapshot() throws Exception {
        Object value=vanishedPlayers.invoke(service);
        if(!(value instanceof Set<?> set)) return Set.of();
        Set<UUID> result=new HashSet<>();
        for(Object item:set) if(item instanceof UUID uuid) result.add(uuid);
        return Set.copyOf(result);
    }

    private static Object field(Object owner,String name) throws Exception {
        Class<?> type=owner.getClass();
        while(type!=null) {
            try {Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
            catch(NoSuchFieldException ignored) {type=type.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }
}
