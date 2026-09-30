package ru.vaulttracker;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelegramActionRegistryTest {
    @Test void directLegacyCallbacksAndParameterizedButtonsShareOneRouter() throws Exception {
        var clock=new AtomicLong(1_000_000);
        var routes=new TelegramActionRegistry(null,clock::get);
        var section=routes.scope("test");
        section.direct("vg:home",(user,data)->new TelegramCommands.View("home"));
        section.directPrefix("vg:dialog:",(user,data)->new TelegramCommands.View(data.substring("vg:dialog:".length())));
        section.on("page",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,
                (user,action,data)->new TelegramCommands.View(action.value()+"/"+action.page()));
        String button=routes.button(42,"Next","page","diamond",2,0).data();

        assertEquals("home",routes.dispatch(42,"vg:home").view().text());
        assertEquals("peer",routes.dispatch(42,"vg:dialog:peer").view().text());
        assertEquals("diamond/2",routes.dispatch(42,button).view().text());
        routes.dispatch(42,"vg:home");
        assertEquals("diamond/2",routes.dispatch(42,button).view().text());
        assertTrue(routes.dispatch(43,button).alert());
        assertTrue(routes.dispatch(42,"vg:unknown").alert());
    }

    @Test void oneShotExpiryAndPerUserLimitBoundOldButtonState() throws Exception {
        var clock=new AtomicLong(1_000_000);
        var routes=new TelegramActionRegistry(null,clock::get);
        var section=routes.scope("test");
        section.on("once",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true,
                (user,action,data)->new TelegramCommands.View("done"));
        section.on("page",TelegramActionRegistry.Arguments.PAGE,
                (user,action,data)->new TelegramCommands.View("page"));

        String once=routes.button(42,"Do","once","",0,0).data();
        assertEquals("done",routes.dispatch(42,once).view().text());
        assertTrue(routes.dispatch(42,once).alert());
        String expired=routes.button(42,"Page","page","",0,0).data();
        clock.addAndGet(TelegramActionRegistry.TTL_MILLIS+1);
        assertTrue(routes.dispatch(42,expired).alert());

        String oldest=routes.button(42,"Page","page","",0,0).data();
        String latest=oldest;
        for(int i=0;i<TelegramActionRegistry.MAX_PER_USER;i++) latest=routes.button(42,"Page","page","",i,0).data();
        assertEquals(TelegramActionRegistry.MAX_PER_USER,routes.pendingCount());
        assertTrue(routes.dispatch(42,oldest).alert());
        assertEquals("page",routes.dispatch(42,latest).view().text());
    }
}
