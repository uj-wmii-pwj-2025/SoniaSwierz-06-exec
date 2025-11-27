package uj.wmii.pwj.exec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ExecServiceTest {

    @Test
    void testExecute() {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r = new TestRunnable();

        s.execute(r);

        doSleep(10);
        assertTrue(r.wasRun);
    }

    @Test
    void testScheduleRunnable() {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r = new TestRunnable();

        s.submit(r);

        doSleep(10);
        assertTrue(r.wasRun);
    }

    @Test
    void testScheduleRunnableWithResult() throws Exception {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r = new TestRunnable();
        Object expected = new Object();

        Future<Object> f = s.submit(r, expected);
        doSleep(10);

        assertTrue(r.wasRun);
        assertTrue(f.isDone());
        assertEquals(expected, f.get());
    }

    @Test
    void testScheduleCallable() throws Exception {
        MyExecService s = MyExecService.newInstance();
        Callable<String> c = () -> {
            Thread.sleep(10);
            return "X";
        };

        Future<String> f = s.submit(c);
        String result = f.get();

        assertTrue(f.isDone());
        assertEquals("X", result);
    }

    @Test
    void testShutdown() {
        ExecutorService s = MyExecService.newInstance();
        s.execute(new TestRunnable());
        doSleep(10);
        s.shutdown();
        assertThrows(
                RejectedExecutionException.class,
                () -> s.submit(new TestRunnable()));
    }

    @Test
    void testShutdownNow() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();

        CountDownLatch workerStarted = new CountDownLatch(1);

        s.execute(() -> {
            workerStarted.countDown();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        });

        TestRunnable r1 = new TestRunnable();
        TestRunnable r2 = new TestRunnable();
        s.execute(r1);
        s.execute(r2);

        workerStarted.await();

        List<Runnable> notExecuted = s.shutdownNow();

        assertEquals(2, notExecuted.size());
        assertTrue(s.isShutdown());

        s.awaitTermination(1, TimeUnit.SECONDS);
        assertTrue(s.isTerminated());

        assertFalse(r1.wasRun);
        assertFalse(r2.wasRun);
    }

    @Test
    void testAwaitTermination() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        s.execute(() -> doSleep(300));
        s.shutdown();

        boolean resultTooEarly = s.awaitTermination(50, TimeUnit.MILLISECONDS);
        assertFalse(resultTooEarly);
        assertFalse(s.isTerminated());

        boolean resultInTime = s.awaitTermination(1, TimeUnit.SECONDS);
        assertTrue(resultInTime);
        assertTrue(s.isTerminated());
    }

    @Test
    void testInvokeAll() throws InterruptedException, ExecutionException {
        ExecutorService s = MyExecService.newInstance();
        List<Callable<Integer>> tasks = Arrays.asList(
                () -> 10,
                () -> 20,
                () -> 30
        );
        List<Future<Integer>> futures = s.invokeAll(tasks);
        assertEquals(3, futures.size());
        assertEquals(10, futures.get(0).get());
        assertEquals(20, futures.get(1).get());
        assertEquals(30, futures.get(2).get());
        assertTrue(futures.get(0).isDone());
        assertTrue(futures.get(1).isDone());
        assertTrue(futures.get(2).isDone());
        s.shutdown();
    }

    @Test
    void testInvokeAllWithTimeout() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        Callable<String> fast = () -> "Fast";
        Callable<String> slow = () -> {
            Thread.sleep(2000);
            return "Slow";
        };
        List<Callable<String>> tasks = Arrays.asList(fast, slow);

        long start = System.currentTimeMillis();
        List<Future<String>> futures = s.invokeAll(tasks, 300, TimeUnit.MILLISECONDS);
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration < 600);
        assertEquals(2, futures.size());
        assertTrue(futures.get(1).isCancelled());

        try {
            futures.get(1).get();
            fail();
        } catch (CancellationException | ExecutionException expected) {
        }
        s.shutdownNow();
    }

    @Test
    void testInvokeAny() throws InterruptedException, ExecutionException {
        ExecutorService s = MyExecService.newInstance();
        List<Callable<String>> tasks = Arrays.asList(
                () -> "A",
                () -> "B"
        );
        String result = s.invokeAny(tasks);
        assertTrue(result.equals("A") || result.equals("B"));
        s.shutdown();
    }

    @Test
    void testInvokeAnyWithException() throws InterruptedException, ExecutionException {
        ExecutorService s = MyExecService.newInstance();
        List<Callable<String>> tasks = Arrays.asList(
                () -> { throw new RuntimeException("Fail"); },
                () -> "Success"
        );
        String result = s.invokeAny(tasks);
        assertEquals("Success", result);
        s.shutdown();
    }

    @Test
    void testInvokeAnyAllFail() {
        ExecutorService s = MyExecService.newInstance();
        List<Callable<String>> tasks = Arrays.asList(
                () -> { throw new RuntimeException("E1"); },
                () -> { throw new IOException("E2"); }
        );
        assertThrows(ExecutionException.class, () -> s.invokeAny(tasks));
        s.shutdown();
    }

    @Test
    void testInvokeAnyTimeout() {
        ExecutorService s = MyExecService.newInstance();
        List<Callable<String>> tasks = Collections.singletonList(
                () -> {
                    Thread.sleep(500);
                    return "Late";
                }
        );
        assertThrows(TimeoutException.class, () ->
                s.invokeAny(tasks, 100, TimeUnit.MILLISECONDS));
        s.shutdownNow();
    }

    @Test
    void testSubmitExceptionPropagation() {
        ExecutorService s = MyExecService.newInstance();
        Future<Object> future = s.submit(() -> {
            throw new IllegalArgumentException("Boom");
        });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertEquals("Boom", ex.getCause().getMessage());
        s.shutdown();
    }

    @Test
    void testNullChecks() {
        ExecutorService s = MyExecService.newInstance();
        assertThrows(NullPointerException.class, () -> s.execute(null));
        assertThrows(NullPointerException.class, () -> s.submit((Runnable) null));
        assertThrows(NullPointerException.class, () -> s.submit((Callable<?>) null));
        assertThrows(NullPointerException.class, () -> s.invokeAll(null));
        assertThrows(NullPointerException.class, () -> s.invokeAny(null));
        s.shutdown();
    }

    @Test
    void testIsShutdownAndTerminated() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        assertFalse(s.isShutdown());
        assertFalse(s.isTerminated());

        s.execute(() -> doSleep(50));
        s.shutdown();

        assertTrue(s.isShutdown());
        assertFalse(s.isTerminated());

        s.awaitTermination(200, TimeUnit.MILLISECONDS);
        assertTrue(s.isTerminated());
    }

    @Test
    void testCancelFuture() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        CountDownLatch latch = new CountDownLatch(1);
        s.execute(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {}
        });

        AtomicBoolean wasRun = new AtomicBoolean(false);
        Future<?> f = s.submit(() -> wasRun.set(true));

        boolean cancelled = f.cancel(false);
        assertTrue(cancelled);
        assertTrue(f.isCancelled());
        assertTrue(f.isDone());

        latch.countDown();
        s.shutdown();
        s.awaitTermination(1, TimeUnit.SECONDS);
        assertFalse(wasRun.get());
    }

    @Test
    void testMultipleShutdowns() {
        ExecutorService s = MyExecService.newInstance();
        s.shutdown();
        s.shutdown();
        assertTrue(s.isShutdown());
    }

    @Test
    void testGetWithTimeout() throws Exception {
        ExecutorService s = MyExecService.newInstance();
        CountDownLatch latch = new CountDownLatch(1);
        Future<String> f = s.submit(() -> {
            latch.await();
            return "OK";
        });

        assertThrows(TimeoutException.class, () -> f.get(50, TimeUnit.MILLISECONDS));
        latch.countDown();
        assertEquals("OK", f.get(1, TimeUnit.SECONDS));
        s.shutdown();
    }

    @Test
    void testSequentialOrder() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        s.execute(() -> {
            doSleep(50);
            results.add(1);
            latch.countDown();
        });
        s.execute(() -> {
            results.add(2);
            latch.countDown();
        });
        s.execute(() -> {
            results.add(3);
            latch.countDown();
        });

        latch.await(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(1, 2, 3), results);
        s.shutdown();
    }

    @Test
    void testSubmitRunnableReturnsNull() throws Exception {
        ExecutorService s = MyExecService.newInstance();
        Future<?> f = s.submit(() -> {});
        assertNull(f.get());
        assertTrue(f.isDone());
        s.shutdown();
    }

    @Test
    void testEmptyInvokeAll() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        List<Future<Object>> list = s.invokeAll(Collections.emptyList());
        assertTrue(list.isEmpty());
        s.shutdown();
    }

    @Test
    void testEmptyInvokeAny() {
        ExecutorService s = MyExecService.newInstance();
        assertThrows(IllegalArgumentException.class, () -> s.invokeAny(Collections.emptyList()));
        s.shutdown();
    }

    @Test
    void testWorkerThreadRecovery() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        s.execute(() -> {
            throw new RuntimeException("Crash");
        });
        doSleep(50);
        CountDownLatch latch = new CountDownLatch(1);
        s.execute(latch::countDown);
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        s.shutdown();
    }

    @Test
    void testRejectedAfterShutdownNow() {
        ExecutorService s = MyExecService.newInstance();
        s.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> s.execute(() -> {}));
    }

    @Test
    void testShutdownNowInterruptsRunningTask() throws InterruptedException {
        ExecutorService s = MyExecService.newInstance();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        s.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });

        started.await();
        s.shutdownNow();
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    static void doSleep(int milis) {
        try {
            Thread.sleep(milis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}

class StringCallable implements Callable<String> {

    private final String result;
    private final int milis;

    StringCallable(String result, int milis) {
        this.result = result;
        this.milis = milis;
    }

    @Override
    public String call() throws Exception {
        ExecServiceTest.doSleep(milis);
        return result;
    }
}
class TestRunnable implements Runnable {

    boolean wasRun;
    @Override
    public void run() {
        wasRun = true;
    }
}