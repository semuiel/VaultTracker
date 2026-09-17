package ru.vaulttracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramReportsTest {
    @TempDir Path folder;
    TelegramApi api=mock(TelegramApi.class);
    TelegramChatConfig config;
    TelegramListExpiry expiry;
    TelegramReports reports;
    @BeforeEach void setup() throws Exception {
        config=TelegramChatConfig.parse(TelegramChatConfigTest.SETTINGS+"\nreports:\n  enabled: true\n  chatId: -100999\n  topicId: 34339\n  maxWarnings: 2\n");
        when(api.memberStatus(anyLong(),anyLong())).thenReturn("member");when(api.memberStatus(-100123,10)).thenReturn("administrator");
        when(api.sendHtml(eq(-100999L),eq(34339),anyString(),anyBoolean(),anyList())).thenReturn(77);
        expiry=new TelegramListExpiry(folder.resolve("expiry"),api,Logger.getAnonymousLogger());reports=new TelegramReports(folder,config,api,expiry,Logger.getAnonymousLogger());
    }
    @AfterEach void close() throws Exception {reports.close();expiry.close();}
    void flush() throws Exception {var field=TelegramReports.class.getDeclaredField("worker");field.setAccessible(true);((ExecutorService)field.get(reports)).submit(()->{}).get(5,TimeUnit.SECONDS);}
    TelegramApi.Incoming command(long user,String text) {return new TelegramApi.Incoming(1,-100123,999,user,3,text,null,null,false,"User",false,false,"User",new TelegramApi.Reply(2,20,"Target","<b>literal</b>",false),false);}
    TelegramApi.Incoming callback(long user,String data,int id) {return new TelegramApi.Incoming(2,-100999,34339,user,id,null,"cb",data,false,"User");}
    @Test void everyoneCanReportButOnlyCurrentSourceAdminsCanActAndCannotReplay() throws Exception {
        assertTrue(reports.consume(command(30,"/report spam"),"ChatBot"));flush();
        @SuppressWarnings("unchecked") var keys=org.mockito.ArgumentCaptor.forClass((Class<List<TelegramCommands.Button>>)(Class<?>)List.class);
        verify(api).sendHtml(eq(-100999L),eq(34339),contains("&lt;b&gt;literal&lt;/b&gt;"),eq(false),keys.capture());
        String warn=keys.getValue().stream().filter(b->b.text().contains("Предупреждение")).findFirst().orElseThrow().data();
        reports.consume(callback(30,warn,77),"ChatBot");flush();verify(api).answerCallback(eq("cb"),contains("действующие права"),eq(true));
        reports.consume(callback(10,warn,78),"ChatBot");flush();verify(api,never()).editHtml(anyLong(),anyInt(),anyString(),anyList());
        reports.consume(callback(10,warn,77),"ChatBot");flush();verify(api).editHtml(eq(-100999L),eq(77),contains("Предупреждение 1/2"),eq(List.of()));
        reports.consume(callback(10,warn,77),"ChatBot");flush();verify(api,times(1)).editHtml(anyLong(),anyInt(),anyString(),anyList());
        reports.consume(command(30,"/warn spam"),"ChatBot");flush();verify(api).sendHtml(eq(-100123L),eq(999),contains("только действующим"),eq(true));
    }
    @Test void warnsRequireLiveRightsAndTriggerConfiguredThreshold() throws Exception {
        reports.consume(command(10,"/warn one"),"ChatBot");flush();verify(api,never()).banMember(anyLong(),anyLong(),anyLong());
        reports.consume(command(10,"/warn two"),"ChatBot");flush();verify(api).banMember(eq(-100123L),eq(20L),longThat(t->t>System.currentTimeMillis()/1000+86000));
        when(api.memberStatus(-100123,10)).thenReturn("member");reports.consume(command(10,"/mute 10m"),"ChatBot");flush();verify(api,never()).muteMember(anyLong(),anyLong(),anyLong(),anyBoolean());
    }
    @Test void protectsAdminsAndRejectsForgedOrForeignRouting() throws Exception {
        when(api.memberStatus(-100123,20)).thenReturn("administrator");reports.consume(command(10,"/ban"),"ChatBot");flush();verify(api,never()).banMember(anyLong(),anyLong(),anyLong());
        assertFalse(reports.consume(command(10,"/ban@OtherBot"),"ChatBot"));
        assertFalse(reports.consume(new TelegramApi.Incoming(1,-555,9,10,1,"/report",null,null,false),"ChatBot"));
        assertThrows(IllegalArgumentException.class,()->TelegramReports.duration("999999d",true));assertEquals(600,TelegramReports.duration("10m",false));assertEquals(0,TelegramReports.duration("",true));
    }
    @Test void reportAndWarningsSurviveModuleRestart() throws Exception {
        reports.consume(command(30,"/report reason"),"ChatBot");flush();
        @SuppressWarnings("unchecked") var keys=org.mockito.ArgumentCaptor.forClass((Class<List<TelegramCommands.Button>>)(Class<?>)List.class);
        verify(api).sendHtml(eq(-100999L),eq(34339),anyString(),eq(false),keys.capture());
        String warn=keys.getValue().stream().filter(b->b.text().contains("Предупреждение")).findFirst().orElseThrow().data();
        reports.consume(command(10,"/warn first"),"ChatBot");flush();reports.close();
        reports=new TelegramReports(folder,config,api,expiry,Logger.getAnonymousLogger());
        reports.consume(callback(10,warn,77),"ChatBot");flush();verify(api).banMember(eq(-100123L),eq(20L),anyLong());
        verify(api).editHtml(eq(-100999L),eq(77),contains("Предупреждение 2/2"),eq(List.of()));
    }
    @Test void failedAutoBanStillReportsSavedWarningTruthfully() throws Exception {
        doThrow(new java.io.IOException("not enough rights")).when(api).banMember(anyLong(),anyLong(),anyLong());
        reports.consume(command(10,"/warn first"),"ChatBot");flush();reports.consume(command(10,"/warn second"),"ChatBot");flush();
        verify(api).sendHtml(eq(-100123L),eq(999),contains("2/2 сохранено. Автобан не подтверждён"),eq(true));
    }
}
