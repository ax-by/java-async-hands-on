package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import async.harness.FakeEventLoopClient;
import async.m08.Symptom;

/** C13 관측: 모두 성공. inventory 50ms, order 50ms, payment(capture) 300ms. */
public final class C13Probe implements AutoCloseable {

    public final FakeEventLoopClient inventoryClient = new FakeEventLoopClient("inventory", 50);
    public final FakeEventLoopClient orderClient = new FakeEventLoopClient("order", 50);
    public final FakeEventLoopClient paymentClient = new FakeEventLoopClient("payment", 300);
    public Outcome<Void> outcome;
    public final AtomicInteger capturesDoneAtCompletion = new AtomicInteger(-1);
    public long completedAtMillis;

    public Diagnosis run(Function<String, CompletableFuture<Void>> fulfill) {
        long start = System.nanoTime();
        CompletableFuture<Void> f = fulfill.apply("o-1");
        // 호출자가 thenRun 으로 출고 메일을 보내는 시점 = 결과 future 가 완료되는 시점
        CompletableFuture<Void> mail = f.whenComplete((v, ex) -> capturesDoneAtCompletion.set(paymentClient.completedCount()));
        outcome = Outcome.await(mail, 2000);
        completedAtMillis = Probes.since(start);
        return new Diagnosis("C13")
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(outcome.completed() && capturesDoneAtCompletion.get() < 1, Symptom.COMPLETES_TOO_EARLY,
                        "결과 future 완료 시점 %dms 에 결제 매입 완료 %d건 (매입은 order 50ms + capture 300ms 뒤에 끝난다)",
                        completedAtMillis, capturesDoneAtCompletion.get());
    }

    @Override
    public void close() {
        inventoryClient.close();
        orderClient.close();
        paymentClient.close();
    }
}
