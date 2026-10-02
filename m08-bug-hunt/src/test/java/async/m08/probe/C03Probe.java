package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;

/** C03 관측: productDb 100, stockDb 120, reviewDb 80, io 8. */
public final class C03Probe implements AutoCloseable {

    public final FakeBlockingService productDb = new FakeBlockingService("product-db", 100);
    public final FakeBlockingService stockDb = new FakeBlockingService("stock-db", 120);
    public final FakeBlockingService reviewDb = new FakeBlockingService("review-db", 80);
    public final NamedPool io = Pools.fixed("io", 8);
    public Outcome<String> outcome;
    public long totalMillis;

    public Diagnosis run(Function<String, CompletableFuture<String>> page) {
        long start = System.nanoTime();
        CompletableFuture<String> f = page.apply("p-1");
        long returned = Probes.since(start);
        outcome = Outcome.await(f, 2000);
        totalMillis = Probes.since(start);
        return new Diagnosis("C03")
                .measured("결과: %s", outcome)
                .callerBlocked(returned)
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(outcome.completed() && totalMillis >= 210, Symptom.SEQUENTIAL_NOT_PARALLEL,
                        "총 %dms (병렬이면 ≈max=120ms, 순차면 ≈합=300ms. 기준 ≥ 210ms)", totalMillis);
    }

    @Override
    public void close() {
        io.close();
    }
}
