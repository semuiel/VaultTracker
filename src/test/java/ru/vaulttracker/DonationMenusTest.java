package ru.vaulttracker;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DonationMenusTest {
    @TempDir Path folder;
    GuardService guard;TelegramGuardMenu menu;TelegramModeration moderation;HttpServer server;
    UUID owner=UUID.randomUUID();List<String> requests=new CopyOnWriteArrayList<>();
    Set<String> claimed=ConcurrentHashMap.newKeySet();AtomicLong reward=new AtomicLong(15000);
    JsonObject wallet=JsonParser.parseString("{\"balanceMinor\":1000000,\"availableMinor\":1000000,\"pendingMinor\":0,\"blocked\":false,\"premiumUntil\":0,\"premiumLiteUntil\":0}").getAsJsonObject();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();var body=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(path+" "+body);var response=new JsonObject();
            switch(path) {
                case "/wallet" -> {response=wallet.deepCopy();response.addProperty("linkRewardClaimed",claimed.contains(body.get("owner").getAsString()));}
                case "/link-reward-settings" -> response.addProperty("rewardMinor",reward.get());
                case "/link-reward-update" -> {reward.set(body.get("rewardMinor").getAsLong());response.addProperty("rewardMinor",reward.get());}
                case "/link-reward-claim" -> {boolean already=!claimed.add(body.get("owner").getAsString());response.addProperty("alreadyClaimed",already);response.addProperty("amountMinor",reward.get());response.addProperty("balanceMinor",1000000+reward.get());}
                case "/voting-providers" -> response.add("providers",new JsonArray());
            }
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
        guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,500),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,0,500),Set.of(99L));link(42,owner);guard.donationsVisible(500,true).get();
        moderation=mock(TelegramModeration.class);menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(s->{}),mock(StorageEngine.class),8),moderation);
    }
    void link(long tg,UUID uuid) throws Exception {String code=TelegramEmojiMarkup.plain(guard.generate(tg).get()).split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);guard.link(uuid,"Player"+tg,code).get();}
    String button(TelegramCommands.View v,String text) {return v.buttons().stream().filter(b->b.text().contains(text)).findFirst().orElseThrow().data();}
    TelegramCommands.View click(long tg,TelegramCommands.View v,String text)throws Exception{return menu.callback(tg,button(v,text)).view();}
    @AfterEach void close(){guard.close();server.stop(0);}

    @Test void freeCoinsReuseVotingAndClaimHidesButtonAcrossUnlinkRelinkAndNewTelegram() throws Exception {
        var donations=menu.cabinet.donations(42);
        assertEquals(List.of("💳 Пополнить","💎 Премиум","🪙 Free-монеты","⌂ Меню"),donations.buttons().stream().map(TelegramCommands.Button::text).toList());
        var free=click(42,donations,"Free-монеты");String rewardButton=button(free,"Награда за привязку");
        assertTrue(click(42,free,"Голосование").text().contains("Голосуйте за FLEXITY"));
        var success=menu.callback(42,rewardButton).view();assertTrue(success.text().contains("150.00"));
        assertFalse(success.buttons().stream().anyMatch(b->b.text().contains("Награда за привязку")));
        assertTrue(menu.callback(42,rewardButton).view().text().contains("уже получена"));
        guard.unlink(owner).get();link(43,owner);
        assertFalse(menu.freeCoins.home(43).buttons().stream().anyMatch(b->b.text().contains("Награда за привязку")));
        assertTrue(requests.stream().filter(r->r.startsWith("/link-reward-claim")).allMatch(r->r.contains(owner.toString())));
    }
    @Test void unlinkedCallbacksDoNotCreditAndSuccessfulLinkImmediatelyEnablesReward() throws Exception {
        var free=menu.freeCoins.home(100);var linkView=click(100,free,"Награда за привязку");
        assertTrue(linkView.text().contains("/vtrack link "));assertFalse(requests.stream().anyMatch(r->r.startsWith("/link-reward-claim")));
        UUID newlyLinked=UUID.randomUUID();String code=TelegramEmojiMarkup.plain(linkView.text()).split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(newlyLinked,"NewPlayer",code).get();
        var success=click(100,menu.freeCoins.home(100),"Награда за привязку");assertTrue(success.text().contains("Зачислено"));
        assertTrue(claimed.contains(newlyLinked.toString()));
        String stale=button(menu.freeCoins.home(42),"Награда за привязку");guard.unlink(owner).get();requests.clear();
        assertTrue(menu.callback(42,stale).view().text().contains("/vtrack link "));
        assertFalse(requests.stream().anyMatch(r->r.startsWith("/link-reward-claim")));
    }
    @Test void rootAmountUsesDecimalConfirmationAndChecksPermissionsAgain() throws Exception {
        var admin=menu.cabinet.donationAdmin(500);String edit=button(admin,"Награда за привязку: 150");
        assertTrue(menu.callback(99,edit).alert());menu.callback(500,edit);
        assertTrue(menu.input(500,"1.001").text().contains("два знака"));
        assertTrue(menu.input(500,"-1").text().contains("от 0"));
        var confirm=menu.input(500,"123,45");assertEquals(15000,reward.get());
        assertTrue(menu.callback(99,button(confirm,"Подтвердить")).alert());
        assertTrue(click(500,confirm,"Подтвердить").buttons().stream().anyMatch(b->b.text().contains("123.45")));
        assertEquals(12345,reward.get());assertThrows(SecurityException.class,()->menu.freeCoins.adminLabel(99));
        guard.addRoot(500,600).get();var second=menu.cabinet.donationAdmin(600);click(600,second,"Награда за привязку");var pending=menu.input(600,"200");
        guard.removeRoot(500,600).get();assertThrows(SecurityException.class,()->click(600,pending,"Подтвердить"));assertEquals(12345,reward.get());
    }
    @Test void tariffDescriptionsPricesCallbacksAndNavigationAreExact() throws Exception {
        var selector=click(42,menu.cabinet.donations(42),"Премиум");
        assertNotNull(button(selector,"Premium Lite"));assertNotNull(button(selector,"💎 Premium"));
        long[][] expected={{31900,54900,149000,269000},{60000,100000,270000,480000}};int index=0;
        for(var p:PremiumProduct.values()) {
            var card=click(42,selector,p.title);assertTrue(card.text().contains(p.title));assertFalse(card.text().contains("flexity."));
            for(String common:List.of("Цвет ника","Цвет сообщений","пресеты","названия предметов","на голове"))assertTrue(card.text().contains(common),common);
            for(String extra:List.of("Градиенты","Значок после ника","Тема профиля","облачков","Частицы","Питомцы","поводки","колонка","пластинок"))assertEquals(p==PremiumProduct.PREMIUM,card.text().contains(extra),extra);
            int i=0;
            for(int months:PremiumProduct.MONTHS) {
                long cost=expected[index][i++];assertEquals(cost,p.price(months));
                String label=months+" мес. · "+java.math.BigDecimal.valueOf(cost,2).stripTrailingZeros().toPlainString()+" 🪙";
                var result=new JsonObject();result.addProperty("balanceMinor",1_000_000-cost);
                when(moderation.donationChange(eq(42L),eq(owner),eq("Player42"),anyString(),eq("buy"),eq(months*30),eq(0L),eq(p))).thenReturn(result);
                String data=button(card,label);menu.callback(42,data);menu.callback(42,data);
                verify(moderation,times(2)).donationChange(42,owner,"Player42",CabinetMenu.requestId(data),"buy",months*30,0,p);
            }
            assertTrue(click(42,card,"К тарифам").text().contains("Выберите тариф"));index++;
        }
    }
    @Test void donationHeaderShowsIndependentExpiriesPendingMoneyAndDebtLock() throws Exception {
        long until=Instant.now().getEpochSecond()+86400;
        wallet.addProperty("premiumLiteUntil",until);wallet.addProperty("pendingMinor",100);wallet.addProperty("blocked",true);
        String lite=menu.cabinet.donations(42).text();assertTrue(lite.contains("Premium Lite · до"));assertFalse(lite.contains("не активен"));
        assertTrue(lite.contains("ждёт подтверждения"));assertTrue(lite.contains("приостановлены"));
        wallet.addProperty("premiumUntil",until+86400);String both=menu.cabinet.donations(42).text();
        assertTrue(both.contains("Premium Lite · до"));assertTrue(both.contains("Premium · до"));
        wallet.addProperty("premiumUntil",0);wallet.addProperty("premiumLiteUntil",0);assertTrue(menu.cabinet.donations(42).text().contains("не активен"));
    }
}
