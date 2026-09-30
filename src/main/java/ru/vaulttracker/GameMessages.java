package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/** VaultTracker player messages styled with Flexity's read-only UI API. */
final class GameMessages {
    enum Tone { INFO, SUCCESS, WARNING, ERROR }
    private static final TextColor ACCENT=TextColor.color(0x10B981), MUTED=TextColor.color(0x94A3B8);
    private static final TextColor TEXT=TextColor.color(0xE2E8F0), DANGER=TextColor.color(0xF87171), WARNING=TextColor.color(0xFBBF24);
    private static volatile Adapter adapter;
    private static volatile boolean checked;

    static Component format(String text,Tone tone) {return format(Component.text(text),tone);}
    static Component format(Component body,Tone tone) {
        Adapter current=adapter();
        if(current!=null) try {
            Component prefix=(Component)current.message.invoke(current.instance,"",tone==Tone.ERROR);
            TextColor color=tone==Tone.SUCCESS?(TextColor)current.accent.invoke(current.instance)
                    :tone==Tone.WARNING?(TextColor)current.warning.invoke(current.instance)
                    :tone==Tone.ERROR?(TextColor)current.danger.invoke(current.instance)
                    :(TextColor)current.text.invoke(current.instance);
            return prefix.append(body.colorIfAbsent(color));
        } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* Flexity API changed; use the matching local palette. */ }
        TextColor color=tone==Tone.ERROR?DANGER:ACCENT;
        TextColor bodyColor=tone==Tone.SUCCESS?ACCENT:tone==Tone.WARNING?WARNING:tone==Tone.ERROR?DANGER:TEXT;
        return Component.text("FLEXITY",color).append(Component.text(" › ",MUTED)).append(body.colorIfAbsent(bodyColor));
    }
    static void send(CommandSender sender,String text,Tone tone) {
        if(sender instanceof Player) sender.sendMessage(format(text,tone));
        else sender.sendMessage(Component.text("[VaultTracker] "+text));
    }
    static void send(CommandSender sender,Component body,Tone tone) {
        sender.sendMessage(sender instanceof Player?format(body,tone):body);
    }
    private static Adapter adapter() {
        if(checked) return adapter;
        synchronized(GameMessages.class) {
            if(checked) return adapter;
            try {
                Plugin flexity=Bukkit.getPluginManager().getPlugin("flexity");
                if(flexity!=null && flexity.isEnabled()) {
                    Class<?> type=Class.forName("org.examples.flexity.ui.Ui",false,flexity.getClass().getClassLoader());
                    adapter=new Adapter(type.getField("INSTANCE").get(null),type.getMethod("message",String.class,boolean.class),
                            type.getMethod("getText"),type.getMethod("getAccent"),type.getMethod("getDanger"),type.getMethod("getWarning"));
                }
            } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* Optional integration. */ }
            checked=true;
            return adapter;
        }
    }
    private record Adapter(Object instance,Method message,Method text,Method accent,Method danger,Method warning) {}
    private GameMessages() {}
}
