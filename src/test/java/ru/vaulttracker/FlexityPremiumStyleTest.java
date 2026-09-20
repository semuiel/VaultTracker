package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import static org.junit.jupiter.api.Assertions.*;

class FlexityPremiumStyleTest {
    public interface Permission {boolean invoke(String name);}
    public record Style(String nick,String chat,String title) {
        public String getNick(){return nick;} public String getChat(){return chat;} public String getTitle(){return title;}
        public TextColor themeColor(){return NamedTextColor.GOLD;}
        public Style visible(Permission allowed){return new Style(allowed.invoke("flexity.nick")?nick:null,allowed.invoke("flexity.chat.color")?chat:null,allowed.invoke("flexity.style.title")?title:"");}
    }
    public static class Styles {
        Object current;int reads;
        public CompletableFuture<Object> readAsync(UUID uuid){reads++;return CompletableFuture.completedFuture(current);}
    }
    public static class Colors {
        public Component paint(Component message,String color){return color==null?message:message.color(TextColor.fromHexString(color));}
    }
    public static class Formatter {
        String nick,title;Component message;TextColor theme;
        public Component format(boolean global,String name,Component message,String nick,String title,TextColor theme){
            assertTrue(global);this.nick=nick;this.title=title;this.message=message;this.theme=theme;
            return Component.text("[G] "+name+" "+title+": ").append(message);
        }
    }
    @Test void currentStyleIsReadForEveryMessageAndRevokedPermissionsRemoveStyle() throws Exception {
        Styles styles=new Styles();Formatter formatter=new Formatter();Set<String> allowed=new HashSet<>(Set.of("flexity.nick","flexity.chat.color","flexity.style.title"));
        var adapter=new FlexityPremiumStyle(formatter,styles,new Colors(),uuid->allowed::contains);
        var account=new GuardService.Account(UUID.randomUUID(),"Semuiel",true);
        styles.current=new Style("#AA00AA","#FFAA00","star");String text="<click:run_command:'/op me'>literal</click>";
        adapter.render(account,text);assertEquals("#AA00AA",formatter.nick);assertEquals("star",formatter.title);assertEquals(NamedTextColor.GOLD,formatter.theme);
        assertEquals(Component.text(text,TextColor.fromHexString("#FFAA00")),formatter.message);assertNull(formatter.message.clickEvent());
        styles.current=new Style("#00AA00","#55FFFF","heart");adapter.render(account,"new");
        assertEquals("#00AA00",formatter.nick);assertEquals("heart",formatter.title);assertEquals(TextColor.fromHexString("#55FFFF"),formatter.message.color());assertEquals(2,styles.reads);
        allowed.clear();Component result=adapter.render(account,"expired");assertNull(formatter.nick);assertEquals("",formatter.title);assertEquals(NamedTextColor.WHITE,formatter.message.color());assertEquals(NamedTextColor.WHITE,result.color());
    }
    @Test void realFlexity161FormatterAndGradientMatchGamePipeline() throws Exception {
        var jar=java.nio.file.Path.of("work/flexity-current-inspect.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(jar),"Optional installed Flexity fixture");
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},getClass().getClassLoader())) {
            Object formatter=loader.loadClass("org.examples.flexity.chat.ChatFormatter").getConstructor().newInstance();
            Object colors=loader.loadClass("org.examples.flexity.style.StyleColors").getField("INSTANCE").get(null);
            Class<?> badges=loader.loadClass("org.examples.flexity.style.StyleBadges");
            String badge=(String)((Map<?,?>)badges.getMethod("getCatalog").invoke(badges.getField("INSTANCE").get(null))).keySet().iterator().next();
            Class<?> type=loader.loadClass("org.examples.flexity.style.PlayerStyle");
            Object saved=type.getConstructor(String.class,String.class,String.class,String.class,String.class,String.class,String.class)
                    .newInstance("gradient:#AA00AA:#FF55FF","#FFAA00",badge,"","emerald","classic","none");
            Styles styles=new Styles();styles.current=saved;
            var adapter=new FlexityPremiumStyle(formatter,styles,colors,uuid->permission->true);
            var account=new GuardService.Account(UUID.randomUUID(),"Semuiel",true);String literal="hello <red>world</red>";
            Component painted=(Component)colors.getClass().getMethod("paint",Component.class,String.class).invoke(colors,Component.text(literal),"#FFAA00");
            Component expected=(Component)formatter.getClass().getMethod("format",boolean.class,String.class,Component.class,String.class,String.class,TextColor.class)
                    .invoke(formatter,true,"Semuiel",painted.colorIfAbsent(NamedTextColor.WHITE),"gradient:#AA00AA:#FF55FF",badge,type.getMethod("themeColor").invoke(saved));
            Component actual=adapter.render(account,literal);
            // Adventure virtual gradient components use renderer identity in equals; compare the client payload.
            var json=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
            assertEquals(json.serialize(expected.colorIfAbsent(NamedTextColor.WHITE)),json.serialize(actual));
            assertTrue(TelegramChatBridge.plain(actual).contains(badge));
            assertTrue(TelegramChatBridge.plain(actual).contains(literal));
        }
    }
}
