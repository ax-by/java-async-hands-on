package async.m06;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Gate;
import async.harness.Sleep;
import async.harness.StubHttpServer;
import async.harness.ThreadRecorder;

/**
 * m06 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>등장인물
 * <ul>
 *   <li>{@code server}: {@link StubHttpServer}. 실제 소켓으로 응답하는 "느린 원격 서버". 경로마다 지연시간이 있다.</li>
 *   <li>{@code HttpClient}: JDK 의 진짜 비동기 HTTP 클라이언트({@code sendAsync}).</li>
 *   <li>{@code redis}: {@link FakeEventLoopClient}. Lettuce 같은 Netty 기반 클라이언트의 대역.
 *       이벤트 루프 스레드 {@code event-loop-1} 하나가 모든 응답을 처리하고 future 를 완료시킨다. 기본 지연 100ms.</li>
 *   <li>풀 이름: {@code http-worker}(HttpClient 에 준 실행기), {@code worker}, {@code jdbc}, {@code reloader}, {@code io}.
 *       테스트 메서드를 실행하는 스레드는 {@code On.caller()} 다.</li>
 * </ul>
 *
 * <p>"violation" 예측은 BlockHound 가 이벤트 루프({@code event-loop-*}) 위에서 blocking 호출을 <b>감지했는가</b>(true/false)다.
 */
public final class Scenarios {

