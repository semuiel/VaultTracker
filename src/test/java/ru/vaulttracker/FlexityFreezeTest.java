package ru.vaulttracker;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlexityFreezeTest {
    public static class Service {
        UUID target,actor;boolean frozen;
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        public CompletableFuture<Boolean> freezePlayer(UUID target,UUID actor) {this.target=target;this.actor=actor;frozen=true;return result;}
        public CompletableFuture<Boolean> unfreezePlayer(UUID target,UUID actor) {this.target=target;this.actor=actor;frozen=false;return CompletableFuture.completedFuture(true);}
    }
    @Test void bridgeUsesExplicitOperationsAndWaitsForFlexityPersistence() throws Exception {
        var service=new Service();var bridge=new FlexityFreeze(service);UUID target=UUID.randomUUID(),actor=UUID.randomUUID();
        var pending=bridge.change(target,actor,true);assertFalse(pending.isDone());assertEquals(target,service.target);assertEquals(actor,service.actor);
        service.result.complete(true);assertTrue(pending.get());assertTrue(service.frozen);
        assertTrue(bridge.change(target,actor,false).get());assertFalse(service.frozen);
    }
    @Test void errorsAreNotReportedAsSuccess() throws Exception {
        var service=new Service();var bridge=new FlexityFreeze(service);var pending=bridge.change(UUID.randomUUID(),UUID.randomUUID(),true);
        service.result.completeExceptionally(new IllegalStateException("disk unavailable"));assertThrows(Exception.class,pending::get);
    }
}
