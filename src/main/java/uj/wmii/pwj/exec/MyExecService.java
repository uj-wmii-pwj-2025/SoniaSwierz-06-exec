package uj.wmii.pwj.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public class MyExecService implements ExecutorService {

    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final Thread workerThread;

    private volatile boolean isShutdown = false;
    private volatile boolean isTerminated = false;

    private MyExecService() {
        this.workerThread = new Thread(this::work, "MyExec-Worker");
        this.workerThread.start();
    }

    static MyExecService newInstance() {
        return new MyExecService();
    }

    private void work() {
        try {
            while (true) {
                if (isShutdown && taskQueue.isEmpty()) {
                    break;
                }

                try {
                    Runnable task;
                    if (isShutdown) {
                        task = taskQueue.poll();
                        if (task == null)
                            break;
                    } else {
                        task = taskQueue.take();
                    }
                    task.run();
                } catch (InterruptedException e) {
                    break;
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        } finally {
            isTerminated = true;
        }
    }

    @Override
    public void execute(Runnable command) {
        if (isShutdown) {
            throw new RejectedExecutionException("Executor is shut down");
        }
        if (command == null)
            throw new NullPointerException();

        if (!taskQueue.offer(command)) {
            throw new RejectedExecutionException("Task queue is full!");
        }
    }

    @Override
    public void shutdown() {
        isShutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        isShutdown = true;
        workerThread.interrupt();
        List<Runnable> remaining = new ArrayList<>();
        taskQueue.drainTo(remaining);
        return remaining;
    }

    @Override
    public boolean isShutdown() {
        return isShutdown;
    }

    @Override
    public boolean isTerminated() {
        return isTerminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (isTerminated)
            return true;

        long millis = unit.toMillis(timeout);
        if (millis <= 0)
            return isTerminated;

        workerThread.join(millis);

        return isTerminated;
    }


    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (task == null)
            throw new NullPointerException();

        RunnableFuture<T> futureTask = new FutureTask<>(task);
        execute(futureTask);

        return futureTask;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        if (task == null)
            throw new NullPointerException();

        RunnableFuture<T> futureTask = new FutureTask<>(task, result);
        execute(futureTask);

        return futureTask;
    }

    @Override
    public Future<?> submit(Runnable task) {
        return submit(task, null);
    }


    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();

        List<Future<T>> futures = new ArrayList<>(tasks.size());

        boolean done = false;
        try {
            for (Callable<T> t : tasks) {
                RunnableFuture<T> f = new FutureTask<>(t);
                futures.add(f);
                execute(f);
            }
            for (Future<T> f : futures) {
                if (!f.isDone()) {
                    try {
                        f.get();
                    } catch (ExecutionException | CancellationException ignore) {}
                }
            }
            done = true;
            return futures;
        } finally {
            if (!done) {
                for (Future<T> f : futures)
                    f.cancel(true);
            }
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();

        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        List<Future<T>> futures = new ArrayList<>(tasks.size());

        try {
            for (Callable<T> t : tasks) {
                futures.add(new FutureTask<>(t));
            }

            for (Future<T> f : futures) {
                if (System.nanoTime() - deadline >= 0)
                    return futures;

                execute((Runnable) f);
            }

            for (Future<T> f : futures) {
                if (!f.isDone()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0)
                        return futures;
                    try {
                        f.get(remaining, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException | CancellationException | ExecutionException ignore) {
                    }
                }
            }
            return futures;
        } finally {
            for (Future<T> f : futures) {
                if (!f.isDone())
                    f.cancel(true);
            }
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        try {
            return doInvokeAny(tasks, false, 0);
        } catch (TimeoutException cannotHappen) {
            return null;
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return doInvokeAny(tasks, true, unit.toNanos(timeout));
    }

    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks, boolean timed, long nanos) throws InterruptedException, ExecutionException, TimeoutException {
        if (tasks == null)
            throw new NullPointerException();
        if (tasks.isEmpty())
            throw new IllegalArgumentException();

        List<Future<T>> futures = new ArrayList<>(tasks.size());
        ExecutionException lastException = null;
        long deadline = timed ? System.nanoTime() + nanos : 0L;

        try {
            for (Callable<T> task : tasks) {
                if (timed && System.nanoTime() - deadline >= 0)
                    throw new TimeoutException();

                Future<T> f = submit(task);
                futures.add(f);

                try {
                    if (timed) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0)
                            throw new TimeoutException();
                        return f.get(remaining, TimeUnit.NANOSECONDS);
                    } else {
                        return f.get();
                    }
                } catch (ExecutionException e) {
                    lastException = e;
                }
            }

            if (lastException == null)
                throw new ExecutionException(new RuntimeException("No successful result"));
            throw lastException;
        } finally {
            for (Future<T> f : futures)
                f.cancel(true);
        }
    }
}