    private Scenarios() {
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).build();
    }

    // ------------------------------------------------------------------------------------------------
    // 1. JDK HttpClient 의 future 는 어느 스레드가 완료시키는가
    // ------------------------------------------------------------------------------------------------

    /** S01. 기본 설정 HttpClient. 응답(100ms)이 오기 전에 non-async 콜백을 붙인다. map 은 어디서 실행될까? */
    public static CompletableFuture<String> s01(ThreadRecorder rec, StubHttpServer server) {
        server.route("/s01", 100, 200, "s01");
        HttpClient client = HttpClient.newHttpClient();
        return client.sendAsync(get(server.uri("/s01")), BodyHandlers.ofString())
                .thenApply(res -> {
                    rec.mark("map");
                    return res.body();
                });
    }

    /**
     * S02. 실행기를 직접 준 HttpClient({@code executor(httpWorker)}).
     * 응답 본문을 String 으로 바꾸는 BodySubscriber(body)와, 결과 future 에 붙인 non-async 콜백(map)은 각각 어디서 실행될까?
     */
    public static CompletableFuture<String> s02(ThreadRecorder rec, StubHttpServer server, Executor httpWorker) {
        server.route("/s02", 100, 200, "s02");
        HttpClient client = HttpClient.newBuilder().executor(httpWorker).build();
        BodyHandler<String> handler = info -> BodySubscribers.mapping(BodySubscribers.ofString(UTF_8), body -> {
            rec.mark("body");
            return body;
        });
        return client.sendAsync(get(server.uri("/s02")), handler)
                .thenApply(res -> {
                    rec.mark("map");
                    return res.body();
                });
    }

    // ------------------------------------------------------------------------------------------------
    // 2. 이벤트 루프 규율 (Netty 계열 클라이언트)
    // ------------------------------------------------------------------------------------------------

    /** S03. Netty 계열 클라이언트의 future 에 응답 전에 non-async 콜백을 붙인다. */
    public static CompletableFuture<String> s03(ThreadRecorder rec, FakeEventLoopClient redis) {
        return redis.fetch("cart:1").thenApply(v -> {
            rec.mark("map");
            return v;
        });
    }

    /**
     * S04. 장바구니 응답(100ms)을 받는 콜백에서 감사 로그를 DB 에 쓴다(auditDb: 300ms blocking).
     * 같은 클라이언트로 보낸 <b>관계없는</b> ping 요청의 응답은 150ms 에 도착할 예정이다. ping 은 실제로 몇 ms 에 처리될까?
     */
    public static CompletableFuture<String> s04(ThreadRecorder rec, FakeEventLoopClient redis, FakeBlockingService auditDb) {
        redis.latencyFor("cart:1", 100).latencyFor("ping", 150);
        CompletableFuture<String> cart = redis.fetch("cart:1")
                .thenApply(v -> {
                    auditDb.call(v);
                    return v;
                });
        CompletableFuture<String> ping = redis.fetch("ping")
                .whenComplete((v, ex) -> rec.mark("pingDone"));
        return cart.thenCombine(ping, (a, b) -> a + "," + b);
    }

    /** S05. S04 와 같지만 감사 로그 기록을 jdbc 풀로 넘긴다. */
    public static CompletableFuture<String> s05(ThreadRecorder rec, FakeEventLoopClient redis, FakeBlockingService auditDb,
                                                Executor jdbc) {
        redis.latencyFor("cart:1", 100).latencyFor("ping", 150);
        CompletableFuture<String> cart = redis.fetch("cart:1")
                .thenApplyAsync(v -> {
                    auditDb.call(v);
                    return v;
                }, jdbc);
        CompletableFuture<String> ping = redis.fetch("ping")
                .whenComplete((v, ex) -> rec.mark("pingDone"));
        return cart.thenCombine(ping, (a, b) -> a + "," + b);
    }

    /**
     * S06. 설정값 future 는 worker 가 이미 완료시켜 두었다(캐시). 장바구니 응답을 받은 뒤 그 캐시 future 에 콜백을 붙인다.
     * apply 는 어디서 실행될까?
     */
    public static CompletableFuture<String> s06(ThreadRecorder rec, FakeEventLoopClient redis, Executor worker) {
        CompletableFuture<String> cachedConfig = CompletableFuture.supplyAsync(() -> "cfg", worker);
        cachedConfig.join();
        return redis.fetch("cart:1").thenCompose(cart -> cachedConfig.thenApply(cfg -> {
            rec.mark("apply");
            return cart + "+" + cfg;
        }));
    }

    /**
     * S07. 이벤트 루프에서 벗어나려고 thenApplyAsync(.., worker) 를 붙였다. 그런데 이번에는 Redis 조회가 <b>실패</b>한다.
     * 실패 경로의 exceptionally(fallback) 는 어디서 실행될까?
     */
    public static CompletableFuture<String> s07(ThreadRecorder rec, FakeEventLoopClient redis, Executor worker) {
        redis.failOn("cart:9", new IllegalStateException("redis down"));
        return redis.fetch("cart:9")
                .thenApplyAsync(v -> {
                    rec.mark("parse");
                    return v;
                }, worker)
                .exceptionally(ex -> {
                    rec.mark("fallback");
                    return "empty-cart";
                });
    }

    // ------------------------------------------------------------------------------------------------
    // 3. "비동기처럼 보이는" 코드 속의 blocking (R4)
    // ------------------------------------------------------------------------------------------------

    /** 이름도 반환 타입도 비동기다. 본문은? */
    static CompletableFuture<String> loadProfileAsync(FakeBlockingService profileDb, String userId) {
        return CompletableFuture.completedFuture(profileDb.call(userId));
    }

    /**
     * S08. profileDb 는 200ms 걸린다.
     * (1) caller 가 loadProfileAsync 를 호출하고 반환받기까지 몇 ms 걸릴까? → "returned"
     * (2) 이벤트 루프 위의 thenCompose 안에서 loadProfileAsync 를 호출하면 BlockHound 가 감지할까?
     */
    public static CompletableFuture<String> s08(ThreadRecorder rec, FakeEventLoopClient redis, FakeBlockingService profileDb) {
        CompletableFuture<String> direct = loadProfileAsync(profileDb, "u1");
        rec.mark("returned");
        CompletableFuture<String> onLoop = redis.fetch("session:u2").thenCompose(s -> loadProfileAsync(profileDb, "u2"));
        return direct.thenCombine(onLoop, (a, b) -> a + "," + b);
    }

    /**
     * S09. 주문 응답(50ms)을 받은 콜백에서, 같은 클라이언트로 가격을 조회하고(50ms) 최대 300ms 기다린다.
     * 결과 future 는 어떻게 끝날까? (성공이면 "OK", 실패면 unwrap 한 원인 예외의 클래스 이름)
     */
    public static CompletableFuture<String> s09(FakeEventLoopClient redis) {
        redis.latencyFor("order:1", 50).latencyFor("price:1", 50);
        return redis.fetch("order:1").thenApply(order -> {
            try {
                String price = redis.fetch("price:1").get(300, TimeUnit.MILLISECONDS);
                return order + "|" + price;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** S10 에서 쓰는 재고 캐시. reload 는 300ms 동안 잠금을 쥐고 있다. */
    public static final class StockCache {
        private final ReentrantLock lock = new ReentrantLock();

        synchronized void reloadSynchronized(Gate holding) {
            holding.open();
            Sleep.ms(300);
        }

        synchronized String getSynchronized(String key) {
            return "stock:" + key;
        }

        void reloadLocked(Gate holding) {
            lock.lock();
            try {
                holding.open();
                Sleep.ms(300);
            } finally {
                lock.unlock();
            }
        }

        String getLocked(String key) {
            lock.lock();
            try {
                return "stock:" + key;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * S10a. reloader 스레드가 캐시를 300ms 동안 다시 읽는 중이다(synchronized). 그사이 이벤트 루프 콜백이 같은 캐시를 읽는다.
     * 이벤트 루프는 약 200ms 동안 멈춘다. BlockHound 가 감지할까?
     */
    public static CompletableFuture<String> s10Synchronized(FakeEventLoopClient redis, Executor reloader) {
        StockCache cache = new StockCache();
        Gate holding = new Gate();
        reloader.execute(() -> cache.reloadSynchronized(holding));
        holding.await();
        return redis.fetch("item:1").thenApply(cache::getSynchronized);
    }

    /** S10b. S10a 와 같지만 ReentrantLock 을 쓴다. BlockHound 가 감지할까? */
    public static CompletableFuture<String> s10Locked(FakeEventLoopClient redis, Executor reloader) {
        StockCache cache = new StockCache();
        Gate holding = new Gate();
        reloader.execute(() -> cache.reloadLocked(holding));
        holding.await();
        return redis.fetch("item:1").thenApply(cache::getLocked);
    }

    // ------------------------------------------------------------------------------------------------
    // 4. B 에서의 타임아웃과 취소 (R10)
    // ------------------------------------------------------------------------------------------------

    /**
     * S11. 300ms 걸리는 조회에 100ms 타임아웃을 걸고 폴백한다. 결과는 100ms 에 "fallback" 이다.
     * 그렇다면 이벤트 루프는 300ms 시점에 그 응답을 여전히 처리할까? (redis.completedCount() 가 1 이 되는가)
     */
    public static CompletableFuture<String> s11(FakeEventLoopClient redis) {
        redis.latencyFor("slow", 300);
        return redis.fetch("slow")
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> "fallback");
    }

    /** S12 의 네 가지 중단 시도. */
    public enum CancelMode {
        /** sendAsync 가 준 future f 에 f.cancel(true) */
        CANCEL_TRUE,
        /** f.cancel(false) */
        CANCEL_FALSE,
        /** f.thenApply(HttpResponse::body) 로 만든 <b>파생</b> future 에 cancel(true) */
        DERIVED_CANCEL_TRUE,
        /** f.orTimeout(100ms) */
        OR_TIMEOUT
    }

    /**
     * S12 관찰 결과.
     * @param serverReceived     원격 서버가 요청을 받았는가
     * @param bodyHandlerInvoked 응답 헤더를 받아 BodyHandler 가 호출됐는가 (= HTTP 교환이 중단되지 않고 계속됐는가)
     * @param fIsCancelled       sendAsync 가 준 future f 의 isCancelled()
     */
    public record Exchange(boolean serverReceived, boolean bodyHandlerInvoked, boolean fIsCancelled) {
    }

    /**
     * S12. 서버는 500ms 후에 응답한다. 요청이 서버에 도착한 것을 확인한 뒤(결정성 장치) mode 대로 중단을 시도하고,
     * 800ms 를 더 기다린 뒤 관찰한다. HTTP 교환이 살아 있다면 그사이 응답 헤더가 도착해 BodyHandler 가 호출된다.
     */
    public static Exchange s12(StubHttpServer server, HttpClient client, CancelMode mode) {
        server.route("/s12", 500, 200, "s12");
        AtomicBoolean handlerInvoked = new AtomicBoolean();
        BodyHandler<String> handler = info -> {
            handlerInvoked.set(true);
            return BodySubscribers.ofString(UTF_8);
        };
        int before = server.requestCount();
        CompletableFuture<HttpResponse<String>> f = client.sendAsync(get(server.uri("/s12")), handler);
        CompletableFuture<String> body = f.thenApply(HttpResponse::body);
        awaitServerReceived(server, before);
        switch (mode) {
            case CANCEL_TRUE -> f.cancel(true);
            case CANCEL_FALSE -> f.cancel(false);
            case DERIVED_CANCEL_TRUE -> body.cancel(true);
            case OR_TIMEOUT -> f.orTimeout(100, TimeUnit.MILLISECONDS);
            default -> throw new IllegalArgumentException(String.valueOf(mode));
        }
        Sleep.ms(800);
        return new Exchange(server.requestCount() > before, handlerInvoked.get(), f.isCancelled());
    }

    private static void awaitServerReceived(StubHttpServer server, int before) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.requestCount() == before) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("요청이 2초 안에 서버에 도착하지 않았다 (시나리오 환경 문제)");
            }
            Sleep.ms(1);
        }
    }

    /**
     * S13. 서버는 500ms 후에 응답한다. 요청 자체에 100ms 타임아웃을 건다(HttpRequest.Builder.timeout).
     * 결과 future 는 어떻게 끝날까? (unwrap 한 원인 예외의 클래스 이름)
     */
    public static CompletableFuture<String> s13(StubHttpServer server, HttpClient client) {
        server.route("/s13", 500, 200, "s13");
        HttpRequest request = HttpRequest.newBuilder(server.uri("/s13")).timeout(Duration.ofMillis(100)).build();
        return client.sendAsync(request, BodyHandlers.ofString()).thenApply(HttpResponse::body);
    }

    // ------------------------------------------------------------------------------------------------
    // 5. 스레드 효율: A vs B
    // ------------------------------------------------------------------------------------------------

    /**
     * S14 관찰 결과.
     * @param millis              전체 소요시간
     * @param serverMaxConcurrent 원격 서버가 <b>동시에</b> 처리한 요청 수의 최댓값
     */
    public record FanOut(long millis, int serverMaxConcurrent) {
    }

    /** S14a (A). 200ms 걸리는 원격 호출 n 개를, blocking send() 로 io 풀(스레드 10개)에서 병렬 실행한다. */
    public static FanOut s14Blocking(StubHttpServer server, HttpClient client, Executor io, int n) {
        server.route("/s14", 200, 200, "ok");
        HttpRequest request = get(server.uri("/s14"));
        long start = System.nanoTime();
        List<CompletableFuture<String>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return client.send(request, BodyHandlers.ofString()).body();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, io));
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).join();
        return new FanOut((System.nanoTime() - start) / 1_000_000, server.maxConcurrentRequests());
    }

    /** S14b (B). 같은 n 개 호출을 sendAsync 로 보낸다. HttpClient 의 실행기는 스레드 2개(http-worker)다. */
    public static FanOut s14NonBlocking(StubHttpServer server, HttpClient client, int n) {
        server.route("/s14", 200, 200, "ok");
        HttpRequest request = get(server.uri("/s14"));
        long start = System.nanoTime();
        List<CompletableFuture<String>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(client.sendAsync(request, BodyHandlers.ofString()).thenApply(HttpResponse::body));
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).join();
        return new FanOut((System.nanoTime() - start) / 1_000_000, server.maxConcurrentRequests());
    }
}
