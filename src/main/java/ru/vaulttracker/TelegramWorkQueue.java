package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded work, ordered per user; independent users never wait for one user's HTTP call. */
final class TelegramWorkQueue implements AutoCloseable {
    @FunctionalInterface interface BeforeEnqueue {void run() throws Exception;}
    private final Map<Long,ArrayDeque<Runnable>> queues=new HashMap<>();
    private final Semaphore capacity;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Consumer<Throwable> failures;
    private boolean closed;
    TelegramWorkQueue(int limit,Consumer<Throwable> failures) {capacity=new Semaphore(limit);this.failures=failures;}

    void submit(long user,Runnable task,BeforeEnqueue before) throws Exception {
        capacity.acquire();
        boolean accepted=false;
        try {
            synchronized(this) {
                if(closed) throw new RejectedExecutionException("Telegram queue is closed");
                before.run();
                enqueue(user,task);accepted=true;
            }
        } finally {if(!accepted) capacity.release();}
    }
    synchronized boolean offer(long user,Runnable task) {
        if(closed || !capacity.tryAcquire()) return false;
        enqueue(user,task);return true;
    }
    private void enqueue(long user,Runnable task) {
        ArrayDeque<Runnable> queue=queues.get(user);
        if(queue==null) {
            queue=new ArrayDeque<>();queue.add(task);queues.put(user,queue);
            workers.execute(()->drain(user));
        } else queue.add(task);
    }
    private void drain(long user) {
        while(true) {
            Runnable task;
            synchronized(this) {
                var queue=queues.get(user);
                if(queue==null || queue.isEmpty()) {queues.remove(user);notifyAll();return;}
                task=queue.remove();
            }
            try {task.run();}
            catch(Throwable failure) {failures.accept(failure);}
            finally {capacity.release();}
        }
    }
    synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(!queues.isEmpty()) {
            long remaining=end-System.nanoTime();if(remaining<=0) return false;
            TimeUnit.NANOSECONDS.timedWait(this,remaining);
        }
        return true;
    }
    @Override public void close() {
        synchronized(this) {closed=true;}
        workers.shutdown();
        try {if(!workers.awaitTermination(5,TimeUnit.SECONDS)) {
            synchronized(this) {
                int abandoned=queues.values().stream().mapToInt(ArrayDeque::size).sum();
                queues.values().forEach(ArrayDeque::clear);capacity.release(abandoned);
                if(abandoned>0) failures.accept(new IllegalStateException("Telegram stopped with "+abandoned+" queued updates; users must retry unfinished actions"));
            }
            workers.shutdownNow();
        }}
        catch(InterruptedException interrupted) {workers.shutdownNow();Thread.currentThread().interrupt();}
    }
}
