package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m08.Symptom;
import async.m08.support.Log;

/** C04 관측: orderDb 50ms 성공, loyaltyDb 50ms 후 실패, io 4. */
public final class C04Probe implements AutoCloseable {

    public final IllegalStateException loyaltyFailure = new IllegalStateException("포인트 DB 장애");
    public final FakeBlockingService orderDb = new FakeBlockingService("order-db", 50);
    public final FakeBlockingService loyaltyDb = new FakeBlockingService("loyalty-db", 50).failOn("o-1", loyaltyFailure);
    public final NamedPool io = Pools.fixed("io", 4);
    public Outcome<String> outcome;

    public Diagnosis run(Function<String, CompletableFuture<String>> place) {
        Log.reset();
        outcome = Outcome.await(place.apply("o-1"), 2000);
        boolean loyaltyRan = Probes.pollUntil(() -> loyaltyDb.callCount() == 1 && loyaltyDb.inFlight() == 0, 1000);
        Sleep.ms(100); // 실패를 관찰하는 콜백(있다면)이 실행될 시간
        boolean logged = loggedWithCause(loyaltyFailure);
        return new Diagnosis("C04")
                .measured("호출자가 받은 결과: %s", outcome)
                .measured("로그: %s", Log.entries())
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(loyaltyRan && !outcome.failed() && !logged, Symptom.EXCEPTION_LOST,
                        "포인트 적립 실패 발생=%s, 호출자가 실패를 봄=%s, 그 예외를 담은 로그=%s",
                        loyaltyRan, outcome.failed(), logged);
    }

    public static boolean loggedWithCause(Throwable target) {
        return Log.entries().stream().anyMatch(e -> e.error() != null && Probes.causeChainContains(e.error(), target));
    }

    @Override
    public void close() {
        io.close();
    }
}
