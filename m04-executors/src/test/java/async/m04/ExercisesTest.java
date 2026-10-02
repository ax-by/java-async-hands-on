package async.m04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.harness.Timed;
import async.m04.Exercises.PoolPlan;
import async.m04.Exercises.SizingInput;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ExercisesTest {

    // ---------------------------------------------------------------- E1

    @Test
    void e1_littlesLaw_basic() {
        PoolPlan plan = Exercises.sizePool(new SizingInput(200, 3, 50, 100, 100, 100));
        assertThat(plan.threads()).as("200 RPS × 3회 × 0.05s = 30 스레드 (R11 Little 의 법칙)").isEqualTo(30);
        assertThat(plan.queueCapacity()).as("30 스레드가 100ms 동안 처리하는 작업 수 = 30 × 100 / 50 = 60").isEqualTo(60);
        assertThat(plan.limitedByDownstream()).as("30 ≤ 다운스트림 한도 100").isFalse();
    }

    @Test
    void e1_headroom() {
        PoolPlan plan = Exercises.sizePool(new SizingInput(200, 3, 50, 150, 100, 100));
        assertThat(plan.threads()).as("필요 동시성 30 × 여유 150% = 45").isEqualTo(45);
        assertThat(plan.queueCapacity()).as("45 × 100 / 50 = 90").isEqualTo(90);
    }

    @Test
    void e1_downstreamLimitWins() {
        PoolPlan plan = Exercises.sizePool(new SizingInput(500, 2, 100, 120, 50, 200));
        assertThat(plan.threads())
                .as("계산값 120 이지만 DB 커넥션이 50개뿐이면 스레드도 50 이 상한이다. 나머지는 커넥션을 기다리며 막힐 뿐 (R11)")
                .isEqualTo(50);
        assertThat(plan.limitedByDownstream()).isTrue();
        assertThat(plan.queueCapacity()).as("잘린 스레드 수 기준: 50 × 200 / 100 = 100").isEqualTo(100);
    }

    @Test
    void e1_roundingIsCeilingAndExact() {
        assertThat(Exercises.sizePool(new SizingInput(7, 1, 30, 100, 10, 30)).threads())
                .as("0.21 → 올림 1 (스레드 0개인 풀은 없다)").isEqualTo(1);
        assertThat(Exercises.sizePool(new SizingInput(100, 3, 70, 100, 100, 70)).threads())
                .as("100 × 3 × 0.07 = 정확히 21. double 로 100 * 3 * (70 / 1000.0) 을 계산하면 21.000000000000004 → 올림 22 가 되는 함정 (정수 연산)")
                .isEqualTo(21);
        assertThat(Exercises.sizePool(new SizingInput(30, 7, 110, 130, 1000, 0)).threads())
                .as("30 × 7 × 0.11 × 1.3 = 30.03 → 31").isEqualTo(31);
    }

    // ---------------------------------------------------------------- E2

    @Test
    void e2_supplySafely_normalPath_runsOnExecutor() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (NamedPool io = Pools.fixed("io", 1)) {
            String v = Exercises.supplySafely(() -> {
                rec.mark("work");
                return "ok";
            }, io).get(1, TimeUnit.SECONDS);
            assertThat(v).isEqualTo("ok");
        }
        assertThat(rec.on("work")).as("정상 경로는 supplyAsync 와 똑같이 executor 에서 실행돼야 한다 (R2)").isEqualTo(On.pool("io"));
    }

    @Test
    void e2_supplySafely_rejectionBecomesFailedFuture() throws Exception {
        try (NamedPool io = Pools.bounded("io", 1, 1, new ThreadPoolExecutor.AbortPolicy())) {
            Gate gate = new Gate();
            CompletableFuture.runAsync(gate::await, io);
            CompletableFuture.runAsync(() -> { }, io);
            CompletableFuture<String>[] holder = new CompletableFuture[1];
            assertThatCode(() -> holder[0] = Exercises.supplySafely(() -> "never", io))
                    .as("거절이 동기 예외로 튀어 나오면 안 된다. 호출자의 exceptionally 폴백이 건너뛰어진다 (R3 예외 2)")
                    .doesNotThrowAnyException();
            assertThat(holder[0]).as("future 를 반환해야 한다").isNotNull();
            assertThat(holder[0].isCompletedExceptionally())
                    .as("거절된 작업은 즉시 실패한 future 여야 한다 (기다릴 이유가 없다)").isTrue();
            assertThatThrownBy(() -> holder[0].get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RejectedExecutionException.class);
            String recovered = holder[0].exceptionally(ex -> "fallback").get(1, TimeUnit.SECONDS);
            assertThat(recovered).as("이제 호출자의 폴백이 동작한다").isEqualTo("fallback");
            gate.open();
        }
    }

    @Test
    void e2_supplySafely_shutdownExecutor() {
        NamedPool io = Pools.fixed("io", 1);
        io.close();
        CompletableFuture<String> f = Exercises.supplySafely(() -> "x", io);
        assertThat(f.isCompletedExceptionally())
                .as("종료된 executor 도 RejectedExecutionException 을 던진다. 같은 방식으로 실패한 future 가 돼야 한다").isTrue();
    }

    // ---------------------------------------------------------------- E3

    @Test
    void e3_paymentPool_meetsAllRequirements() throws Exception {
        FakeBlockingService payment = new FakeBlockingService("pg", 100);
        ThreadRecorder rec = ThreadRecorder.start();
        try (NamedPool pool = Exercises.paymentPool()) {
            assertThat(pool.name()).isEqualTo("payment");
            assertThat(pool.getMaximumPoolSize()).as("결제사 동시 호출 한도 8 (R11)").isEqualTo(8);
            assertThat(pool.getQueue().remainingCapacity() + pool.getQueue().size())
                    .as("큐는 제한돼야 한다. 8 스레드 × 대기 200ms / 호출 100ms = 16 (E1 규칙, R11: 무제한 큐는 과부하를 지연으로 숨긴다)")
                    .isEqualTo(16);

            List<CompletableFuture<String>> accepted = new ArrayList<>();
            int rejected = 0;
            long start = System.nanoTime();
            for (int i = 0; i < 30; i++) {
                String key = "pay-" + i;
                try {
                    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                        rec.mark("call-" + key);
                        return payment.call(key);
                    }, pool);
                    if (f.isCompletedExceptionally()) {
                        rejected++;
                    } else {
                        accepted.add(f);
                    }
                } catch (RejectedExecutionException e) {
                    rejected++;
                }
            }
            long submitMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(submitMillis)
                    .as("30건 제출이 즉시 끝나야 한다. 오래 걸렸다면 요청 스레드가 결제 호출을 직접 실행한 것이다 (CallerRunsPolicy, R3 예외 1)")
                    .isLessThan(80);
            assertThat(rejected).as("8개 실행 + 16개 대기 = 24개 수용, 나머지 6개는 즉시 거절돼야 503 을 줄 수 있다").isEqualTo(6);
            for (CompletableFuture<String> f : accepted) {
                try {
                    f.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    fail("수용된 요청이 1초 안에 끝나지 않았다. Discard 계열 정책이 큐의 작업을 조용히 버렸다 (R11)");
                }
            }
            assertThat(rec.marks()).as("어떤 결제 호출도 요청 스레드(caller)에서 실행되면 안 된다 (R3 예외 1)")
                    .noneMatch(m -> m.on().equals(On.caller()));
            assertThat(payment.maxConcurrency()).as("동시 호출 한도 8 을 넘으면 결제사가 429 를 준다").isLessThanOrEqualTo(8);
            assertThat(pool.largestPoolSize()).isEqualTo(8);
        }
    }

    // ---------------------------------------------------------------- E4

    @Test
    void e4_orderAssembler_neverDeadlocks_evenWithOneThread() throws Exception {
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 100);
        FakeBlockingService itemDb = new FakeBlockingService("item-db", 100);
        try (NamedPool io = Pools.fixed("io", 1)) {
            OrderAssembler assembler = new OrderAssembler(orderDb, itemDb, io);
            CompletableFuture<OrderAssembler.OrderView> a = assembler.load("o1");
            CompletableFuture<OrderAssembler.OrderView> b = assembler.load("o2");
            try {
                CompletableFuture.allOf(a, b).get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("스레드 1개 풀에서 요청 2개가 3초 안에 끝나지 않았다(실제 작업은 800ms). "
                        + "io 스레드 위에서 io 풀의 작업을 join/get 하고 있지 않은가? (R11 thread starvation deadlock)");
            }
            assertThat(a.join()).isEqualTo(new OrderAssembler.OrderView("order-db:o1",
                    List.of("item-db:o1-item1", "item-db:o1-item2", "item-db:o1-item3")));
            assertThat(b.join().order()).isEqualTo("order-db:o2");
        }
    }

    @Test
    void e4_orderAssembler_parallelItems_underLoad() throws Exception {
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 100);
        FakeBlockingService itemDb = new FakeBlockingService("item-db", 100);
        try (NamedPool io = Pools.fixed("io", 4)) {
            OrderAssembler assembler = new OrderAssembler(orderDb, itemDb, io);
            Timed<List<CompletableFuture<OrderAssembler.OrderView>>> submit = Timed.of(() -> {
                List<CompletableFuture<OrderAssembler.OrderView>> all = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    all.add(assembler.load("o" + i));
                }
                return all;
            });
            assertThat(submit.millis()).as("load 는 호출 스레드를 막지 않고 즉시 future 를 반환해야 한다 (R3)").isLessThan(50);
            long start = System.nanoTime();
            try {
                CompletableFuture.allOf(submit.value().toArray(new CompletableFuture[0])).get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("io 4개에 동시 요청 4개가 3초 안에 끝나지 않았다. io 스레드 위에서 io 풀의 작업을 기다리고 있지 않은가? 기다리지 말고 이어 붙여라 (R11, R13)");
            }
            long elapsed = submit.millis() + (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsed)
                    .as("주문 4건(100ms) 다음 상품 12건(4개씩 3묶음) ≈ 400ms. 많이 느리다면 상품 조회가 순차다 (R7)")
                    .isLessThan(800);
            assertThat(itemDb.maxConcurrency()).as("상품 조회가 병렬로 실행돼야 한다 (R7)").isGreaterThanOrEqualTo(3);
            assertThat(orderDb.callerThreads()).allMatch(t -> t.startsWith("io-"));
            assertThat(itemDb.callerThreads()).as("모든 blocking 호출은 io 풀에서 (R11)").allMatch(t -> t.startsWith("io-"));
        }
    }

    // ---------------------------------------------------------------- E5

    @Test
    void e5_bulkhead_slowRecommendationDoesNotAffectInventory() throws Exception {
        FakeBlockingService inventory = new FakeBlockingService("inventory-api", 50);
        FakeBlockingService recommendation = new FakeBlockingService("recommendation-api", 600);
        try (DownstreamClients clients = new DownstreamClients(inventory, recommendation)) {
            Timed<List<CompletableFuture<String>>> slow = Timed.of(() -> {
                List<CompletableFuture<String>> all = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    all.add(clients.recommendation("u" + i));
                }
                return all;
            });
            assertThat(slow.millis()).as("recommendation() 은 호출 스레드를 막으면 안 된다 (R3)").isLessThan(50);

            long start = System.nanoTime();
            List<CompletableFuture<String>> fast = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                fast.add(clients.inventory("sku-" + i));
            }
            CompletableFuture.allOf(fast.toArray(new CompletableFuture[0])).get(2, TimeUnit.SECONDS);
            long inventoryMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(inventoryMillis)
                    .as("추천 호출 12개가 밀려 있어도 재고 8건은 ≈50ms 에 끝나야 한다. 재고 호출이 추천 호출과 스레드를 나눠 쓰고 있지 않은가? (R11 bulkhead)")
                    .isLessThan(250);
            assertThat(fast.get(0).join()).isEqualTo("inventory-api:sku-0");

            assertThat(inventory.callerThreads()).as("재고 호출은 inventory 풀에서").allMatch(t -> t.startsWith("inventory-"));
            assertThat(recommendation.callerThreads()).as("추천 호출은 recommendation 풀에서")
                    .allMatch(t -> t.startsWith("recommendation-"));
            assertThat(recommendation.maxConcurrency())
                    .as("추천은 동시에 최대 4개까지만. 무제한 스레드라면 느린 다운스트림에 부하를 더 얹어 장애를 키운다 (R11)")
                    .isLessThanOrEqualTo(DownstreamClients.RECOMMENDATION_THREADS);
            assertThat(inventory.maxConcurrency()).isLessThanOrEqualTo(DownstreamClients.INVENTORY_THREADS);
        }
    }
}
