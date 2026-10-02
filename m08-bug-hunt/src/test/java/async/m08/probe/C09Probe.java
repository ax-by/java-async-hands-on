package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeEventLoopClient;
import async.m08.Symptom;
import async.m08.domain.CardDeclinedException;

/** C09 관측: PG 가 40ms 후 CardDeclinedException 으로 future 를 직접 실패시킨다. */
public final class C09Probe implements AutoCloseable {

    public final IllegalStateException outage = new IllegalStateException("PG 장애");
    public final FakeEventLoopClient paymentGateway = new FakeEventLoopClient("pg", 40)
            .failOn("o-1", new CardDeclinedException("한도 초과"))
            .failOn("o-3", outage);
    public Outcome<String> outcome;

    public Diagnosis run(Function<String, CompletableFuture<String>> pay) {
        outcome = Outcome.await(pay.apply("o-1"), 2000);
        return new Diagnosis("C09")
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(outcome.failed(), Symptom.FALLBACK_NOT_APPLIED,
                        "카드 거절을 주입했을 때 호출자가 받은 결과: %s", outcome);
    }

    @Override
    public void close() {
        paymentGateway.close();
    }
}
