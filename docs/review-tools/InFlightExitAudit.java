import com.objwww.pr.control.alert.application.tool.InFlightToolCancels;
import java.util.UUID;
import java.util.concurrent.*;

/** Offline reproducer of ToolGateway's get/cancel/finally-unregister lifecycle. */
public class InFlightExitAudit {
    public static void main(String[] args) throws Exception {
        var registry = new InFlightToolCancels();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        var run = UUID.randomUUID();
        try {
            Future<?> future = pool.submit(() -> {
                entered.countDown();
                try {
                    boolean done = false;
                    while (!done) {
                        try { release.await(); done = true; }
                        catch (InterruptedException ignored) { /* emulate non-cooperative adapter */ }
                    }
                } finally { exited.countDown(); }
            });
            registry.register(run, future);
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("not started");
            registry.cancelRun(run);
            try { future.get(3, TimeUnit.SECONDS); }
            catch (CancellationException expected) { }
            finally { registry.unregister(run, future); }
            System.out.println("reported_inflight=" + registry.inflightCount(run));
            System.out.println("actual_executor_exited=" + (exited.getCount() == 0));
            if (registry.inflightCount(run) != 0 || exited.getCount() == 0)
                throw new AssertionError("expected lifecycle gap was not reproduced");
            System.out.println("REPRODUCED: wait ended while execution remained alive");
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) pool.shutdownNow();
        }
    }
}
