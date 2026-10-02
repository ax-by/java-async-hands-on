package async.m02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.harness.Timed;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ExercisesTest {

    private static Throwable root(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    // ---------------------------------------------------------------- E1 sequence

    @Test
    void e1_sequence_keepsInputOrder_evenWhenCompletedInReverse() throws Exception {
        List<CompletableFuture<String>> inputs = List.of(new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        Timed<CompletableFuture<List<String>>> call = Timed.of(() -> Exercises.sequence(inputs));
        assertThat(call.millis()).as("E1: 입력이 미완료여도 즉시 반환해야 한다 (R5: 메서드 안에서 join 하지 마라)").isLessThan(50);
        assertThat(call.value()).as("E1: 입력이 모두 끝나기 전에는 결과도 미완료여야 한다").isNotDone();
        inputs.get(2).complete("c");
        inputs.get(1).complete("b");
        assertThat(call.value()).as("E1: 하나라도 남아 있으면 미완료여야 한다 (allOf)").isNotDone();
        inputs.get(0).complete("a");
        assertThat(call.value().get(1, TimeUnit.SECONDS))
                .as("E1: 결과 순서는 완료 순서가 아니라 입력 순서여야 한다").containsExactly("a", "b", "c");
    }

    @Test
    void e1_sequence_failsWhenAnyInputFails() {
        IllegalStateException boom = new IllegalStateException("boom");
        CompletableFuture<String> failed = new CompletableFuture<>();
        CompletableFuture<List<String>> result = Exercises.sequence(List.of(CompletableFuture.completedFuture("a"), failed));
        failed.completeExceptionally(boom);
        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .as("E1: 입력 하나가 실패하면 결과도 그 예외로 실패해야 한다 (R8)")
                .isInstanceOf(ExecutionException.class)
                .hasRootCause(boom);
    }

    @Test
    void e1_sequence_emptyList() throws Exception {
        assertThat(Exercises.<String>sequence(List.of()).get(1, TimeUnit.SECONDS))
                .as("E1: 빈 입력이면 빈 리스트로 완료돼야 한다").isEmpty();
    }

    // ---------------------------------------------------------------- E2 fetchPrices

    @Test
    void e2_fetchPrices_returnsImmediately_andRunsInParallel() {
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 100).latencyFor("p1", 200);
        List<String> skus = List.of("p1", "p2", "p3", "p4", "p5", "p6");
        try (NamedPool io = Pools.fixed("io", 6)) {
            io.prestartAllCoreThreads();
            long start = System.nanoTime();
            Timed<CompletableFuture<List<String>>> call = Timed.of(() -> Exercises.fetchPrices(priceApi, skus, io));
            assertThat(call.millis()).as("E2: 호출 즉시 future 를 반환해야 한다 (루프 안 join 은 호출 스레드를 막는다, R5)").isLessThan(50);
            List<String> prices = call.value().join();
            long total = (System.nanoTime() - start) / 1_000_000;
            assertThat(prices).as("E2: 결과 순서는 skus 순서여야 한다")
                    .containsExactly("price-api:p1", "price-api:p2", "price-api:p3", "price-api:p4", "price-api:p5", "price-api:p6");
            assertThat(total).as("E2: 스레드 6개에 6건이면 ≈ max(200, 100...) = 200ms 여야 한다. 실제 %dms (합이면 순차 실행, R7)", total)
                    .isLessThan(300);
            assertThat(priceApi.maxConcurrency()).as("E2: 6건이 동시에 진행돼야 한다 (R7)").isEqualTo(6);
        }
        assertThat(priceApi.callerThreads()).as("E2: 모든 호출은 io 풀에서 실행돼야 한다 (R2)").allMatch(t -> t.startsWith("io-"));
    }

    @Test
    void e2_fetchPrices_isCappedByPoolSize() {
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 100);
        List<String> skus = List.of("p1", "p2", "p3", "p4", "p5", "p6");
        try (NamedPool io = Pools.fixed("io", 2)) {
            io.prestartAllCoreThreads();
            Timed<List<String>> t = Timed.of(() -> Exercises.fetchPrices(priceApi, skus, io).join());
            assertThat(t.value()).hasSize(6);
            assertThat(t.millis()).as("E2: 스레드 2개에 6건이면 ceil(6/2) × 100 = 300ms 여야 한다. 실제 %dms (R7 풀 상한)", t.millis())
                    .isBetween(250L, 420L);
            assertThat(priceApi.maxConcurrency()).as("E2: 동시 호출 수는 풀 크기를 넘을 수 없다").isEqualTo(2);
        }
    }

    // ---------------------------------------------------------------- E3 ordersOfUser

    @Test
    void e3_ordersOfUser_sequentialTiming_andThreads() {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        try (NamedPool io = Pools.fixed("io", 2); FakeEventLoopClient orderClient = new FakeEventLoopClient("orders", 200)) {
            io.prestartAllCoreThreads();
            BlockHoundSupport.reset();
            long start = System.nanoTime();
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.ordersOfUser(userDb, orderClient, "u1", io));
            assertThat(call.millis()).as("E3: 호출 즉시 반환해야 한다 (R3)").isLessThan(50);
            String orders = call.value().join();
            long total = (System.nanoTime() - start) / 1_000_000;
            assertThat(orders).as("E3: 주문은 user 조회 결과로 조회해야 한다").isEqualTo("orders:user-db:u1");
            assertThat(total).as("E3: 의존 관계라 순차다. ≈ 100 + 200 = 300ms. 실제 %dms (R7)", total).isBetween(250L, 420L);
            assertThat(BlockHoundSupport.violations()).as("E3: 이벤트 루프에서 blocking 하면 안 된다 (R4)").isEmpty();
        }
        assertThat(userDb.callerThreads()).as("E3: userDb(JDBC)는 io 풀에서 호출해야 한다 (R2, R11)").allMatch(t -> t.startsWith("io-"));
    }

    @Test
    void e3_ordersOfUser_doesNotHoldIoThreadWhileWaitingForOrders() {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        try (NamedPool io = Pools.single("io"); FakeEventLoopClient orderClient = new FakeEventLoopClient("orders", 300)) {
            io.prestartAllCoreThreads();
            CompletableFuture<String> f = Exercises.ordersOfUser(userDb, orderClient, "u1", io);
            Sleep.ms(250); // user 조회(100ms)는 끝났고 주문 조회(300ms)는 진행 중인 시점
            assertThat(io.getActiveCount())
                    .as("E3: 주문을 기다리는 동안 io 스레드를 점유하면 안 된다. 람다 안에서 join 하지 말고 thenCompose 를 써라 (R13, R4)")
                    .isZero();
            assertThat(f.join()).isEqualTo("orders:user-db:u1");
        }
    }

    // ---------------------------------------------------------------- E4 productPage

    @Test
    void e4_productPage_parallel() {
        FakeBlockingService products = new FakeBlockingService("product-db", 100);
        FakeBlockingService prices = new FakeBlockingService("price-api", 200);
        FakeBlockingService stocks = new FakeBlockingService("stock-db", 300);
        try (NamedPool io = Pools.fixed("io", 4)) {
            io.prestartAllCoreThreads();
            long start = System.nanoTime();
            Timed<CompletableFuture<Exercises.ProductPage>> call = Timed.of(() -> Exercises.productPage(products, prices, stocks, "p1", io));
            assertThat(call.millis()).as("E4: 호출 즉시 반환해야 한다 (R3)").isLessThan(50);
            Exercises.ProductPage page = call.value().join();
            long total = (System.nanoTime() - start) / 1_000_000;
            assertThat(page).isEqualTo(new Exercises.ProductPage("product-db:p1", "price-api:p1", "stock-db:p1"));
            assertThat(total).as("E4: 독립 호출 셋은 ≈ max(100, 200, 300) = 300ms 여야 한다. 실제 %dms (600ms 근처면 순차, R7)", total)
                    .isLessThan(420);
        }
        List<String> threads = new ArrayList<>();
        threads.addAll(products.callerThreads());
        threads.addAll(prices.callerThreads());
        threads.addAll(stocks.callerThreads());
        assertThat(threads).as("E4: blocking 호출은 모두 io 풀에서 실행해야 한다 (R2)").allMatch(t -> t.startsWith("io-"));
    }

    // ---------------------------------------------------------------- E5 firstSuccessful

    private static CompletableFuture<String> after(long ms, String value, RuntimeException failure, NamedPool pool) {
        return CompletableFuture.supplyAsync(() -> {
            Sleep.ms(ms);
            if (failure != null) {
                throw failure;
            }
            return value;
        }, pool);
    }

    @Test
    void e5_firstSuccessful_ignoresEarlierFailure() {
        try (NamedPool pool = Pools.fixed("replica", 3)) {
            pool.prestartAllCoreThreads();
            List<CompletableFuture<String>> inputs = List.of(
                    after(100, null, new IllegalStateException("replica-1 down"), pool),
                    after(200, "replica-2", null, pool),
                    after(400, "replica-3", null, pool));
            long start = System.nanoTime();
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.firstSuccessful(inputs));
            assertThat(call.millis()).as("E5: 즉시 반환해야 한다").isLessThan(50);
            String v = call.value().join();
            long total = (System.nanoTime() - start) / 1_000_000;
            assertThat(v).as("E5: 100ms 의 실패는 무시하고 200ms 의 첫 성공을 써야 한다 (anyOf 와의 차이, R13)").isEqualTo("replica-2");
            assertThat(total).as("E5: 첫 성공(200ms)에 바로 완료돼야 한다. 실제 %dms", total).isBetween(150L, 320L);
        }
    }

    @Test
    void e5_firstSuccessful_failsOnlyWhenAllFail() {
        IllegalStateException first = new IllegalStateException("replica-1 down");
        IllegalStateException last = new IllegalStateException("replica-2 down");
        try (NamedPool pool = Pools.fixed("replica", 2)) {
            CompletableFuture<String> f1 = after(100, null, first, pool);
            CompletableFuture<String> f2 = after(200, null, last, pool);
            CompletableFuture<String> result = Exercises.firstSuccessful(List.of(f1, f2));
            Sleep.ms(150);
            assertThat(result).as("E5: 하나만 실패한 시점에는 아직 완료되면 안 된다").isNotDone();
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .as("E5: 모두 실패하면 마지막 실패로 실패해야 한다")
                    .isInstanceOf(ExecutionException.class);
            assertThat(root(catchFailure(result))).isSameAs(last);
        }
    }

    @Test
    void e5_firstSuccessful_emptyInput() {
        assertThatThrownBy(() -> Exercises.<String>firstSuccessful(List.of()).get(1, TimeUnit.SECONDS))
                .as("E5: 빈 목록은 IllegalArgumentException 으로 실패한 future 여야 한다 (던지지 말고 future 로)")
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static Throwable catchFailure(CompletableFuture<?> f) {
        try {
            f.join();
            throw new AssertionError("실패해야 하는데 성공했다");
        } catch (CompletionException e) {
            return e;
        }
    }

    // ---------------------------------------------------------------- E6 placeOrder

    @Test
    void e6_placeOrder_waitsForSideEffects_inParallel() {
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 100);
        FakeBlockingService audit = new FakeBlockingService("audit", 200);
        FakeBlockingService notifier = new FakeBlockingService("notifier", 200);
        try (NamedPool io = Pools.fixed("io", 4)) {
            io.prestartAllCoreThreads();
            long start = System.nanoTime();
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.placeOrder(orderDb, audit, notifier, "o1", io));
            assertThat(call.millis()).as("E6: 즉시 반환해야 한다").isLessThan(50);
            String saved = call.value().join();
            long total = (System.nanoTime() - start) / 1_000_000;
            assertThat(saved).as("E6: 결과는 저장 결과여야 한다").isEqualTo("order-db:o1");
            assertThat(audit.callCount()).as("E6: 반환 future 가 완료될 때 감사 로그는 이미 끝나 있어야 한다 (R6)").isEqualTo(1);
            assertThat(notifier.callCount()).as("E6: 반환 future 가 완료될 때 알림은 이미 끝나 있어야 한다 (R6)").isEqualTo(1);
            assertThat(audit.inFlight() + notifier.inFlight()).as("E6: 부수 작업이 끝난 뒤에 완료돼야 한다").isZero();
            assertThat(total).as("E6: 저장 100 + max(감사 200, 알림 200) = 300ms 여야 한다. 실제 %dms (500ms 근처면 순차, R7)", total)
                    .isBetween(250L, 420L);
        }
        assertThat(audit.callerThreads()).as("E6: 감사 로그 호출은 io 풀에서").allMatch(t -> t.startsWith("io-"));
        assertThat(notifier.callerThreads()).as("E6: 알림 호출은 io 풀에서").allMatch(t -> t.startsWith("io-"));
    }

    @Test
    void e6_placeOrder_failureOfSideEffectIsNotLost() {
        IllegalStateException auditDown = new IllegalStateException("감사 로그 저장소 장애");
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 50);
        FakeBlockingService audit = new FakeBlockingService("audit", 50).failOn("order-db:o1", auditDown);
        FakeBlockingService notifier = new FakeBlockingService("notifier", 50);
        try (NamedPool io = Pools.fixed("io", 4)) {
            CompletableFuture<String> f = Exercises.placeOrder(orderDb, audit, notifier, "o1", io);
            Throwable failure = null;
            try {
                f.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                failure = e;
            }
            assertThat(failure).as("E6: 감사 로그 실패가 사라졌다. 버려진 분기의 예외는 아무도 관찰하지 않는다 (R6)").isNotNull();
            assertThat(root(failure)).as("E6: 원인은 감사 로그 실패여야 한다").isSameAs(auditDown);
        }
    }
}
