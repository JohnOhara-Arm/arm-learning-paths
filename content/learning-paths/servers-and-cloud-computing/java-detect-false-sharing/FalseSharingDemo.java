import java.util.concurrent.CountDownLatch;
import jdk.internal.vm.annotation.Contended;

/** Positive and negative controls for cache-line false sharing. */
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

    static final class PaddedCounters implements Counters {
        @Contended("left") volatile long left;
        @Contended("right") volatile long right;

        public void incrementLeft() { left++; }
        public void incrementRight() { right++; }
        public long sum() { return left + right; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 ||
                !(args[0].equals("baseline") || args[0].equals("padded"))) {
            throw new IllegalArgumentException("use: baseline | padded");
        }
        Counters counters = args[0].equals("baseline")
                ? new BaselineCounters() : new PaddedCounters();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Thread left = new Thread(() -> runWorker(ready, start, counters::incrementLeft), "left-writer");
        Thread right = new Thread(() -> runWorker(ready, start, counters::incrementRight), "right-writer");
        left.start();
        right.start();
        ready.await();
        long begin = System.nanoTime();
        start.countDown();
        left.join();
        right.join();
        double seconds = (System.nanoTime() - begin) / 1_000_000_000.0;
        System.out.printf("mode=%s seconds=%.6f sum=%d pid=%d%n",
                args[0], seconds, counters.sum(), ProcessHandle.current().pid());
    }

    private static void runWorker(CountDownLatch ready, CountDownLatch start, Runnable update) {
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
