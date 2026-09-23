package ru.vaulttracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GuardServiceTest {
    @TempDir Path folder;
    final AtomicLong clock=new AtomicLong(2_000_000);
    final UUID owner=UUID.randomUUID();
    final BlockKey sign=new BlockKey(UUID.randomUUID(),1,64,1);
    final UUID generation=UUID.randomUUID();
    GuardService guard;
    @BeforeEach void start() throws Exception {guard=open();guard.configure(new GuardConfig(true,120_000),Set.of(99L));}
    GuardService open() throws Exception {return new GuardService(folder.resolve("guard"),new GuardConfig(true,120_000),Logger.getAnonymousLogger(),clock::get);}
    @AfterEach void close() {guard.close();}
    String code(long tg) throws Exception {return guard.generate(tg).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);}
    Snapshot snapshot(long count,long revision) {return new Snapshot(sign,generation,owner,"Alex",List.of(sign),count==0 ? Map.of() : Map.of("DIAMOND",count),true,clock.get(),revision);}
    void offline() {guard.presence(owner,"Alex",false);clock.addAndGet(120_001);}
    List<GuardService.Event> events() throws Exception {return guard.history(99,0,20).get();}

    List<GuardService.Delivery> ready() throws Exception {guard.account(0).get();clock.addAndGet(10000);return guard.deliveries().get();}
    @Test void batchesTenSecondsPersistsAndKeepsChannelsPrivate() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.restore(List.of(snapshot(10,1)));offline();
        guard.attribute(sign,"Griefer");guard.accept(snapshot(9,2));events();
        assertTrue(guard.deliveries().get().isEmpty());clock.addAndGet(9000);
        guard.attribute(sign,"Griefer");guard.accept(snapshot(7,3));events();
        assertTrue(guard.deliveries().get().isEmpty());guard.close();guard=open();guard.configure(new GuardConfig(true,120000),Set.of(99L));
        clock.addAndGet(1000);var result=guard.deliveries().get();assertEquals(2,result.size());
        String player=result.stream().filter(d->d.recipient()==42).findFirst().orElseThrow().text();
        String admin=result.stream().filter(d->d.recipient()==99).findFirst().orElseThrow().text();
        assertTrue(player.contains("событий: 2"));assertTrue(player.contains("-3 шт."));assertFalse(player.contains("Griefer"));assertTrue(admin.contains("Griefer"));
        assertEquals(2,events().size());assertEquals(2,guard.searchHistory(99,"алмаз",0,6).get().size());
        assertEquals(2,guard.searchHistory(99,"grief",0,6).get().size());assertTrue(guard.searchHistory(99,"missing",0,6).get().isEmpty());
        assertThrows(ExecutionException.class,()->guard.searchHistory(42,"Alex",0,6).get());
        guard.restore(List.of(snapshot(7,3)));guard.attribute(sign,"Griefer");guard.accept(snapshot(6,4));events();
        for(var d:result) guard.delivered(d.id(),true).get();assertTrue(guard.deliveries().get().isEmpty());
        clock.addAndGet(10000);assertEquals(2,guard.deliveries().get().size());
    }
    @Test void withdrawalAndReturnDoNotCancelEachOtherInSummary() {
        var first=new GuardService.Event(1,0,"Alex","world",Map.of("DIAMOND",-5L),false,"A");
        var second=new GuardService.Event(2,1,"Alex","world",Map.of("DIAMOND",5L),false,"A");
        String text=GuardService.batchText(List.of(first,second),true);
        assertTrue(text.contains("-5 шт."));assertTrue(text.contains("+5 шт."));
    }
    @Test void actorAppearsOnlyInAdminDeliveryAndHistory() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.restore(List.of(snapshot(10,1)));offline();
        guard.attribute(sign,"Griefer");guard.accept(snapshot(9,2));
        var deliveries=ready();
        String ownerText=deliveries.stream().filter(d->d.recipient()==42).findFirst().orElseThrow().text();
        String adminText=deliveries.stream().filter(d->d.recipient()==99).findFirst().orElseThrow().text();
        assertFalse(ownerText.contains("Кто изменил"));assertTrue(adminText.contains("Кто изменил: Griefer"));
        assertEquals("Griefer",events().getFirst().actor());assertTrue(GuardService.eventText(events().getFirst(),0,8).contains("Кто изменил: Griefer"));
    }
    @Test void bastionGrantIsPendingOnlyAfterSuccessfulLinkAndSurvivesRestart() throws Exception {
        String valid=code(42);
        assertFalse(guard.linkOutcome(owner,"Alex","WRONG-CODE").get().linked());
        assertFalse(guard.bastionPending(owner).get());
        var linked=guard.linkOutcome(owner,"Alex",valid).get();assertTrue(linked.linked());
        assertTrue(guard.bastionPending(owner).get());
        assertFalse(guard.linkOutcome(owner,"Alex",valid).get().linked());
        guard.close();guard=open();guard.configure(new GuardConfig(true,120_000),Set.of(99L));
        assertTrue(guard.bastionPending(owner).get());
        guard.bastionGranted(owner).get();assertFalse(guard.bastionPending(owner).get());
        guard.close();guard=open();guard.configure(new GuardConfig(true,120_000),Set.of(99L));
        assertFalse(guard.bastionPending(owner).get());
    }
    @Test void personalAdminAlertsKeepActorEvenWhenAdminChannelIsDisabledAndHideAfterDemotion() throws Exception {
        UUID actor=UUID.randomUUID();guard.link(owner,"Alex",code(99)).get();
        guard.toggleAdmin(99).get();guard.offlineSeconds(99,-1L).get();guard.presence(owner,"Alex",true);
        guard.restore(List.of(snapshot(10,1)));guard.attribute(sign,actor,"Griefer");guard.accept(snapshot(9,2));
        var delivery=ready().getFirst();assertEquals(99,delivery.recipient());assertEquals(actor,delivery.actorUuid());assertTrue(delivery.text().contains("Кто изменил: Griefer"));
        assertEquals("Griefer",guard.ownHistory(99,0,10).get().getFirst().actor());
        guard.configure(new GuardConfig(true,120_000),Set.of());
        var demoted=guard.deliveries().get().getFirst();assertFalse(demoted.text().contains("Griefer"));assertNull(demoted.actorUuid());assertNull(demoted.actor());
        assertNull(guard.ownHistory(99,0,10).get().getFirst().actor());
    }
    @Test void differentActorsGetSeparateNotificationsAndUuidSurvivesRestart() throws Exception {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();guard.restore(List.of(snapshot(10,1)));offline();
        guard.attribute(sign,first,"One");guard.accept(snapshot(9,2));events();
        guard.attribute(sign,second,"Two");guard.accept(snapshot(8,3));events();
        guard.close();guard=open();guard.configure(new GuardConfig(true,120_000),Set.of(99L));
        var deliveries=ready();assertEquals(2,deliveries.size());
        assertEquals(Set.of(first,second),new HashSet<>(deliveries.stream().map(GuardService.Delivery::actorUuid).toList()));
        assertTrue(deliveries.stream().noneMatch(d->d.text().contains("One")&&d.text().contains("Two")));
    }
    @Test void automatedInventoryChangesUpdateBaselineWithoutBotAlerts() throws Exception {
        guard.restore(List.of(snapshot(10,1)));offline();
        guard.automated(sign);guard.accept(snapshot(7,2));
        assertTrue(events().isEmpty());assertTrue(ready().isEmpty());
        // A later player action is still reported normally.
        guard.attribute(sign,UUID.randomUUID(),"Griefer");guard.accept(snapshot(6,3));
        assertEquals(1,events().size());assertEquals("Griefer",events().getFirst().actor());
    }
    @Test void automatedOrExpiredAttributionIsReportedAsUnknownToAdmins() throws Exception {
        guard.restore(List.of(snapshot(10,1)));offline();guard.accept(snapshot(9,2));
        assertTrue(ready().getFirst().text().contains("Кто изменил: не определено"));
        for(var d:ready()) guard.delivered(d.id(),true).get();
        guard.attribute(sign,"OldActor");clock.addAndGet(30_001);guard.accept(snapshot(8,3));
        assertTrue(ready().getFirst().text().contains("Кто изменил: не определено"));
    }
    @Test void ownerWorkingInOwnStorageCreatesNoGuardEventButAnotherPlayerStillDoes() throws Exception {
        UUID other=UUID.randomUUID();guard.link(owner,"Alex",code(42)).get();guard.restore(List.of(snapshot(10,1)));offline();
        guard.attribute(sign,owner,"Alex");guard.accept(snapshot(9,2));
        assertTrue(events().isEmpty());assertTrue(ready().isEmpty());
        guard.attribute(sign,other,"Griefer");guard.accept(snapshot(8,3));
        assertEquals(1,events().size());assertEquals("Griefer",events().getFirst().actor());
    }
    @Test void friendsCreateHistoryButNeverNotifyOwnerOrAdminsAndPersist() throws Exception {
        UUID friend=UUID.randomUUID();guard.link(owner,"Alex",code(42)).get();guard.addFriend(42,friend,"Trusted").get();
        assertEquals(List.of(new GuardService.Friend(friend,"Trusted")),guard.friends(42).get());
        guard.restore(List.of(snapshot(10,1)));offline();guard.attribute(sign,friend,"Trusted");guard.accept(snapshot(9,2));
        assertEquals(1,events().size());assertTrue(ready().isEmpty());
        guard.close();guard=open();guard.configure(new GuardConfig(true,120_000),Set.of(99L));
        assertEquals("Trusted",guard.friends(42).get().getFirst().name());guard.removeFriend(42,friend).get();assertTrue(guard.friends(42).get().isEmpty());
        guard.restore(List.of(snapshot(9,2)));guard.attribute(sign,friend,"Trusted");guard.accept(snapshot(8,3));
        assertEquals(2,events().size());assertEquals(Set.of(42L,99L),new HashSet<>(ready().stream().map(GuardService.Delivery::recipient).toList()));
    }

    @Test void personalDayDoesNotDelayAdminAlertsAndAppliesToFutureChanges() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.offlineSeconds(42,86400L).get();
        guard.restore(List.of(snapshot(10,1)));offline();guard.accept(snapshot(9,2));
        var first=ready();assertEquals(List.of(99L),first.stream().map(GuardService.Delivery::recipient).toList());
        for(var d:first) guard.delivered(d.id(),true).get();
        clock.addAndGet(86400_000);guard.accept(snapshot(8,3));
        assertEquals(Set.of(42L,99L),new HashSet<>(ready().stream().map(GuardService.Delivery::recipient).toList()));
    }
    @Test void shorterPersonalTimeDoesNotSendAdminsEarlyOrLeakBetweenChannels() throws Exception {
        guard.link(owner,"Alex",code(99)).get();guard.offlineSeconds(99,0L).get();
        guard.restore(List.of(snapshot(10,1)));guard.presence(owner,"Alex",false);guard.accept(snapshot(9,2));
        assertEquals(1,ready().size());guard.toggleOwn(99).get();
        assertTrue(ready().isEmpty()); // admin preference cannot revive owner-only delivery
        guard.toggleOwn(99).get();guard.offlineSeconds(99,86400L).get();clock.addAndGet(120001);
        guard.accept(snapshot(8,3));assertEquals(1,ready().size());guard.toggleAdmin(99).get();
        assertTrue(ready().isEmpty()); // owner preference cannot revive admin-only delivery
    }
    @Test void personalTimePersistsAndResetFollowsServerSetting() throws Exception {
        guard.link(owner,"Alex",code(42)).get();assertNull(guard.offlineSeconds(42).get());
        guard.offlineSeconds(42,86400L).get();guard.close();guard=open();
        assertEquals(86400L,guard.offlineSeconds(42).get());
        guard.configure(new GuardConfig(true,900000),Set.of(99L));assertEquals(86400L,guard.offlineSeconds(42).get());
        guard.offlineSeconds(42,null).get();assertNull(guard.offlineSeconds(42).get());assertEquals(900,guard.defaultOfflineSeconds());
        assertThrows(ExecutionException.class,()->guard.offlineSeconds(43,10L).get());
        assertThrows(ExecutionException.class,()->guard.offlineSeconds(42,-2L).get());
        assertThrows(ExecutionException.class,()->guard.offlineSeconds(42,31536001L).get());
        guard.offlineSeconds(42,31536000L).get();assertEquals(31536000L,guard.offlineSeconds(42).get());
    }
    @Test void worldNamesAppearInEventsAndLegacyTextAndSurviveRestart() throws Exception {
        guard.world(sign.world(),"world_nether");guard.restore(List.of(snapshot(10,1)));offline();guard.accept(snapshot(9,2));
        assertEquals("Мир: world_nether\nКоординаты: 1 64 1",events().getFirst().location());
        assertTrue(ready().getFirst().text().contains("Мир: world_nether\nКоординаты:"));
        String legacy="Мир "+sign.world()+"; блок -3779 64 -305";
        assertEquals("Мир: world_nether\nКоординаты: -3779 64 -305",guard.readableLocation(legacy));
        guard.close();guard=open();assertTrue(guard.readableLocation(legacy).contains("world_nether"));
    }
    @Test void upgradingLegacyAccountTablePreservesExistingLink() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.toggleOwn(42).get();guard.close();
        try(var db=java.sql.DriverManager.getConnection("jdbc:h2:"+folder.resolve("guard").toAbsolutePath(),"sa","");var s=db.createStatement()) {
            s.execute("ALTER TABLE accounts DROP COLUMN offline_seconds");
            s.execute("ALTER TABLE outbox DROP COLUMN own_channel");s.execute("ALTER TABLE outbox DROP COLUMN admin_channel");
        }
        guard=open();assertEquals(owner,guard.account(42).get().uuid());assertFalse(guard.account(42).get().notifications());
        assertNull(guard.offlineSeconds(42).get());guard.offlineSeconds(42,86400L).get();assertEquals(86400L,guard.offlineSeconds(42).get());
    }

    @Test void bindingIsSingleUseAndCannotReplaceEitherAccount() throws Exception {
        String token=code(42);assertEquals(10,token.length());assertTrue(token.matches("[A-HJ-NP-Z2-9]{10}"));assertTrue(guard.link(owner,"Alex",token.toLowerCase(Locale.ROOT)).get().contains("привязан к Telegram"));
        assertEquals(owner,guard.account(42).get().uuid());
        assertTrue(guard.link(UUID.randomUUID(),"Other",token).get().contains("неверный"));
        String second=code(43);assertTrue(guard.link(owner,"Alex",second).get().contains("уже привязан"));
        assertNull(guard.account(43).get());
    }
    @Test void completeLinkCommandIsOneCopyableTelegramCodeBlock() throws Exception {
        String generated=guard.generate(42).get(),plain=TelegramEmojiMarkup.plain(generated);String token=plain.split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        assertTrue(plain.contains("/vtrack link "+token));
        String html=TelegramEmojiMarkup.html(generated).text();assertTrue(html.contains("<code>/vtrack link "+token+"</code>"),html);
    }
    @Test void codesExpireAndRegenerationInvalidatesEarlierCode() throws Exception {
        String old=code(42),current=code(42);
        assertTrue(guard.link(owner,"Alex",old).get().contains("неверный"));
        clock.addAndGet(GuardService.CODE_TTL);
        assertTrue(guard.link(owner,"Alex",current).get().contains("истёк"));
    }
    @Test void simultaneousLinkRequestsConsumeCodeOnlyOnce() throws Exception {
        String token=code(42);var one=guard.link(owner,"Alex",token);var two=guard.link(UUID.randomUUID(),"Other",token);
        assertTrue(one.get().contains("привязан к Telegram"));assertTrue(two.get().contains("неверный"));
    }
    @Test void ignoresRegistrationOnlineChangesGracePeriodAndUnchangedSnapshots() throws Exception {
        guard.accept(snapshot(10,1));guard.presence(owner,"Alex",true);clock.addAndGet(500_000);
        guard.accept(snapshot(9,2));guard.presence(owner,"Alex",false);clock.addAndGet(119_999);
        guard.accept(snapshot(8,3));clock.addAndGet(2);guard.accept(snapshot(8,4));
        assertTrue(events().isEmpty());
        guard.accept(snapshot(7,5));assertEquals(-1L,events().getFirst().changes().get("DIAMOND"));
    }
    @Test void offlineChangesNotifyOwnerAndAdminsAndDoNotDuplicateUnchangedUpdates() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.restore(List.of(snapshot(10,1)));offline();
        guard.accept(snapshot(7,2));guard.accept(snapshot(7,3));
        assertEquals(1,events().size());assertEquals(-3L,events().getFirst().changes().get("DIAMOND"));
        var deliveries=ready();assertEquals(Set.of(42L,99L),new HashSet<>(deliveries.stream().map(GuardService.Delivery::recipient).toList()));
        guard.presence(owner,"Alex",true);guard.accept(snapshot(2,4));assertEquals(1,events().size());
    }
    @Test void ownerAdminGetsOneMessageAndTogglesAreIndependent() throws Exception {
        guard.link(owner,"Alex",code(99)).get();guard.restore(List.of(snapshot(10,1)));offline();
        guard.accept(snapshot(9,2));assertEquals(1,ready().size());
        guard.toggleOwn(99).get();assertEquals(1,ready().size());
        guard.toggleAdmin(99).get();assertTrue(ready().isEmpty());
        guard.accept(snapshot(8,3));assertTrue(ready().isEmpty());assertEquals(2,events().size());
    }
    @Test void notificationOptOutAndRemovedAdminApplyToQueuedMessages() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.restore(List.of(snapshot(10,1)));offline();guard.accept(snapshot(9,2));
        assertEquals(2,ready().size());guard.toggleOwn(42).get();
        guard.configure(new GuardConfig(true,120_000),Set.of());assertTrue(ready().isEmpty());
        assertThrows(ExecutionException.class,()->guard.history(99,0,5).get());
        assertThrows(ExecutionException.class,()->guard.toggleAdmin(42).get());
    }
    @Test void linksPreferencesEventsOfflineTimeAndOutboxSurviveRestart() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.toggleAdmin(99).get();guard.restore(List.of(snapshot(10,1)));offline();
        guard.accept(snapshot(9,2));assertEquals(1,ready().size());guard.close();
        guard=open();guard.configure(new GuardConfig(true,120_000),Set.of(99L));guard.restore(List.of(snapshot(9,2)));
        assertEquals(owner,guard.account(42).get().uuid());assertFalse(guard.adminAlerts(99).get());assertEquals(1,events().size());
        assertEquals(1,ready().size());guard.accept(snapshot(8,3));assertEquals(2,events().size());
    }
    @Test void restoredBaselineDoesNotCreateAnEventAndUnknownPresenceUsesStartupGrace() throws Exception {
        guard.restore(List.of(snapshot(10,1)));guard.accept(snapshot(9,2));assertTrue(events().isEmpty());
        clock.addAndGet(120_001);guard.accept(snapshot(8,3));assertEquals(1,events().size());
    }
    @Test void removalIsDistinctFromTheftAndReregistrationIsNotAnAlert() throws Exception {
        guard.restore(List.of(snapshot(10,1)));offline();
        guard.accept(new Snapshot(sign,generation,owner,"Alex",List.of(sign),Map.of(),false,clock.get(),2));
        var e=events().getFirst();assertTrue(e.removed());assertTrue(e.changes().isEmpty());assertTrue(GuardService.eventText(e,0,8).contains("не подтверждение кражи"));
        guard.accept(new Snapshot(sign,UUID.randomUUID(),owner,"Alex",List.of(sign),Map.of("DIAMOND",9L),true,clock.get(),3));assertEquals(1,events().size());
    }
    @Test void disabledGuardKeepsBaselineWithoutRecordingChanges() throws Exception {
        guard.restore(List.of(snapshot(10,1)));offline();guard.configure(new GuardConfig(false,120_000),Set.of(99L));
        guard.accept(snapshot(7,2));assertTrue(events().isEmpty());guard.configure(new GuardConfig(true,120_000),Set.of(99L));
        guard.accept(snapshot(6,3));assertEquals(-1L,events().getFirst().changes().get("DIAMOND"));
    }
    @Test void retryAcknowledgementAndThirtyDayRetention() throws Exception {
        guard.restore(List.of(snapshot(10,1)));offline();guard.accept(snapshot(9,2));
        var delivery=ready().getFirst();guard.delivered(delivery.id(),false).get();assertTrue(ready().isEmpty());
        clock.addAndGet(300_000);assertEquals(1,ready().size());guard.delivered(delivery.id(),true).get();assertTrue(ready().isEmpty());
        long id=events().getFirst().id();clock.addAndGet(GuardService.RETENTION+1);assertTrue(events().isEmpty());assertNull(guard.event(99,id).get());
    }
    @Test void joinUpdatesNameButNotUuidOrPreferences() throws Exception {
        guard.link(owner,"Alex",code(42)).get();guard.toggleOwn(42).get();guard.presence(owner,"NewName",true);
        var a=guard.account(42).get();assertEquals(owner,a.uuid());assertEquals("NewName",a.name());assertFalse(a.notifications());
    }
    @Test void newConfigCreatedWithoutTouchingTelegramAndExistingGuardPreserved() throws Exception {
        Path telegram=folder.resolve("telegram.yml");Files.writeString(telegram,"keep-existing-settings");
        assertEquals(120_000,GuardConfig.load(folder).absenceMillis());assertEquals("keep-existing-settings",Files.readString(telegram));
        Files.writeString(folder.resolve("guard.yml"),"enabled: false\noffline-seconds: 25\n");
        assertEquals(new GuardConfig(false,25_000),GuardConfig.load(folder));
        Files.writeString(folder.resolve("guard.yml"),"offline-seconds: -1\n");assertThrows(IllegalArgumentException.class,()->GuardConfig.load(folder));
    }
}
