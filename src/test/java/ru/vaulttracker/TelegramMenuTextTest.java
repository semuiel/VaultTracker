package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramMenuTextTest {
    @TempDir Path folder;
    @Test void changesTextButtonsAndSafelyRendersCustomEmoji() throws Exception {
        Files.writeString(folder.resolve("telegram-menu.yml"),"""
                emojis:
                  - original: "💎"
                    emoji: "🔷"
                    customEmojiId: "5339573139900735019"
                replacements:
                  - from: "Главное меню"
                    to: "💎 Новое меню"
                  - from: "Назад"
                    to: "Вернуться"
                """);
        var text=TelegramMenuText.load(folder,Logger.getAnonymousLogger());
        var view=text.view(new TelegramCommands.View("Главное меню <данные>",List.of(new TelegramCommands.Button("Назад","x"))));
        assertEquals("🔷 Новое меню <данные>",TelegramEmojiMarkup.plain(view.text()));assertEquals("Вернуться",view.buttons().getFirst().text());
        var body=TelegramApi.messageBody(1,view);assertEquals("HTML",body.get("parse_mode").getAsString());
        assertTrue(body.get("text").getAsString().contains("<tg-emoji emoji-id=\"5339573139900735019\">🔷</tg-emoji>"));
        assertTrue(body.get("text").getAsString().contains("&lt;данные&gt;"));
    }
    @Test void bundledFileIsCompleteAndKeepsCurrentDefaults() throws Exception {
        try(var source=getClass().getResourceAsStream("/telegram-menu.yml")) {assertNotNull(source);Files.copy(source,folder.resolve("telegram-menu.yml"));}
        String yaml=Files.readString(folder.resolve("telegram-menu.yml"));assertTrue(yaml.lines().filter(line->line.startsWith("  - from:")).count()>450);
        var text=TelegramMenuText.load(folder,Logger.getAnonymousLogger());
        var original=new TelegramCommands.View("💝 Пожертвования\nБаланс: 10\nВсе пожертвования добровольны.",List.of(new TelegramCommands.Button("↩ Назад","x")));
        assertEquals(original,text.view(original));
    }
    @Test void boldMarkupCanContainCustomEmojiWithoutLeakingInternalMarkers() {
        String source=TelegramEmojiMarkup.bold("Карточка "+TelegramEmojiMarkup.marker("123456789","❤️")+" "+TelegramEmojiMarkup.code("world · 1 2 3"));
        var rendered=TelegramEmojiMarkup.html(source);
        assertTrue(rendered.custom());assertEquals("<b>Карточка <tg-emoji emoji-id=\"123456789\">❤️</tg-emoji> </b><code>world · 1 2 3</code>",rendered.text());
        assertEquals("Карточка ❤️ world · 1 2 3",TelegramEmojiMarkup.plain(source));
    }
    @Test void worldAndCoordinatesBecomeOneCopyableCodeFragment() throws Exception {
        Files.writeString(folder.resolve("telegram-menu.yml"),"emojis: []\nreplacements: []\n");var text=TelegramMenuText.load(folder,Logger.getAnonymousLogger());
        var view=text.view(new TelegramCommands.View("Мир: world_nether\nКоординаты: -10 64 20"));
        assertEquals("📍 world_nether · -10 64 20",TelegramEmojiMarkup.plain(view.text()));
        assertTrue(TelegramEmojiMarkup.html(view.text()).text().contains("world_nether · <code>-10 64 20</code>"));
    }
    @Test void mainMenuDoesNotRepeatCabinetNavigationButton() throws Exception {
        Files.writeString(folder.resolve("telegram-menu.yml"),"emojis: []\nreplacements: []\n");var text=TelegramMenuText.load(folder,Logger.getAnonymousLogger());
        var view=text.view(new TelegramCommands.View("Торговый бот FLEXITY",List.of(
                new TelegramCommands.Button("🔎 Поиск","vg:search"),new TelegramCommands.Button("👤 Личный кабинет","vg:home"),
                new TelegramCommands.Button("↩ В кабинет","vg:home"),new TelegramCommands.Button("⌂ Меню","vg:menu"))));
        assertEquals(List.of("🔎 Поиск","👤 Личный кабинет"),view.buttons().stream().map(TelegramCommands.Button::text).toList());
    }
    @Test void highlightsFlexityAndSearchInstructions() throws Exception {
        Files.writeString(folder.resolve("telegram-menu.yml"),"emojis: []\nreplacements: []\n");var text=TelegramMenuText.load(folder,Logger.getAnonymousLogger());
        var view=text.view(new TelegramCommands.View("Поиск ресурсов FLEXITY\n«Игрок» · «Предмет» · [v] или [vault]"));
        String html=TelegramEmojiMarkup.html(view.text()).text();
        for(String key:List.of("Поиск ресурсов","FLEXITY","«Игрок»","«Предмет»","[v]","[vault]")) assertTrue(html.contains("<b>"+key+"</b>"),html);
    }
}
