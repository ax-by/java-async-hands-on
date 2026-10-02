package async.m10;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m10.caffeine.ProductCacheFactory;
import async.m10.caffeine.TimeoutAwareProductCache;

/** E1, E2 채점. */
@Timeout(20)
class CaffeineExercisesTest {

    @Test
    void e1_loaderRunsOnLoaderPool_andConcurrentRequestsShareOneLoad() throws Exception {
        FakeBlockingService repo = new FakeBlockingService("product-db", 150);
        try (NamedPool loaderPool = Pools.fixed("cache-loader", 4)) {
            AsyncLoadingCache<String, String> cache = ProductCacheFactory.create(repo, loaderPool);
            List<CompletableFuture<String>> requests = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                requests.add(cache.get("p1"));
            }
            for (CompletableFuture<String> r : requests) {
                assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("product-db:p1");
            }
            assertThat(repo.callCount()).as("같은 키 동시 요청 5개는 DB 를 한 번만 불러야 한다 (O2)").isEqualTo(1);
            assertThat(repo.callerThreads())
                    .as("blocking 로더는 loaderPool(cache-loader-N)에서 실행돼야 한다 (R11, O1)")
                    .allMatch(t -> t.startsWith("cache-loader-"));
        }
    }

    @Test
    void e1_failedLoadIsNotCached_andPolicyIsConfigured() throws Exception {
        FakeBlockingService repo = new FakeBlockingService("product-db", 10).failOn("p2", new IllegalStateException("DB down"));
        try (NamedPool loaderPool = Pools.fixed("cache-loader", 2)) {
            AsyncLoadingCache<String, String> cache = ProductCacheFactory.create(repo, loaderPool);
            assertThatThrownBy(() -> cache.get("p2").get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            // 실패 직후에는 항목이 아직 남아 있을 수 있다(가이드 1장 읽기 과제 4). 잠깐 기다린 뒤 재요청한다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (cache.getIfPresent("p2") != null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            cache.get("p2").handle((v, ex) -> null).get(2, TimeUnit.SECONDS);
            assertThat(repo.callCount()).as("실패한 로드는 캐시에 남지 않으므로 두 번째 요청이 다시 로드해야 한다 (O2)").isEqualTo(2);

            var policy = cache.synchronous().policy();
            assertThat(policy.eviction()).as("maximumSize(10_000) 를 설정해야 한다").isPresent();
            assertThat(policy.eviction().get().getMaximum()).isEqualTo(10_000);
            assertThat(policy.expireAfterWrite()).as("expireAfterWrite(10분) 을 설정해야 한다").isPresent();
            assertThat(policy.expireAfterWrite().get().getExpiresAfter(TimeUnit.MINUTES)).isEqualTo(10);
        }
    }

    private static String valueOrFailure(String value, Throwable error) {
        return error == null ? value : "실패: " + Threads.unwrap(error);
    }

    private static AsyncLoadingCache<String, String> gatedCache(NamedPool pool, Gate dbResponds, AtomicInteger loads) {
        return Caffeine.newBuilder().executor(pool).buildAsync(id -> {
            loads.incrementAndGet();
            dbResponds.await();
            return "product-" + id;
        });
    }

    @Test
    void e2_shortTimeoutOfOneCaller_doesNotPoisonOthers() throws Exception {
        try (NamedPool pool = Pools.fixed("cache-loader", 2)) {
            Gate dbResponds = new Gate();
            AtomicInteger loads = new AtomicInteger();
            AsyncLoadingCache<String, String> cache = gatedCache(pool, dbResponds, loads);
            TimeoutAwareProductCache facade = new TimeoutAwareProductCache(cache);

            CompletableFuture<String> impatient = facade.getAsync("p1", Duration.ofMillis(50));
            CompletableFuture<String> patient = facade.getAsync("p1", Duration.ofSeconds(3));

            Throwable error = impatient.handle((v, ex) -> ex).get(2, TimeUnit.SECONDS);
            assertThat(error).as("짧은 타임아웃 호출자는 TimeoutException 으로 실패해야 한다").isNotNull();
            assertThat(Threads.unwrap(error)).isInstanceOf(TimeoutException.class);

            dbResponds.open();
            assertThat(patient.handle(CaffeineExercisesTest::valueOrFailure).get(2, TimeUnit.SECONDS))
                    .as("다른 호출자는 정상 값을 받아야 한다. getAsync 가 cache.get 이 준 future 에 직접 타임아웃을 걸고 있지 않은가? (C1, C5, O2)")
                    .isEqualTo("product-p1");
            assertThat(loads.get()).as("로드는 한 번만 일어나야 한다").isEqualTo(1);
            assertThat(cache.synchronous().getIfPresent("p1")).as("값은 캐시에 남아야 한다 (실패로 제거되면 안 된다)").isEqualTo("product-p1");
        }
    }

    @Test
    void e2_cancelByOneCaller_doesNotAffectOthers() throws Exception {
        try (NamedPool pool = Pools.fixed("cache-loader", 2)) {
            Gate dbResponds = new Gate();
            AtomicInteger loads = new AtomicInteger();
            AsyncLoadingCache<String, String> cache = gatedCache(pool, dbResponds, loads);
            TimeoutAwareProductCache facade = new TimeoutAwareProductCache(cache);

            CompletableFuture<String> leaving = facade.getAsync("p1", Duration.ofSeconds(3));
            CompletableFuture<String> staying = facade.getAsync("p1", Duration.ofSeconds(3));
            leaving.cancel(true); // 클라이언트 연결이 끊긴 요청

            dbResponds.open();
            assertThat(staying.handle(CaffeineExercisesTest::valueOrFailure).get(2, TimeUnit.SECONDS))
                    .as("한 호출자의 cancel 이 다른 호출자에게 영향을 주면 안 된다. getAsync 가 cache.get 이 준 future 를 그대로 돌려주고 있지 않은가? (C1, O2, R10)")
                    .isEqualTo("product-p1");
            assertThat(loads.get()).isEqualTo(1);
            assertThat(cache.synchronous().getIfPresent("p1")).isEqualTo("product-p1");
        }
    }
}
