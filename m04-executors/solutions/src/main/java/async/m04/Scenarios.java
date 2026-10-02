package async.m04;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.harness.ThreadRecorder;

/**
 * m04 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>"(측정 장치)", "(결정성 장치)", "(정리 장치)" 주석이 붙은 줄은 결과를 관찰하거나 테스트가 멈추지 않게 하는 코드다.
 * 예측 대상이 아니다.
 *
 * <p>자식 JVM 에서 실행되는 시나리오(C01, V01)는 {@code probe} 패키지에 있다.
 */
public final class Scenarios {

    private Scenarios() {
    }

    // ------------------------------------------------------------------------------------------------
    // S01. executor 를 생략한 supplyAsync
    // ------------------------------------------------------------------------------------------------

    /** S01. 테스트 JVM(JDK 25, CPU 여러 개)에서 executor 없이 supplyAsync 를 호출한다. task 는 어디서 실행될까? */
    public static CompletableFuture<Void> s01(ThreadRecorder rec) {
        return CompletableFuture.runAsync(() -> rec.mark("task"));
    }

    // ------------------------------------------------------------------------------------------------
    // S02. 거절 정책별로 supplyAsync 가 겪는 일
    // ------------------------------------------------------------------------------------------------

    /**
     * S02. 스레드 1개, 큐 용량 1인 풀. 첫 작업이 스레드를 잡고(Gate), 둘째 작업이 큐를 채운 상태에서 셋째 작업을 제출한다.
     * policy 별로 <b>둘째(second)</b>와 <b>셋째(third)</b> 작업의 {@link Fate} 는?
     *
     * @return [0] = second 의 운명, [1] = third 의 운명
     */
    public static Fate[] s02(ThreadRecorder rec, RejectedExecutionHandler policy) {
        try (NamedPool io = Pools.bounded("io", 1, 1, policy)) {
            Gate gate = new Gate();
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
                gate.await();                                   // io-1 을 붙잡고 있다
                return "1";
            }, io);
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                rec.mark("second");
                return "2";
            }, io);                                             // 큐(용량 1)에 들어간다

            CompletableFuture<String> third = null;
            RuntimeException thrown = null;
            try {
                third = CompletableFuture.supplyAsync(() -> {   // 스레드도 큐도 꽉 찼다
                    rec.mark("third");
                    return "3";
                }, io);
            } catch (RuntimeException e) {
                thrown = e;
            }
            gate.open();
            first.join();
            return new Fate[] {observe(rec, "second", second, null), observe(rec, "third", third, thrown)};
        }
    }

    /** (측정 장치) 제출 결과를 {@link Fate} 로 분류한다. 500ms 안에 완료되지 않으면 영원히 완료되지 않는 것으로 본다. */
    static Fate observe(ThreadRecorder rec, String step, CompletableFuture<String> future, RuntimeException thrown) {
        if (thrown != null) {
            return Fate.THROWS_SYNC;
        }
        try {
            future.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Fate.NEVER_COMPLETES;
        } catch (ExecutionException e) {
            return Fate.COMPLETES_EXCEPTIONALLY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return rec.on(step).equals(On.caller()) ? Fate.RUNS_ON_CALLER : Fate.COMPLETES_NORMALLY;
    }

    // ------------------------------------------------------------------------------------------------
    // S03, S04. ThreadPoolExecutor 의 core / max / queue
    // ------------------------------------------------------------------------------------------------

    /** S03 결과. queuedIds 는 "모든 제출이 끝난 시점에 아직 시작하지 못하고 큐에서 기다리던 작업 번호"(오름차순, 쉼표 구분). */
    public record PoolShape(int largestPoolSize, String queuedIds, String rejectedIds) {
    }

    /**
     * S03. core 2, max 4, 큐 용량 2(ArrayBlockingQueue), 기본 거절 정책(AbortPolicy)인 풀에 작업 1~7 을 차례로 제출한다.
     * 모든 작업은 Gate 가 열릴 때까지 끝나지 않는다.
     */
    public static PoolShape s03(ThreadRecorder rec) throws Exception {
        ThreadPoolExecutor api = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), Pools.namedFactory("api"));
        try {
            Gate gate = new Gate();
            List<CompletableFuture<Void>> accepted = new ArrayList<>();
            StringJoiner rejected = new StringJoiner(",");
            for (int id = 1; id <= 7; id++) {
                int task = id;
                try {
                    accepted.add(CompletableFuture.runAsync(() -> {
                        rec.mark("start-" + task);
                        gate.await();
                    }, api));
                } catch (RejectedExecutionException e) {
                    rejected.add(String.valueOf(task));
                }
            }
            Sleep.ms(200);                                      // (측정 장치) 실행 중인 작업이 start 를 기록할 시간
            StringJoiner queued = new StringJoiner(",");
            for (int id = 1; id <= 7; id++) {
                if (!rec.has("start-" + id) && !rejected.toString().contains(String.valueOf(id))) {
                    queued.add(String.valueOf(id));
                }
            }
            gate.open();
            CompletableFuture.allOf(accepted.toArray(new CompletableFuture[0])).get(2, TimeUnit.SECONDS);
            return new PoolShape(api.getLargestPoolSize(), queued.toString(), rejected.toString());
        } finally {
            api.shutdownNow();
        }
    }

    /** S04 결과. */
    public record Throughput(int largestPoolSize, long totalMillis) {
    }

    /**
     * S04. core 2, max 10, <b>무제한 큐</b>(LinkedBlockingQueue)인 풀에 100ms 작업 10개를 한꺼번에 제출한다.
     * (Spring 의 {@code ThreadPoolTaskExecutor} 기본값과 같은 구조: core 만 정하고 queueCapacity 를 정하지 않은 경우)
     */
    public static Throughput s04() throws Exception {
        ThreadPoolExecutor api = new ThreadPoolExecutor(2, 10, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), Pools.namedFactory("api"));
        try {
            FakeBlockingService remote = new FakeBlockingService("remote", 100);
            long start = System.nanoTime();
            List<CompletableFuture<String>> all = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String key = "k" + i;
                all.add(CompletableFuture.supplyAsync(() -> remote.call(key), api));
            }
            CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            return new Throughput(api.getLargestPoolSize(), (System.nanoTime() - start) / 1_000_000);
        } finally {
            api.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // S05. 무제한 큐 vs 제한 큐: 과부하는 어디로 가는가
    // ------------------------------------------------------------------------------------------------

    /**
     * S05-a. 스레드 2개, <b>무제한 큐</b>. 100ms 걸리는 호출 20개가 한꺼번에 들어온다.
     *
     * @return 마지막 호출이 끝난 시점 (제출 시작 후 ms). 즉, 가장 운 나쁜 요청이 겪는 지연시간
     */
    public static long s05Unbounded() throws Exception {
        try (NamedPool io = Pools.fixed("io", 2)) {
            FakeBlockingService remote = new FakeBlockingService("remote", 100);
            long start = System.nanoTime();
            List<CompletableFuture<String>> all = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String key = "k" + i;
                all.add(CompletableFuture.supplyAsync(() -> remote.call(key), io));
            }
            CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            return (System.nanoTime() - start) / 1_000_000;
        }
    }

    /**
     * S05-b. 스레드 2개, <b>큐 용량 4</b>, AbortPolicy. 같은 20개가 한꺼번에 들어온다.
     *
     * @return 거절된 호출 수
     */
    public static int s05Bounded() throws Exception {
        try (NamedPool io = Pools.bounded("io", 2, 4, new ThreadPoolExecutor.AbortPolicy())) {
            FakeBlockingService remote = new FakeBlockingService("remote", 100);
            List<CompletableFuture<String>> accepted = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String key = "k" + i;
                try {
                    accepted.add(CompletableFuture.supplyAsync(() -> remote.call(key), io));
                } catch (RejectedExecutionException e) {
                    // 실무라면 여기서 503 을 돌려준다
                }
            }
            CompletableFuture.allOf(accepted.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            return io.rejectedCount();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // S06. thread starvation deadlock
    // ------------------------------------------------------------------------------------------------

    /**
     * S06. 주문 조회 요청 {@code requests} 개가 io 풀(스레드 {@code threads} 개)에서 동시에 처리된다.
     * 각 요청은 io 스레드 위에서 상품 조회를 <b>같은 io 풀</b>에 제출하고 {@code join()} 으로 기다린다.
     * 모든 요청이 끝나는가({@link Outcome#COMPLETES}), 영원히 멈추는가({@link Outcome#DEADLOCK})?
     */
    public static Outcome s06(int threads, int requests) throws Exception {
        try (NamedPool io = Pools.fixed("io", threads)) {
            FakeBlockingService itemDb = new FakeBlockingService("item-db", 100);
            Gate allSubmitted = new Gate();
            List<CompletableFuture<String>> inners = new CopyOnWriteArrayList<>();
            List<CompletableFuture<String>> orders = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String orderId = "o" + i;
                orders.add(CompletableFuture.supplyAsync(() -> {
                    allSubmitted.await();                       // (결정성 장치) 모든 요청이 제출된 뒤에 안쪽 작업을 제출한다
                    CompletableFuture<String> item = CompletableFuture.supplyAsync(() -> itemDb.call(orderId), io);
                    inners.add(item);
                    return orderId + "+" + item.join();          // io 스레드가 io 풀의 작업을 기다린다
                }, io));
            }
            allSubmitted.open();
            try {
                CompletableFuture.allOf(orders.toArray(new CompletableFuture[0])).get(1500, TimeUnit.MILLISECONDS);
                return Outcome.COMPLETES;
            } catch (TimeoutException e) {
                return Outcome.DEADLOCK;
            } finally {
                inners.forEach(f -> f.complete("released")); // (정리 장치) 멈춘 스레드가 있다면 풀어 준다. 운영에서는 이런 장치가 없다
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // S07. bulkhead
    // ------------------------------------------------------------------------------------------------

    /**
     * S07-a. 추천 API(500ms, 장애로 느려진 상태)와 재고 API(50ms)가 <b>하나의 풀(4개)</b>을 공유한다.
     * 추천 호출 4개가 먼저 들어온 직후 재고 호출 1개가 들어온다.
     *
     * @return 재고 호출의 응답시간 (ms)
     */
    public static long s07Shared() {
        FakeBlockingService recommendation = new FakeBlockingService("recommendation", 500);
        FakeBlockingService inventory = new FakeBlockingService("inventory", 50);
        try (NamedPool shared = Pools.fixed("shared", 4)) {
            for (int i = 0; i < 4; i++) {
                String key = "r" + i;
                CompletableFuture.supplyAsync(() -> recommendation.call(key), shared);
            }
            long start = System.nanoTime();
            CompletableFuture.supplyAsync(() -> inventory.call("sku-1"), shared).join();
            return (System.nanoTime() - start) / 1_000_000;
        }
    }

    /** S07-b. 같은 상황, 다만 의존 서비스마다 풀을 나눴다(bulkhead). 재고 호출의 응답시간은? */
    public static long s07Bulkhead() {
        FakeBlockingService recommendation = new FakeBlockingService("recommendation", 500);
        FakeBlockingService inventory = new FakeBlockingService("inventory", 50);
        try (NamedPool recommendationPool = Pools.fixed("recommendation", 4);
             NamedPool inventoryPool = Pools.fixed("inventory", 4)) {
            for (int i = 0; i < 4; i++) {
                String key = "r" + i;
                CompletableFuture.supplyAsync(() -> recommendation.call(key), recommendationPool);
            }
            long start = System.nanoTime();
            CompletableFuture.supplyAsync(() -> inventory.call("sku-1"), inventoryPool).join();
            return (System.nanoTime() - start) / 1_000_000;
        }
    }
}
