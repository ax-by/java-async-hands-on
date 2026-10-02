package async.m07vt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * m07-vt 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>같은 "상품 상세 페이지" A 설계(상품·가격·리뷰 3개의 독립 blocking 호출)를 세 가지로 쓴다.
 * <ol>
 *   <li>{@link #withCompletableFuture}: Java 8/17 방식. CF + 전용 플랫폼 스레드 풀</li>
 *   <li>{@link #withVirtualThreads}: Java 21+. 평범한 blocking 코드 + 작업마다 가상 스레드</li>
 *   <li>{@link #withStructuredScope}: Java 25 preview (JEP 505). 구조적 동시성</li>
 * </ol>
 * 실행: 시나리오마다 "몇 ms 에 끝났는가", "형제 작업이 interrupt 됐는가" 를 예측한다.
 */
public final class VtScenarios {

    private VtScenarios() {
    }

    /** 세 서비스. 각자 지연시간과 실패를 설정할 수 있다 (FakeBlockingService). */
    public record Services(FakeBlockingService product, FakeBlockingService price, FakeBlockingService review) {
        public int interruptedTotal() {
            return product.interruptedCount() + price.interruptedCount() + review.interruptedCount();
        }
    }

    // ── 같은 A 설계, 세 가지 표현 ──────────────────────────────────────────────

    /** 1) CF + 플랫폼 풀 (Java 8/17). */
    public static String withCompletableFuture(Services s, String key, NamedPool io) {
        CompletableFuture<String> product = CompletableFuture.supplyAsync(() -> s.product().call(key), io);
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> s.price().call(key), io);
        CompletableFuture<String> review = CompletableFuture.supplyAsync(() -> s.review().call(key), io);
        return CompletableFuture.allOf(product, price, review)
                .thenApply(v -> product.join() + "|" + price.join() + "|" + review.join())
                .join();
    }

    /** 2) 가상 스레드 + 평범한 Future.get (Java 21+). */
    public static String withVirtualThreads(Services s, String key) throws Exception {
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> product = vt.submit(() -> s.product().call(key));
            Future<String> price = vt.submit(() -> s.price().call(key));
            Future<String> review = vt.submit(() -> s.review().call(key));
            return product.get() + "|" + price.get() + "|" + review.get();
        }
    }

    /** 3) StructuredTaskScope (Java 25 preview). 기본 Joiner: 하나라도 실패하면 join 이 FailedException 을 던진다. */
    public static String withStructuredScope(Services s, String key) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            Subtask<String> product = scope.fork(() -> s.product().call(key));
            Subtask<String> price = scope.fork(() -> s.price().call(key));
            Subtask<String> review = scope.fork(() -> s.review().call(key));
            scope.join();
            return product.get() + "|" + price.get() + "|" + review.get();
        }
    }

    // ── 예측 시나리오 ─────────────────────────────────────────────────────────

    /**
     * V01. CF 버전에서 product 가 50ms 만에 실패하고, price 와 review 는 500ms 걸린다.
     * {@link #withCompletableFuture} 가 예외를 던지기까지 몇 ms? 그 사이 price/review 는 interrupt 될까?
     */
    public static Services v01Services() {
        return new Services(
                new FakeBlockingService("product", 50).failOn("k", new IllegalStateException("상품 DB 장애")),
                new FakeBlockingService("price", 500),
                new FakeBlockingService("review", 500));
    }

    /** V02. 같은 서비스 구성으로 {@link #withStructuredScope} 를 호출한다. */
    public static Services v02Services() {
        return v01Services();
    }

    /**
     * V03. 100ms blocking 호출 2,000개를 동시에 처리한다.
     * platform: 플랫폼 스레드 200개짜리 풀 / virtual: 작업마다 가상 스레드. 각각 총 몇 ms?
     */
    public static long v03Platform(FakeBlockingService api) throws Exception {
        long start = System.nanoTime();
        try (NamedPool pool = Pools.fixed("io", 200)) {
            List<Future<String>> all = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                String key = "k" + i;
                all.add(pool.submit(() -> api.call(key)));
            }
            for (Future<String> f : all) {
                f.get();
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static long v03Virtual(FakeBlockingService api) throws Exception {
        long start = System.nanoTime();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> all = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                String key = "k" + i;
                all.add(vt.submit(() -> api.call(key)));
            }
            for (Future<String> f : all) {
                f.get();
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    // ── 컨텍스트: ThreadLocal, InheritableThreadLocal, ScopedValue ──────────────

    public static final ThreadLocal<String> TRACE_TL = new ThreadLocal<>();
    public static final InheritableThreadLocal<String> TRACE_ITL = new InheritableThreadLocal<>();
    public static final ScopedValue<String> TRACE_SV = ScopedValue.newInstance();

    static String tl() {
        return TRACE_TL.get() == null ? "NONE" : TRACE_TL.get();
    }

    static String itl() {
        return TRACE_ITL.get() == null ? "NONE" : TRACE_ITL.get();
    }

    static String sv() {
        return TRACE_SV.isBound() ? TRACE_SV.get() : "NONE";
    }

    /**
     * V04. 요청 스레드가 TRACE_TL 과 TRACE_ITL 을 모두 "t-100" 으로 설정한 뒤,
     * 가상 스레드 executor 에 제출한 작업에서 각각 무엇이 보일까? 반환: [tl, itl]
     */
    public static String[] v04() throws Exception {
        TRACE_TL.set("t-100");
        TRACE_ITL.set("t-100");
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            return new String[] {vt.submit(VtScenarios::tl).get(), vt.submit(VtScenarios::itl).get()};
        } finally {
            TRACE_TL.remove();
            TRACE_ITL.remove();
        }
    }

    /**
     * V05. InheritableThreadLocal 과 "재사용되는" 스레드.
     * 요청 t-100 이 플랫폼 스레드 1개짜리 풀과 가상 스레드 executor 를 모두 한 번씩 쓴 뒤,
     * 다음 요청 t-200 이 다시 둘 다에 제출한다. 반환: [platform, virtual] (t-200 요청의 작업에서 보인 값)
     */
    public static String[] v05() throws Exception {
        try (NamedPool pool = Pools.single("io"); ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            TRACE_ITL.set("t-100");
            pool.submit(VtScenarios::itl).get();
            vt.submit(VtScenarios::itl).get();
            TRACE_ITL.set("t-200");
            return new String[] {pool.submit(VtScenarios::itl).get(), vt.submit(VtScenarios::itl).get()};
        } finally {
            TRACE_ITL.remove();
        }
    }

    /**
     * V06. ScopedValue 를 "t-100" 으로 바인딩한 범위 안에서 세 가지 방법으로 다른 스레드에 일을 넘긴다.
     * 반환: [StructuredTaskScope.fork, 가상 스레드 executor submit, CF.supplyAsync(commonPool)] 에서 보인 값
     */
    public static String[] v06() throws Exception {
        return ScopedValue.where(TRACE_SV, "t-100").call(() -> {
            String inFork;
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>allSuccessfulOrThrow())) {
                Subtask<String> s = scope.fork(VtScenarios::sv);
                scope.join();
                inFork = s.get();
            }
            String inExecutor;
            try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
                inExecutor = vt.submit(VtScenarios::sv).get();
            }
            String inCf = CompletableFuture.supplyAsync(VtScenarios::sv).join();
            return new String[] {inFork, inExecutor, inCf};
        });
    }

    /**
     * V07. 가상 스레드 (4 × CPU 코어 수)개가 각자 자기 락으로 {@code synchronized} 블록 안에서 100ms blocking 한다.
     * (가상 스레드 스케줄러의 캐리어 스레드 수 = CPU 코어 수) 총 몇 ms? <b>이 repo 의 JDK(25) 기준</b>으로 답한다.
     */
    public static long v07() {
        int n = Runtime.getRuntime().availableProcessors() * 4;
        FakeBlockingService api = new FakeBlockingService("legacy-sdk", 100);
        long start = System.nanoTime();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                Object lock = new Object();
                vt.submit(() -> {
                    synchronized (lock) {
                        return api.call("k");
                    }
                });
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
