package async.m05;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Timed;

/** E1, E2 채점. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AggregatorTest {

    ProductBackends backends;
    NamedPool pool;
    TimingExecutor io;
    ProductDetailAggregator aggregator;

    @BeforeEach
    void setUp() {
        backends = new ProductBackends();
        pool = Pools.fixed("io", 16);
        io = new TimingExecutor(pool);
        aggregator = new ProductDetailAggregator(backends, io);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    /** future 가 실패한 시점과 그 원인(ExecutionException 을 벗긴 것)을 돌려준다. */
    private static Failure failureOf(CompletableFuture<ProductDetail> f, long startNanos) {
        Throwable t = catchThrowable(() -> f.get(2, TimeUnit.SECONDS));
        long at = (System.nanoTime() - startNanos) / 1_000_000;
        assertThat(t).as("future 가 실패해야 한다 (2초 안에)").isNotNull();
        assertThat(t).as("2초 안에 끝나지 않았다. 전체 타임아웃(orTimeout)이 없는가? (R10)").isNotInstanceOf(TimeoutException.class);
        assertThat(t).isInstanceOf(ExecutionException.class);
        return new Failure(t.getCause(), at);
    }

    private record Failure(Throwable cause, long atMillis) {
    }

    private void assertDomainException(Failure failure, ProductDetailException.Reason reason) {
        assertThat(failure.cause())
                .as("future 는 ProductDetailException 으로 실패해야 한다. 실제: %s (R8: 감싸진 예외를 unwrap 했는가?)", failure.cause())
                .isInstanceOf(ProductDetailException.class);
        assertThat(((ProductDetailException) failure.cause()).reason()).isEqualTo(reason);
    }

    // ---------------------------------------------------------------- E1

    @Test
    void e1_returnsImmediately_and_totalIsMaxNotSum() throws Exception {
        long start = System.nanoTime();
        Timed<CompletableFuture<ProductDetail>> call = Timed.of(() -> aggregator.load("p1"));
        assertThat(call.millis())
                .as("load 는 요청 스레드를 막지 않고 즉시 future 를 반환해야 한다 (R3, R5). 안에서 join/get 하고 있지 않은가?")
                .isLessThan(30);
        ProductDetail d = call.value().get(2, TimeUnit.SECONDS);
        long total = (System.nanoTime() - start) / 1_000_000;

        assertThat(d).isEqualTo(new ProductDetail("product-db:p1", "price-api:p1", "stock-api:p1",
                List.of("review-db:p1#1", "review-db:p1#2")));
        assertThat(total).as("총 시간은 네 지연의 합이 아니라 가장 느린 호출 근처여야 한다. 합에 가깝다면 순차다 (R7)").isBetween(140L, 260L);
        assertThat(backends.maxTotalConcurrency()).as("네 호출이 동시에 진행돼야 한다 (R7: 먼저 모두 시작)").isEqualTo(4);
    }

    @Test
    void e1_allDownstreamCallsRunOnTheDedicatedPool() throws Exception {
        aggregator.load("p1").get(2, TimeUnit.SECONDS);
        for (var svc : List.of(backends.productDb(), backends.priceApi(), backends.stockApi(), backends.reviewDb())) {
            assertThat(svc.callerThreads())
                    .as("%s 호출은 io 풀에서 실행돼야 한다. commonPool(R2)이나 요청 스레드(R3)에서 blocking 하면 안 된다", svc.name())
                    .isNotEmpty()
                    .allMatch(t -> t.startsWith("io-"));
        }
    }

    @Test
    void e1_optionalFailures_fallBack() throws Exception {
        backends.stockApi().failOn("p1", new IllegalStateException("stock 503"));
        backends.reviewDb().failOn("p1", new IllegalStateException("review db down"));
        ProductDetail d = aggregator.load("p1").get(2, TimeUnit.SECONDS);
        assertThat(d.stock()).as("선택 소스 stock 실패 → UNKNOWN 폴백 (R9)").isEqualTo(ProductDetail.UNKNOWN_STOCK);
        assertThat(d.reviews()).as("선택 소스 reviews 실패 → 빈 목록 폴백 (R9)").isEmpty();
        assertThat(d.product()).isEqualTo("product-db:p1");
        assertThat(d.price()).isEqualTo("price-api:p1");
    }

    @Test
    void e1_requiredPriceFailure_domainException_withOriginalCause() {
        IllegalStateException original = new IllegalStateException("price api 500");
        backends.priceApi().failOn("p1", original);
        long start = System.nanoTime();
        Failure failure = failureOf(aggregator.load("p1"), start);
        assertDomainException(failure, ProductDetailException.Reason.PRICE_UNAVAILABLE);
        assertThat(failure.cause().getCause())
                .as("도메인 예외의 cause 는 원래 예외여야 한다. CompletionException 껍데기를 그대로 넣지 않았는가? (R8)")
                .isSameAs(original);
        assertThat(failure.atMillis()).as("필수 소스 실패는 타임아웃(300ms)을 기다리지 않고 알려져야 한다").isLessThan(280);
    }

    @Test
    void e1_requiredProductFailure_domainException() {
        IllegalArgumentException original = new IllegalArgumentException("no such product");
        backends.productDb().failOn("p1", original);
        Failure failure = failureOf(aggregator.load("p1"), System.nanoTime());
        assertDomainException(failure, ProductDetailException.Reason.PRODUCT_UNAVAILABLE);
        assertThat(failure.cause().getCause()).as("cause 는 unwrap 한 원래 예외 (R8)").isSameAs(original);
    }

    @Test
    void e1_overallTimeout_whenPriceIsSlow() {
        backends.priceApi().latencyFor("p1", 1000);
        long start = System.nanoTime();
        Timed<CompletableFuture<ProductDetail>> call = Timed.of(() -> aggregator.load("p1"));
        assertThat(call.millis()).as("느린 소스가 있어도 load 는 즉시 반환해야 한다").isLessThan(30);
        Failure failure = failureOf(call.value(), start);

        assertDomainException(failure, ProductDetailException.Reason.TIMEOUT);
        assertThat(failure.cause().getCause()).as("TIMEOUT 의 cause 는 TimeoutException (R8, R10)").isInstanceOf(TimeoutException.class);
        assertThat(failure.atMillis())
                .as("전체 타임아웃 300ms 에 실패해야 한다. 1000ms 근처면 타임아웃이 없다 (R10)").isBetween(280L, 450L);
        assertThat(backends.productDb().inFlight() + backends.stockApi().inFlight() + backends.reviewDb().inFlight())
                .as("타임아웃 시점에 나머지 호출은 이미 끝났어야 한다 (병렬이므로)").isZero();
        // R10: 타임아웃 뒤에도 늦은 호출이 새지 않고 결국 끝나는지 확인한다.
        await().atMost(Duration.ofMillis(1500)).untilAsserted(() ->
                assertThat(backends.inFlight()).as("늦은 호출도 결국 끝나야 한다 (새는 작업 없음)").isZero());
        assertThat(backends.priceApi().callCount()).as("타임아웃 후 재시도 등으로 호출이 늘어나면 안 된다").isEqualTo(1);
    }

    @Test
    void e1_noThreadIsHeldJustToWait() {
        backends.priceApi().latencyFor("p1", 1000);
        catchThrowable(() -> aggregator.load("p1").get(2, TimeUnit.SECONDS));
        await().atMost(Duration.ofMillis(1500)).until(() -> backends.inFlight() == 0);
        await().atMost(Duration.ofMillis(500)).until(() -> pool.getActiveCount() == 0);
        assertThat(io.tasksLongerThan(50))
                .as("io 스레드를 50ms 이상 점유한 작업은 다운스트림 호출 4개뿐이어야 한다. 실제 작업 시간: %s. "
                        + "풀 안에서 다른 future 를 get/join 으로 기다리는 작업이 있는가? (R4, R11)", io.durations())
                .isEqualTo(4);
    }

    // ---------------------------------------------------------------- E2

    @Test
    void e2_slowReviews_fallBackAt200ms_withoutFailingTheRequest() throws Exception {
        backends.reviewDb().latencyFor("p1", 1000);
        long start = System.nanoTime();
        Timed<CompletableFuture<ProductDetail>> call = Timed.of(() -> aggregator.loadWithSourceTimeouts("p1"));
        assertThat(call.millis()).as("즉시 반환해야 한다 (R3)").isLessThan(30);
        ProductDetail d = call.value().get(2, TimeUnit.SECONDS);
        long took = (System.nanoTime() - start) / 1_000_000;
        assertThat(d.reviews()).as("리뷰가 200ms 안에 오지 않으면 빈 목록으로 폴백한다").isEmpty();
        assertThat(d.stock()).isEqualTo("stock-api:p1");
        assertThat(took).as("선택 소스 타임아웃 200ms 시점에 응답해야 한다 (E1 만으로는 300ms 에 요청 전체가 실패한다)")
                .isBetween(180L, 290L);
    }

    @Test
    void e2_slowStock_fallsBackToUnknown() throws Exception {
        backends.stockApi().latencyFor("p1", 1000);
        ProductDetail d = aggregator.loadWithSourceTimeouts("p1").get(2, TimeUnit.SECONDS);
        assertThat(d.stock()).isEqualTo(ProductDetail.UNKNOWN_STOCK);
        assertThat(d.reviews()).hasSize(2);
    }

    @Test
    void e2_optionalFailureStillFallsBack_andNormalPathUnchanged() throws Exception {
        backends.stockApi().failOn("p1", new IllegalStateException("stock 503"));
        long start = System.nanoTime();
        ProductDetail d = aggregator.loadWithSourceTimeouts("p1").get(2, TimeUnit.SECONDS);
        long took = (System.nanoTime() - start) / 1_000_000;
        assertThat(d.stock()).as("타임아웃뿐 아니라 실패도 폴백 (R9)").isEqualTo(ProductDetail.UNKNOWN_STOCK);
        assertThat(d.reviews()).hasSize(2);
        assertThat(took).as("평소 응답시간은 E1 과 같아야 한다 (R7)").isLessThan(260);
    }

    @Test
    void e2_requiredSourceStillUsesOverallTimeout() {
        backends.priceApi().latencyFor("p1", 1000);
        long start = System.nanoTime();
        Failure failure = failureOf(aggregator.loadWithSourceTimeouts("p1"), start);
        assertDomainException(failure, ProductDetailException.Reason.TIMEOUT);
        assertThat(failure.atMillis()).as("필수 소스는 폴백하지 않고 전체 타임아웃 300ms 에 실패").isBetween(280L, 450L);
    }

    @Test
    void e2_sourceTimeoutDoesNotHoldExtraThreads() throws Exception {
        backends.reviewDb().latencyFor("p1", 1000);
        backends.stockApi().latencyFor("p1", 1000);
        aggregator.loadWithSourceTimeouts("p1").get(2, TimeUnit.SECONDS);
        await().atMost(Duration.ofMillis(1500)).until(() -> backends.inFlight() == 0);
        await().atMost(Duration.ofMillis(500)).until(() -> pool.getActiveCount() == 0);
        assertThat(io.tasksLongerThan(50))
                .as("io 스레드를 오래 점유한 작업은 다운스트림 호출 4개뿐이어야 한다. 실제: %s. "
                        + "get(200, ms) 로 기다리는 작업을 풀에 넣으면 그 스레드가 200ms 동안 놀면서 막힌다. "
                        + "completeOnTimeout/orTimeout 은 스레드를 점유하지 않는다 (R10)", io.durations())
                .isEqualTo(4);
    }

    @Test
    void unwrapSanity_completionExceptionIsNotADomainException() {
        // 학습자에게: get() 은 ExecutionException, join() 은 CompletionException 으로 한 번 더 감싼다 (R8).
        backends.priceApi().failOn("p1", new IllegalStateException("boom"));
        Throwable viaJoin = catchThrowable(() -> aggregator.load("p1").join());
        assertThat(viaJoin).isInstanceOf(CompletionException.class);
        assertThat(viaJoin.getCause()).as("join() 으로 받아도 껍데기 하나만 벗기면 도메인 예외여야 한다")
                .isInstanceOf(ProductDetailException.class);
    }
}
