package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelegramWorkQueueTest {
    @Test void slowUserDoesNotBlockOthersAndUserOrderIsPreserved() throws Exception {
        var firstStarted=new CountDownLatch(1);var release=new CountDownLatch(1);var otherDone=new CountDownLatch(1);
        List<Integer> steps=new CopyOnWriteArrayList<>();
        try(var queue=new TelegramWorkQueue(10,error->fail(error))) {
            queue.submit(1,()->{firstStarted.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}steps.add(1);},()->{});
            assertTrue(firstStarted.await(2,TimeUnit.SECONDS));
            queue.submit(1,()->steps.add(2),()->{});
            queue.submit(2,otherDone::countDown,()->{});
            assertTrue(otherDone.await(2,TimeUnit.SECONDS));assertTrue(steps.isEmpty());
            release.countDown();assertTrue(queue.awaitIdle(2000));assertEquals(List.of(1,2),steps);
        } finally {release.countDown();}
    }
    @Test void journalFailureNeverExecutesActionAndCapacityIsReleased() throws Exception {
        List<Integer> steps=new CopyOnWriteArrayList<>();
        try(var queue=new TelegramWorkQueue(1,error->fail(error))) {
            assertThrows(Exception.class,()->queue.submit(1,()->steps.add(1),()->{throw new Exception("disk");}));
            assertTrue(queue.offer(1,()->steps.add(2)));assertTrue(queue.awaitIdle(2000));
            assertEquals(List.of(2),steps);
        }
    }
    @Test void fullQueueRejectsOptionalWorkAndFailureDoesNotDropNextTask() throws Exception {
        var release=new CountDownLatch(1);var failed=new CountDownLatch(1);var next=new CountDownLatch(1);
        try(var queue=new TelegramWorkQueue(1,error->failed.countDown())) {
            queue.submit(1,()->{try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IllegalStateException();},()->{});
            assertFalse(queue.offer(2,()->{}));release.countDown();assertTrue(failed.await(2,TimeUnit.SECONDS));
            queue.submit(1,next::countDown,()->{});assertTrue(next.await(2,TimeUnit.SECONDS));
        } finally {release.countDown();}
    }
}
