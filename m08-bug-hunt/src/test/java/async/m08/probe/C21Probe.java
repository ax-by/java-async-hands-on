package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import async.harness.FakeEventLoopClient;
import async.m08.Symptom;

/** C21 관측: priceClient 200ms. 호출자 A 는 50ms 예산(orTimeout), 직후 호출자 B 는 1초 예산. */
public final class C21Probe implements AutoCloseable {

    public final FakeEventLoopClient priceClient = new FakeEventLoopClient("price", 200);
    public Outcome<String> callerA;
    public Outcome<String> callerB;

    public Diagnosis run(Function<String, CompletableFuture<String>> price) {
        CompletableFuture<String> a = price.apply("sku-1").orTimeout(50, TimeUnit.MILLISECONDS); // 검색 화면의 예산
        CompletableFuture<String> b = price.apply("sku-1");                                       // 상품 상세
        callerB = Outcome.await(b, 1000);
        callerA = Outcome.await(a, 1000);
        return new Diagnosis("C21")
                .measured("호출자 A(50ms 예산): %s", callerA)
                .measured("가격 API 호출 %d회, 응답 도착 %d회", priceClient.requestCount(), priceClient.completedCount())
                .completed((callerA.completed() ? 1 : 0) + (callerB.completed() ? 1 : 0), 2, 1000)
                .flagIf(callerB.failed(), Symptom.SHARED_FUTURE_CORRUPTED,
                        "호출자 B(1초 예산, 가격은 200ms 에 도착)의 결과: %s", callerB);
    }

    @Override
    public void close() {
        priceClient.close();
    }
}
