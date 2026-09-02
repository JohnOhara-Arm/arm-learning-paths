import java.util.concurrent.CountDownLatch;

/** A small workload that is likely to exhibit cache-line false sharing. */
public final class FalseSharingDemo {
    private static final long ITERATIONS = 500_000_000L;

    interface Counters {
        void incrementLeft();
        void incrementRight();
        long sum();
    }

    static final class BaselineCounters implements Counters {
        volatile long left;
        volatile long right;

        public void incrementLeft() { left++; }
        public void incrementRight() { right++; }
        public long sum() { return left + right; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("baseline")) {
            throw new IllegalArgumentException("use: baseline");
        }

        Counters counters = new BaselineCounters();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Thread left = new Thread(
                () -> runWorker(ready, start, counters::incrementLeft),
                "left-writer");
        Thread right = new Thread(
                () -> runWorker(ready, start, counters::incrementRight),
                "right-writer");
        left.start();
        right.start();
        ready.await();
        long begin = System.nanoTime();
        start.countDown();
        left.join();
        right.join();
        double seconds = (System.nanoTime() - begin) / 1_000_000_000.0;
        System.out.printf("mode=baseline seconds=%.6f sum=%d pid=%d%n",
                seconds, counters.sum(), ProcessHandle.current().pid());
    }

    private static void runWorker(
            CountDownLatch ready, CountDownLatch start, Runnable update) {
        try {
            ready.countDown();
            start.await();
            for (long i = 0; i < ITERATIONS; i++) {
                update.run();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }
}
