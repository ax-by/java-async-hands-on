package async.m03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.FakeBlockingService;
import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.harness.ThreadRecorder;
import async.harness.Timed;
import async.m03.Errors.ExternalApiException;
import async.m03.Errors.OutOfStockException;
import async.m03.Errors.ProductNotFoundException;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ExercisesTest {

    /** join() 이 던진 예외를 돌려준다. 성공하면 AssertionError. */
    private static CompletionException joinFailure(CompletableFuture<?> f) {
        try {
            Object v = f.get(2, TimeUnit.SECONDS);
            throw new AssertionError("실패해야 하는데 성공했다: " + v);
        } catch (TimeoutException e) {
            throw new AssertionError("2초 안에 완료되지 않았다. 어딘가에서 영원히 기다리고 있다", e);
        } catch (ExecutionException | InterruptedException e) {
            // get 으로 완료를 기다린 뒤 join 으로 실제 예외 객체를 꺼낸다
        }
        try {
            f.join();
            throw new AssertionError("실패해야 하는데 성공했다");
        } catch (CompletionException e) {
            return e;
        }
    }

    private static Throwable root(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    // ---------------------------------------------------------------- E1 unwrap

    @Test
    void e1_unwrap() {
        OutOfStockException domain = new OutOfStockException("p1");
        assertThat(Exercises.unwrap(new CompletionException(domain))).as("E1: 한 겹").isSameAs(domain);
        assertThat(Exercises.unwrap(new ExecutionException(domain))).as("E1: get() 의 ExecutionException").isSameAs(domain);
        assertThat(Exercises.unwrap(new CompletionException(new CompletionException(new ExecutionException(domain)))))
                .as("E1: 여러 겹을 모두 벗겨야 한다 (껍질을 다시 감싸 던지면 두 겹이 된다, R8)").isSameAs(domain);
        assertThat(Exercises.unwrap(domain)).as("E1: 껍질이 없으면 그대로").isSameAs(domain);
        IllegalStateException withCause = new IllegalStateException("도메인 예외", domain);
        assertThat(Exercises.unwrap(withCause)).as("E1: 껍질이 아닌 예외의 cause 는 벗기지 않는다").isSameAs(withCause);
        CompletionException noCause = new CompletionException("cause 없음", null);
        assertThat(Exercises.unwrap(noCause)).as("E1: cause 가 없는 껍질은 그대로").isSameAs(noCause);
        assertThat(Exercises.unwrap(null)).as("E1: null 이면 null").isNull();
    }

    // ---------------------------------------------------------------- E2 fallbackOn

    @Test
    void e2_fallbackOn_wrappedFailureFromSupplyAsync() {
        try (NamedPool io = Pools.fixed("io", 1)) {
            CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> {
                throw new OutOfStockException("p1");
            }, io);
            assertThat(Exercises.fallbackOn(stock, OutOfStockException.class, "SOLD_OUT").join())
                    .as("E2: supplyAsync 의 예외는 CompletionException 으로 감싸져 온다. unwrap 해서 판별해야 한다 (R8)")
                    .isEqualTo("SOLD_OUT");
        }
    }

    @Test
    void e2_fallbackOn_rawFailureFromCompleteExceptionally() {
        CompletableFuture<String> stock = new CompletableFuture<>();
        CompletableFuture<String> result = Exercises.fallbackOn(stock, OutOfStockException.class, "SOLD_OUT");
        stock.completeExceptionally(new OutOfStockException("p1"));
        assertThat(result.join()).as("E2: completeExceptionally 로 온 예외(감싸지지 않음)에도 동작해야 한다 (R8)")
                .isEqualTo("SOLD_OUT");
    }

    @Test
    void e2_fallbackOn_otherFailurePropagatesWithSingleWrap() {
        ExternalApiException apiDown = new ExternalApiException("stock-api 503");
        try (NamedPool io = Pools.fixed("io", 1)) {
            CompletableFuture<String> wrapped = CompletableFuture.supplyAsync(() -> {
                throw apiDown;
            }, io);
            CompletionException e1 = joinFailure(Exercises.fallbackOn(wrapped, OutOfStockException.class, "SOLD_OUT"));
            assertThat(e1.getCause()).as("E2: 대상이 아닌 실패는 그대로 전파하고, 한 겹만 감싸야 한다. "
                    + "이미 CompletionException 이면 그대로 다시 던져라 (R8)").isSameAs(apiDown);
        }
        CompletableFuture<String> raw = new CompletableFuture<>();
        CompletableFuture<String> r2 = Exercises.fallbackOn(raw, OutOfStockException.class, "SOLD_OUT");
        raw.completeExceptionally(apiDown);
        assertThat(joinFailure(r2).getCause()).as("E2: 감싸지지 않은 원본도 원래 예외가 cause 여야 한다").isSameAs(apiDown);
    }

    @Test
    void e2_fallbackOn_successPassesThrough() {
        assertThat(Exercises.fallbackOn(CompletableFuture.completedFuture("10개"), OutOfStockException.class, "SOLD_OUT").join())
                .isEqualTo("10개");
    }

    // ---------------------------------------------------------------- E3 withTimeoutFallback

    @Test
    void e3_withTimeoutFallback_timesOut_withoutTouchingSource_andReportsLateResult() {
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 300);
        List<String> late = new CopyOnWriteArrayList<>();
        try (NamedPool io = Pools.fixed("io", 1)) {
            CompletableFuture<String> source = CompletableFuture.supplyAsync(() -> priceApi.call("p1"), io);
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.withTimeoutFallback(source, 100, "cached", late::add));
            assertThat(call.millis()).as("E3: 즉시 반환해야 한다").isLessThan(50);
            Timed<String> waited = Timed.of(() -> call.value().join());
            assertThat(waited.value()).isEqualTo("cached");
            assertThat(waited.millis()).as("E3: 100ms 근처에서 fallback 으로 완료돼야 한다. 실제 %dms", waited.millis()).isBetween(50L, 220L);
            assertThat(source.isDone())
                    .as("E3: source 가 완료돼 버렸다. completeOnTimeout/orTimeout 은 새 future 가 아니라 this 를 바꾼다 (R10)")
                    .isFalse();
            source.join();
            await().atMost(Duration.ofSeconds(1)).until(() -> !late.isEmpty());
            Sleep.ms(50);
            assertThat(late).as("E3: 타임아웃 뒤 도착한 값은 onLateResult 로 정확히 한 번 알려야 한다").containsExactly("price-api:p1");
            assertThat(call.value().join()).as("E3: 늦게 온 값이 결과를 바꾸면 안 된다").isEqualTo("cached");
        }
    }

    @Test
    void e3_withTimeoutFallback_inTime() {
        AtomicInteger lateCalls = new AtomicInteger();
        try (NamedPool io = Pools.fixed("io", 1)) {
            CompletableFuture<String> source = CompletableFuture.supplyAsync(() -> {
                Sleep.ms(50);
                return "real";
            }, io);
            CompletableFuture<String> r = Exercises.withTimeoutFallback(source, 200, "cached", v -> lateCalls.incrementAndGet());
            assertThat(r.join()).as("E3: 시간 안에 끝나면 실제 값").isEqualTo("real");
            Sleep.ms(250);
            assertThat(lateCalls).as("E3: 시간 안에 끝났으면 onLateResult 를 호출하면 안 된다").hasValue(0);
        }
    }

    @Test
    void e3_withTimeoutFallback_failureInTimePropagates() {
        ExternalApiException apiDown = new ExternalApiException("price-api 503");
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> r = Exercises.withTimeoutFallback(source, 500, "cached", v -> {
        });
        source.completeExceptionally(apiDown);
        assertThat(root(joinFailure(r))).as("E3: 시간 안의 실패는 그대로 전파한다(폴백은 타임아웃에만)").isSameAs(apiDown);
    }

    // ---------------------------------------------------------------- E4 Java8Timeouts.within

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, Pools.namedFactory("timer"));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    @Test
    void e4_within_doesNotUseJava9TimeoutApis() throws Exception {
        Path source = Path.of("src/main/java/async/m03/Java8Timeouts.java");
        if (!Files.exists(source)) {
            source = Path.of("m03-failure").resolve(source); // IDE 가 저장소 루트에서 테스트를 실행하는 경우
        }
        assertThat(source).as("Java8Timeouts.java 소스를 찾지 못했다 (작업 디렉터리를 모듈 또는 저장소 루트로)").exists();
        String code = Files.readString(source, StandardCharsets.UTF_8);
        for (String java9Api : List.of("orTimeout", "completeOnTimeout", "delayedExecutor", "failedFuture")) {
            assertThat(code).as("E4: Java 8 에는 %s 가 없다. ScheduledExecutorService 로 직접 구현하라 (R10, 버전표)", java9Api)
                    .doesNotContain(java9Api);
        }
    }

    @Test
    void e4_within_timesOutOnSchedulerThread_withoutTouchingSource() {
        ThreadRecorder rec = ThreadRecorder.start();
        ScheduledThreadPoolExecutor timer = timer();
        try {
            CompletableFuture<String> source = new CompletableFuture<>();
            Timed<CompletableFuture<String>> call = Timed.of(() -> Java8Timeouts.within(source, 100, timer));
            assertThat(call.millis()).as("E4: 즉시 반환해야 한다").isLessThan(50);
            CompletableFuture<String> observed = call.value().whenComplete((v, ex) -> rec.mark("after"));
            Timed<CompletionException> failed = Timed.of(() -> joinFailure(observed));
            assertThat(root(failed.value())).as("E4: 시간 초과면 TimeoutException 으로 실패해야 한다").isInstanceOf(TimeoutException.class);
            assertThat(failed.millis()).as("E4: 100ms 근처에서 실패해야 한다. 실제 %dms", failed.millis()).isBetween(50L, 250L);
            assertThat(rec.on("after")).as("E4: 타이머 스레드가 결과를 완료시키므로 non-async 콜백도 그 스레드에서 실행된다 (R1, R10)")
                    .isEqualTo(On.pool("timer"));
            assertThat(source.isDone()).as("E4: source 를 건드리면 안 된다 (새 future 를 반환하라)").isFalse();
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void e4_within_completesInTime_andCancelsTimer() {
        ScheduledThreadPoolExecutor timer = timer();
        try (NamedPool io = Pools.fixed("io", 1)) {
            CompletableFuture<String> source = CompletableFuture.supplyAsync(() -> {
                Sleep.ms(50);
                return "real";
            }, io);
            CompletableFuture<String> r = Java8Timeouts.within(source, 5_000, timer);
            assertThat(r.join()).isEqualTo("real");
            await().atMost(Duration.ofMillis(500)).untilAsserted(() -> assertThat(timer.getQueue())
                    .as("E4: source 가 먼저 끝났으면 타이머를 취소해야 한다. 안 그러면 요청마다 타이머가 쌓인다").isEmpty());
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void e4_within_failureInTimePropagates() {
        ScheduledThreadPoolExecutor timer = timer();
        try {
            OutOfStockException domain = new OutOfStockException("p1");
            CompletableFuture<String> source = new CompletableFuture<>();
            CompletableFuture<String> r = Java8Timeouts.within(source, 1_000, timer);
            source.completeExceptionally(domain);
            assertThat(root(joinFailure(r))).as("E4: 시간 안의 실패는 그대로 전파한다").isSameAs(domain);
        } finally {
            timer.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- E5 interruptibleCall

    @Test
    void e5_interruptibleCall_success() {
        FakeBlockingService reportApi = new FakeBlockingService("report-api", 100);
        try (NamedPool io = Pools.fixed("io", 1)) {
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.interruptibleCall(reportApi, "r1", io));
            assertThat(call.millis()).as("E5: 즉시 반환해야 한다").isLessThan(50);
            assertThat(call.value().join()).isEqualTo("report-api:r1");
            assertThat(reportApi.interruptedCount()).as("E5: 정상 완료면 interrupt 하지 않는다").isZero();
            assertThat(reportApi.callerThreads()).as("E5: 호출은 pool 에서 실행돼야 한다").allMatch(t -> t.startsWith("io-"));
        }
    }

    @Test
    void e5_interruptibleCall_cancelInterruptsAndFreesThread() {
        FakeBlockingService reportApi = new FakeBlockingService("report-api", 1_000);
        try (NamedPool io = Pools.single("io")) {
            CompletableFuture<String> report = Exercises.interruptibleCall(reportApi, "r1", io);
            await().atMost(Duration.ofSeconds(1)).until(() -> reportApi.inFlight() == 1);
            report.cancel(true);
            await().atMost(Duration.ofMillis(300)).untilAsserted(() -> {
                assertThat(reportApi.interruptedCount())
                        .as("E5: 반환 future 를 취소하면 실제 호출이 interrupt 돼야 한다. CF.cancel 은 interrupt 하지 않는다 (R10)")
                        .isEqualTo(1);
                assertThat(reportApi.inFlight()).isZero();
            });
            Timed<String> next = Timed.of(() -> CompletableFuture.supplyAsync(() -> "next", io).join());
            assertThat(next.millis()).as("E5: 취소 후 스레드가 즉시 풀로 돌아와야 한다 (1초짜리 호출이 계속 점유하면 안 된다)")
                    .isLessThan(200);
        }
    }

    @Test
    void e5_interruptibleCall_timeoutAppliedByCallerInterrupts() {
        FakeBlockingService reportApi = new FakeBlockingService("report-api", 1_000);
        try (NamedPool io = Pools.single("io")) {
            CompletableFuture<String> report = Exercises.interruptibleCall(reportApi, "r1", io)
                    .orTimeout(100, TimeUnit.MILLISECONDS);
            assertThat(root(joinFailure(report))).isInstanceOf(TimeoutException.class);
            await().atMost(Duration.ofMillis(300)).untilAsserted(() -> assertThat(reportApi.interruptedCount())
                    .as("E5: 호출자가 orTimeout 을 걸어도 실제 호출이 interrupt 돼야 한다. 취소뿐 아니라 '실패로 끝남'을 감지하라 (R10)")
                    .isEqualTo(1));
        }
    }

    // ---------------------------------------------------------------- E6 logged

    @Test
    void e6_logged_success() {
        List<String> log = new CopyOnWriteArrayList<>();
        assertThat(Exercises.logged(CompletableFuture.completedFuture("paid"), log::add).join()).isEqualTo("paid");
        assertThat(log).containsExactly("OK paid");
    }

    @Test
    void e6_logged_failureIsObservedButNotRecovered() {
        List<String> log = new CopyOnWriteArrayList<>();
        try (NamedPool io = Pools.fixed("io", 1)) {
            CompletableFuture<String> paid = CompletableFuture.supplyAsync(() -> {
                throw new OutOfStockException("p1");
            }, io);
            CompletionException e = joinFailure(Exercises.logged(paid, log::add));
            assertThat(root(e)).as("E6: 로그만 남기고 실패는 그대로 전파해야 한다 (관찰 ≠ 복구, R9)").isInstanceOf(OutOfStockException.class);
            assertThat(log).as("E6: 근본 원인 이름을 남겨야 한다 (CompletionException 이 아니라, R8)").containsExactly("FAIL OutOfStockException");
        }
    }

    @Test
    void e6_logged_brokenLoggerDoesNotChangeOutcome() {
        Consumer<String> brokenLogger = line -> {
            throw new IllegalStateException("로그 수집기 연결 끊김");
        };
        assertThat(Exercises.logged(CompletableFuture.completedFuture("paid"), brokenLogger).join())
                .as("E6: 로거가 던져도 성공은 성공이어야 한다. whenComplete 액션의 예외는 성공을 실패로 바꾼다 (R9)")
                .isEqualTo("paid");
        OutOfStockException domain = new OutOfStockException("p1");
        CompletableFuture<String> failed = new CompletableFuture<>();
        CompletableFuture<String> r = Exercises.logged(failed, brokenLogger);
        failed.completeExceptionally(domain);
        assertThat(root(joinFailure(r))).as("E6: 로거가 던져도 원래 실패가 유지돼야 한다").isSameAs(domain);
    }

    // ---------------------------------------------------------------- E7 productDetail

    @Test
    void e7_productDetail_bothOk_parallel() {
        FakeBlockingService products = new FakeBlockingService("product", 100);
        FakeBlockingService reviews = new FakeBlockingService("reviews", 200);
        try (NamedPool io = Pools.fixed("io", 2)) {
            io.prestartAllCoreThreads();
            long start = System.nanoTime();
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.productDetail(products, reviews, "p1", io, 400));
            assertThat(call.millis()).as("E7: 즉시 반환해야 한다").isLessThan(50);
            String page = call.value().join();
            long total = (System.nanoTime() - start) / 1_000_000;
            assertThat(page).isEqualTo("product:p1|reviews:p1");
            assertThat(total).as("E7: 둘은 독립이다. ≈ max(100, 200) = 200ms. 실제 %dms (R7)", total).isLessThan(290);
        }
    }

    @Test
    void e7_productDetail_optionalFailureFallsBack() {
        FakeBlockingService products = new FakeBlockingService("product", 50);
        FakeBlockingService reviews = new FakeBlockingService("reviews", 50).failOn("p1", new ExternalApiException("reviews 503"));
        try (NamedPool io = Pools.fixed("io", 2)) {
            assertThat(Exercises.productDetail(products, reviews, "p1", io, 400).join())
                    .as("E7: 리뷰 실패는 no-reviews 로 대체한다").isEqualTo("product:p1|no-reviews");
        }
    }

    @Test
    void e7_productDetail_optionalSlowFallsBackWithinBudget() {
        FakeBlockingService products = new FakeBlockingService("product", 50);
        FakeBlockingService reviews = new FakeBlockingService("reviews", 600);
        try (NamedPool io = Pools.fixed("io", 2)) {
            Timed<String> t = Timed.of(() -> Exercises.productDetail(products, reviews, "p1", io, 150).join());
            assertThat(t.value()).isEqualTo("product:p1|no-reviews");
            assertThat(t.millis()).as("E7: 리뷰 예산(150ms)이 지나면 기다리지 말고 응답해야 한다. 실제 %dms (R10)", t.millis())
                    .isLessThan(300);
        }
    }

    @Test
    void e7_productDetail_requiredFailureFailsWhole() {
        ProductNotFoundException notFound = new ProductNotFoundException("p1");
        FakeBlockingService products = new FakeBlockingService("product", 50).failOn("p1", notFound);
        FakeBlockingService reviews = new FakeBlockingService("reviews", 50);
        try (NamedPool io = Pools.fixed("io", 2)) {
            CompletionException e = joinFailure(Exercises.productDetail(products, reviews, "p1", io, 400));
            assertThat(root(e)).as("E7: 필수 정보(상품)의 실패는 전체 실패여야 한다. 복구 단계가 합친 뒤에 있으면 상품 실패까지 삼킨다 (R9)")
                    .isSameAs(notFound);
        }
    }
}
