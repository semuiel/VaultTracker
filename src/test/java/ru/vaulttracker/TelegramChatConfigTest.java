package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import static org.junit.jupiter.api.Assertions.*;

class TelegramChatConfigTest {
    @TempDir Path folder;
    static final String SETTINGS="""
        enabled: true
        token: '123456:abcdefghijklmnopqrstuvwxyz'
        chat:
          chatId: -100123
          topicId: 486
        advancements:
          chatId: -100123
          topicId: 123
        """;
    @Test void createsDocumentedDisabledConfigWithoutReplacingExistingFile() throws Exception {
        var first=TelegramChatConfig.load(folder);assertFalse(first.enabled);
        assertTrue(Files.readString(folder.resolve("telegramchat.yml")).contains("customEmojiId"));
        Files.writeString(folder.resolve("telegramchat.yml"),SETTINGS);
        var second=TelegramChatConfig.load(folder);assertTrue(second.enabled);assertEquals(486,second.chat.topicId());assertEquals(123,second.advancements.topicId());
        assertEquals(SETTINGS,Files.readString(folder.resolve("telegramchat.yml")));
    }
    @Test void independentTokenInheritsProxyAndDetectsSharedToken() throws Exception {
        var config=TelegramChatConfig.parse(SETTINGS);
        var proxy=new TelegramConfig.Proxy(TelegramConfig.ProxyType.SOCKS5,"localhost",1080,"user","secret");
        var catalogue=new TelegramConfig(true,config.token,Set.of(),Set.of(),List.of(),20,30,300,"https://api.telegram.org",proxy,new TelegramConfig.Retry(0,1000,30000),folder.resolve("telegram.offset"));
        assertTrue(config.shared(catalogue));var transport=config.transport(catalogue);assertSame(proxy,transport.proxy());
        assertEquals("telegramchat-123456.offset",transport.offsetFile().getFileName().toString());
        var other=TelegramChatConfig.parse(SETTINGS.replace("123456:","654321:"));assertFalse(other.shared(catalogue));assertNotEquals(transport.offsetFile(),other.transport(catalogue).offsetFile());
    }
    @Test void validatesTargetsAndEmojiIdentifiersWithoutPrintingToken() {
        assertThrows(IllegalArgumentException.class,()->TelegramChatConfig.parse(SETTINGS.replace("topicId: 486","topicId: -1")));
        assertThrows(IllegalArgumentException.class,()->TelegramChatConfig.parse(SETTINGS.replace("chatId: -100123","chatId: 0")));
        assertThrows(IllegalArgumentException.class,()->TelegramChatConfig.parse(SETTINGS+"\nlist:\n  dimensions:\n    end:\n      customEmojiId: 'not-an-id'\n"));
    }
    @Test void listUsesTwentyRowsDimensionIconsAndSafePlayerText() throws Exception {
        var config=TelegramChatConfig.parse(SETTINGS+"\nlist:\n  dimensions:\n    nether:\n      emoji: '🔥'\n      customEmojiId: '123456789'\n");
        var people=new ArrayList<TelegramChatFormat.PlayerRow>();
        for(int i=0;i<21;i++) people.add(new TelegramChatFormat.PlayerRow("Player"+String.format("%02d",i),"Display","world",i==0?"nether":"overworld"));
        var pages=TelegramChatFormat.playerList(config,people);assertEquals(2,pages.size());assertEquals(21,pages.getFirst().lines().count());assertEquals(2,pages.getLast().lines().count());
        assertTrue(pages.getFirst().contains("<tg-emoji emoji-id=\"123456789\">🔥</tg-emoji> Player00"));assertTrue(pages.getLast().contains("🌍 Player20"));
        assertFalse(TelegramChatFormat.withoutCustomEmoji(pages.getFirst()).contains("tg-emoji"));
        assertTrue(TelegramChatFormat.playerList(config,List.of(new TelegramChatFormat.PlayerRow("<b>&","x","world","other"))).getFirst().contains("&lt;b&gt;&amp;"));
    }
    @Test void pingToggleWorksWithOldAndNewRowTemplatesAndCustomSuffix() throws Exception {
        var people=List.of(new TelegramChatFormat.PlayerRow("Alex","Alex","world","overworld",84));
        for(String row:List.of("{emoji} {name}","{emoji} {name}{ping}")) {
            var config=TelegramChatConfig.parse(SETTINGS+"\nlist:\n  showPing: true\nformats:\n  listRow: '"+row+"'\n  listPing: ' [<code>{ping} ms</code>]'\n");
            assertTrue(TelegramChatFormat.playerList(config,people).getFirst().contains("🌍 Alex [<code>84 ms</code>]"));
            config.yaml.set("list.showPing",false);
            String hidden=TelegramChatFormat.playerList(config,people).getFirst();assertTrue(hidden.endsWith("🌍 Alex"));assertFalse(hidden.contains("84"));assertFalse(hidden.contains("{ping}"));
        }
    }
    @Test void pingColumnAlignsAcrossPagesAndKeepsCustomEmojiOutsideCode() throws Exception {
        var config=TelegramChatConfig.parse(SETTINGS+"\nlist:\n  showPing: true\n  dimensions:\n    overworld:\n      emoji: '🌍'\n      customEmojiId: '5339573139900735019'\nformats:\n  listRow: '{emoji} {name}'\n");
        var people=new ArrayList<TelegramChatFormat.PlayerRow>();
        for(int i=0;i<20;i++) people.add(new TelegramChatFormat.PlayerRow("A"+i,"x","world","overworld",31));
        people.add(new TelegramChatFormat.PlayerRow("LongestPlayerName","x","world","overworld",236));
        var pages=TelegramChatFormat.playerList(config,people);
        assertEquals(2,pages.size());
        assertTrue(pages.getFirst().contains("</tg-emoji> <code>A0"));
        var columns=pages.stream().flatMap(p->p.lines().skip(1)).map(line->line.substring(line.indexOf("<code>")+6)).map(line->line.indexOf("· ")).distinct().toList();
        assertEquals(1,columns.size());
        assertTrue(pages.getLast().contains("LongestPlayerName   · 236 мс</code>"));
        config.yaml.set("list.alignPing",false);
        assertTrue(TelegramChatFormat.playerList(config,people).getFirst().contains("</tg-emoji> A0 · 31 мс"));
    }
    @Test void requiredPrefixAndSinglePassPlaceholdersDoNotChangeMessageContent() throws Exception {
        var config=TelegramChatConfig.parse(SETTINGS+"\nmessages:\n  requirePrefixInMinecraft: ''\n");
        assertNull(TelegramChatFormat.chatText(config,"private chat"));assertEquals("Hello",TelegramChatFormat.chatText(config,"Hello"));
        assertEquals("{text}: literal",TelegramChatFormat.template("{sender}: {text}",Map.of("sender","{text}","text","literal")));
    }
    @Test void translatesVanillaAdvancementsAndCustomOverrides() throws Exception {
        var text=new TelegramChatText(folder);assertEquals("Доктор для зомби",text.plain(Component.translatable("advancements.story.cure_zombie_villager.title")));
        Files.writeString(folder.resolve("telegramchat-lang.json"),"{\"custom.title\":\"Герой %s\"}");
        assertEquals("Герой Alex",new TelegramChatText(folder).plain(Component.translatable("custom.title",Component.text("Alex"))));
    }
}
