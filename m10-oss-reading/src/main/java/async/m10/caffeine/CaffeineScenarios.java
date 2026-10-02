package async.m10.caffeine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import async.harness.Gate;
import async.harness.Sleep;
import async.harness.ThreadRecorder;
import async.m10.Threads;

/**
 * 1장 Caffeine (AsyncCache) 예측 시나리오. <b>이 파일은 수정하지 않는다.</b>
 *
 * <p>상황: 상품 상세 조회 API 앞에 Caffeine {@code AsyncCache<String, String>} 을 두었다.
 * 값은 상품 ID → 상품 이름이다. 로더는 DB 를 부른다고 생각하라.
 * 읽기 가이드: {@code guides/01-caffeine.md}
 */
public final class CaffeineScenarios {

    private CaffeineScenarios() {
    }

    /** C1 결과: 로더 호출 횟수, 두 호출자가 받은 future 가 같은 인스턴스인가. */
    public record DedupResult(int loaderCalls, boolean sameInstance) {
    }

    /**
     * C1. 같은 키를 두 요청이 거의 동시에 조회한다. 첫 로드가 끝나기 전에(Gate 가 닫힌 동안) 두 번째 요청이 온다.
     * 로더는 몇 번 호출되는가? 두 요청이 받은 future 는 같은 객체인가({@code a == b})?
     */
    public static DedupResult c1(Executor loaderPool) {
        AsyncCache<String, String> cache = Caffeine.newBuilder().executor(loaderPool).buildAsync();
        AtomicInteger calls = new AtomicInteger();
        Gate dbResponds = new Gate();
        Function<String, String> loader = id -> {
            calls.incrementAndGet();
            dbResponds.await();
            return "product-" + id;
        };
        CompletableFuture<String> a = cache.get("p1", loader);
        CompletableFuture<String> b = cache.get("p1", loader);
        dbResponds.open();
        a.join();
        b.join();
        return new DedupResult(calls.get(), a == b);
    }

    /**
     * C2. executor 를 지정하지 않은 캐시. 동기 로더({@code Function})는 어느 스레드에서 실행되는가("loader")?
     * 로드가 끝나기 전에 붙인 non-async 콜백은 어디서 실행되는가("whileLoading")?
     */
    public static CompletableFuture<String> c2(ThreadRecorder rec) {
        AsyncCache<String, String> cache = Caffeine.newBuilder().buildAsync();
        Gate dbResponds = new Gate();
        CompletableFuture<String> f = cache.get("p1", id -> {
            rec.mark("loader");
            dbResponds.await();
            return "product-" + id;
        });
        CompletableFuture<String> g = f.thenApply(name -> {
            rec.mark("whileLoading");
            return name.toUpperCase();
        });
        dbResponds.open();
        return g;
    }

    /**
     * C3. 이번에는 "비동기 매핑 함수" {@code (key, executor) -> CompletableFuture} 를 넘긴다.
     * 이 함수 자체("mappingFn")는 어느 스레드에서 호출되는가?
     */
    public static CompletableFuture<String> c3(ThreadRecorder rec, Executor loaderPool) {
        AsyncCache<String, String> cache = Caffeine.newBuilder().executor(loaderPool).buildAsync();
        return cache.get("p1", (id, executor) -> {
            rec.mark("mappingFn");
            return CompletableFuture.supplyAsync(() -> "product-" + id, executor);
        });
    }

    /** C4 결과. */
    public record FailureResult(boolean presentAfterFailure, int loaderCalls) {
    }

    /**
     * C4. 로더가 실패한다(DB 장애). 실패 직후 {@code getIfPresent} 는 무엇을 돌려주는가(값이 있는가)?
     * 다시 {@code get} 하면 로더는 다시 호출되는가? (총 호출 횟수)
     */
    public static FailureResult c4(Executor loaderPool) {
        AsyncCache<String, String> cache = Caffeine.newBuilder().executor(loaderPool).buildAsync();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> first = cache.get("p1", id -> {
            calls.incrementAndGet();
            throw new IllegalStateException("DB down");
        });
        first.handle((v, ex) -> null).join();
        Sleep.ms(100); // (결정성 장치) 완료 후처리가 끝날 시간을 준다
        boolean present = cache.getIfPresent("p1") != null;
        cache.get("p1", id -> {
            calls.incrementAndGet();
            return "product-" + id;
        }).join();
        return new FailureResult(present, calls.get());
    }

    /**
     * C5. 요청 A 는 "나는 50ms 만 기다리겠다"며 받은 future 에 {@code orTimeout} 을 걸었다.
     * 같은 키를 조회한 요청 B 는 타임아웃을 걸지 않았다. 로더는 300ms 뒤 정상 응답한다.
     * B 의 결과는? ({@code "value:<값>"} 또는 원인 예외의 단순 클래스 이름)
     */
    public static String c5(Executor loaderPool) {
        AsyncCache<String, String> cache = Caffeine.newBuilder().executor(loaderPool).buildAsync();
        Gate dbResponds = new Gate();
        CompletableFuture<String> forA = cache.get("p1", id -> {
            dbResponds.await();
            return "product-" + id;
        });
        forA.orTimeout(50, TimeUnit.MILLISECONDS); // 요청 A 의 "내 타임아웃"
        CompletableFuture<String> forB = cache.get("p1", id -> "product-" + id);
        forA.handle((v, ex) -> null).join(); // A 의 타임아웃이 발화할 때까지 기다린다
        dbResponds.open();
        return forB.handle(Threads::outcome).join();
    }

    /** C6 결과: 각 호출에 걸린 시간(ms). */
    public record SyncViewResult(long getIfPresentMs, long getMs) {
    }

    /**
     * C6. 200ms 걸리는 로드가 진행 중인 키를 {@code synchronous()} 뷰로 조회한다.
     * {@code getIfPresent(key)} 와 {@code get(key, fn)} 은 각각 얼마나 걸리는가?
     */
    public static SyncViewResult c6(Executor loaderPool) {
        AsyncCache<String, String> cache = Caffeine.newBuilder().executor(loaderPool).buildAsync();
        cache.put("p1", CompletableFuture.supplyAsync(() -> {
            Sleep.ms(200);
            return "product-p1";
        }, loaderPool));
        long t0 = System.nanoTime();
        cache.synchronous().getIfPresent("p1");
        long getIfPresentMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        cache.synchronous().get("p1", id -> "fallback");
        long getMs = (System.nanoTime() - t1) / 1_000_000;
        return new SyncViewResult(getIfPresentMs, getMs);
    }
}
