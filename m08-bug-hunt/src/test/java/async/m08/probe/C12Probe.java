package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m08.Symptom;
import async.m08.support.Log;

/** C12 관측: orderDb 50ms 성공, analytics 200ms 후 실패, io 4. */
public final class C12Probe implements AutoCloseable {

    public final IllegalStateException analyticsFailure = new IllegalStateException("analytics 503");
    public final FakeBlockingService orderDb = new FakeBlockingService("order-db", 50);
    public final FakeEventLoopClient analyticsClient = new FakeEventLoopClient("analytics", 200)
            .failOn("order-placed:o-1", analyticsFailure);
    public final NamedPool io = Pools.fixed("io", 4);
    public Outcome<String> outcome;

    public Diagnosis run(Function<String, CompletableFuture<String>> place) {
        Log.reset();
        long start = System.nanoTime();
        CompletableFuture<String> f = place.apply("o-1");
        long returned = Probes.since(start);
        outcome = Outcome.await(f, 2000);
        boolean analyticsDone = Probes.pollUntil(() -> analyticsClient.completedCount() >= 1, 1000);
        Sleep.ms(100);
        boolean logged = C04Probe.loggedWithCause(analyticsFailure);
        return new Diagnosis("C12")
                .measured("호출자가 받은 결과: %s (analytics 200ms 를 기다리지 않아야 한다)", outcome)
                .measured("로그: %s", Log.entries())
                .callerBlocked(returned)
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(analyticsDone && !outcome.failed() && !logged, Symptom.EXCEPTION_LOST,
                        "analytics 실패 발생=%s, 호출자가 실패를 봄=%s, 그 예외를 담은 로그=%s", analyticsDone, outcome.failed(), logged);
    }

    @Override
    public void close() {
        analyticsClient.close();
        io.close();
    }
}
