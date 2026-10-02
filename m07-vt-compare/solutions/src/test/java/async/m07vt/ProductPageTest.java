package async.m07vt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.FakeBlockingService;
import async.harness.Sleep;
import async.harness.Timed;

/** m07-vt 구현 채점 X1~X3. */
@Timeout(20)
class ProductPageTest {

    static FakeBlockingService svc(String name, long ms) {
        return new FakeBlockingService(name, ms);
    }

    // ── X1 ──

    @Test
    void x1_parallelOnVirtualThreads() throws Exception {
        FakeBlockingService product = svc("product", 200);
        FakeBlockingService price = svc("price", 200);
        FakeBlockingService review = svc("review", 200);
        Timed<String> t = Timed.run(() -> ProductPage.loadWithVirtualThreads(product, price, review, "k"));
        assertThat(t.value()).isEqualTo("product:k|price:k|review:k");
        assertThat(t.millis()).as("세 호출(각 200ms)은 동시에 실행돼야 한다: ≈200ms (R7)").isLessThan(350);
        assertThat(product.maxConcurrency() + price.maxConcurrency() + review.maxConcurrency()).isEqualTo(3);
        assertThat(product.callerThreads()).as("가상 스레드의 이름은 기본적으로 빈 문자열이다 → 가상 스레드에서 실행됐다는 뜻")
                .containsExactly("");
    }

    @Test
    void x1_failureRethrowsOriginalException() {
        IllegalStateException boom = new IllegalStateException("상품 DB 장애");
        FakeBlockingService product = svc("product", 50).failOn("k", boom);
        assertThatThrownBy(() -> ProductPage.loadWithVirtualThreads(product, svc("price", 50), svc("review", 50), "k"))
                .as("ExecutionException 이 아니라 원래 예외를 던져야 한다 (R8 unwrap)")
                .isSameAs(boom);
    }

    // ── X2 ──

    @Test
    void x2_success() throws Exception {
        Timed<String> t = Timed.run(() -> ProductPage.loadStructured(svc("product", 200), svc("price", 200), svc("review", 200),
                "k", Duration.ofSeconds(1)));
        assertThat(t.value()).isEqualTo("product:k|price:k|review:k");
        assertThat(t.millis()).as("≈200ms").isLessThan(350);
    }

    @Test
    void x2_failureCancelsSiblingsImmediately() {
        IllegalStateException boom = new IllegalStateException("상품 DB 장애");
        FakeBlockingService product = svc("product", 50).failOn("k", boom);
        FakeBlockingService price = svc("price", 1000);
        FakeBlockingService review = svc("review", 1000);
        long start = System.nanoTime();
        assertThatThrownBy(() -> ProductPage.loadStructured(product, price, review, "k", Duration.ofSeconds(2)))
                .as("원래 예외를 던져야 한다 (FailedException 의 cause)").isSameAs(boom);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsed).as("가장 느린 형제(1000ms)를 기다리지 않고 ≈50ms 에 끝나야 한다 (VT2)").isLessThan(300);
        Sleep.ms(50);
        assertThat(price.interruptedCount() + review.interruptedCount())
                .as("나머지 두 호출은 interrupt 로 취소돼야 한다 (CF 와의 차이, R10)").isEqualTo(2);
    }

    @Test
    void x2_timeoutCancelsInFlightCalls() {
        FakeBlockingService review = svc("review", 2000);
        long start = System.nanoTime();
        assertThatThrownBy(() -> ProductPage.loadStructured(svc("product", 50), svc("price", 50), review, "k",
                Duration.ofMillis(300)))
                .isInstanceOf(StructuredTaskScope.TimeoutException.class);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsed).as("타임아웃 300ms 근처에서 끝나야 한다").isLessThan(700);
        Sleep.ms(50);
        assertThat(review.interruptedCount()).as("타임아웃 시 진행 중인 호출은 interrupt 된다").isEqualTo(1);
        assertThat(review.inFlight()).isZero();
    }

    // ── X3 ──

    @Test
    void x3_scopedValueReachesEveryCall() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        ProductPage.TracedApi product = traced("product", seen);
        ProductPage.TracedApi price = traced("price", seen);
        ProductPage.TracedApi review = traced("review", seen);
        Timed<String> t = Timed.run(() -> ProductPage.handleRequest("t-1", product, price, review, "k"));
        assertThat(t.value()).isEqualTo("product:k|price:k|review:k");
        assertThat(seen).as("세 API 모두 자기 스레드에서 TRACE=t-1 을 봐야 한다. "
                + "가상 스레드 executor 나 CF 로 넘기면 ScopedValue 는 전달되지 않는다 (VT4)")
                .containsExactlyInAnyOrder("product t-1 virtual", "price t-1 virtual", "review t-1 virtual");
        assertThat(t.millis()).as("동시에 실행돼야 한다: ≈200ms").isLessThan(350);
        assertThat(ProductPage.TRACE.isBound()).as("범위를 벗어나면 바인딩이 풀려야 한다").isFalse();
    }

    static ProductPage.TracedApi traced(String name, List<String> seen) {
        return key -> {
            seen.add(name + " " + (ProductPage.TRACE.isBound() ? ProductPage.TRACE.get() : "UNBOUND")
                    + (Thread.currentThread().isVirtual() ? " virtual" : " platform"));
            Sleep.ms(200);
            return name + ":" + key;
        };
    }
}
