package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeEventLoopClient;

/** C11 관측: carrierA 50ms, carrierB 무응답(60초). 1.5초 기다린다. */
public final class C11Probe implements AutoCloseable {

    public final FakeEventLoopClient carrierA = new FakeEventLoopClient("carrier-a", 50);
    public final FakeEventLoopClient carrierB = new FakeEventLoopClient("carrier-b", 60_000);
    public Outcome<String> outcome;

    public Diagnosis run(Function<String, CompletableFuture<String>> cheapest) {
        outcome = Outcome.await(cheapest.apply("o-1"), 1500);
        return new Diagnosis("C11")
                .measured("결과: %s, carrierA 응답 %d건, carrierB 응답 %d건", outcome, carrierA.completedCount(), carrierB.completedCount())
                .completed(outcome.completed() ? 1 : 0, 1, 1500);
    }

    @Override
    public void close() {
        carrierA.close();
        carrierB.close();
    }
}
