package async.m08.probe;

import java.util.List;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;

/** C01 관측: 주문 5건, orderDb 100ms, io 8. */
public final class C01Probe implements AutoCloseable {

    public static final List<String> IDS = List.of("o-1", "o-2", "o-3", "o-4", "o-5");

    public final FakeBlockingService orderDb = new FakeBlockingService("order-db", 100);
    public final NamedPool io = Pools.fixed("io", 8);
    public Outcome<List<String>> outcome;

    public Diagnosis run(Function<List<String>, List<String>> recentOrders) {
        outcome = Probes.onRequestThread(() -> recentOrders.apply(IDS), 3000);
        int maxConc = orderDb.maxConcurrency();
        return new Diagnosis("C01")
                .measured("결과: %s", outcome)
                .completed(outcome.completed() ? 1 : 0, 1, 3000)
                .flagIf(outcome.completed() && (maxConc <= 1 || outcome.millis() >= 200), Symptom.SEQUENTIAL_NOT_PARALLEL,
                        "orderDb 최대 동시 호출 %d/5, 총 %dms (병렬이면 ≈100ms, 순차면 ≈500ms)", maxConc, outcome.millis());
    }

    @Override
    public void close() {
        io.close();
    }
}
