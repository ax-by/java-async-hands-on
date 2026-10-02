package async.m10.lettuce;

import java.util.concurrent.CompletableFuture;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.ThreadRecorder;
import async.m10.Threads;

/**
 * 4장 Lettuce 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽기 가이드: {@code guides/04-lettuce.md}
 *
 * <p>테스트가 로컬 가짜 Redis 서버(src/test/.../FakeRedisServer, RESP2)를 띄우고 Lettuce 7 로 <b>진짜 TCP 연결</b>을 맺는다.
 * 키가 {@code held:} 로 시작하면 서버는 {@code serverReleases} 게이트가 열릴 때까지 응답을 보내지 않는다
 * (= "응답이 오기 전에 콜백을 등록했다"를 보장하는 장치).
 */
public final class LettuceScenarios {

    private LettuceScenarios() {
    }

    /**
     * L1. 응답이 오기 전에 {@code RedisFuture.thenApply} 를 등록한다. 콜백("callback")은 어느 스레드에서 실행되는가?
     */
    public static CompletableFuture<String> l1(ThreadRecorder rec, RedisAsyncCommands<String, String> redis, Gate serverReleases) {
        CompletableFuture<String> name = redis.get("held:product:1").thenApply(v -> {
            rec.mark("callback");
            return v;
        }).toCompletableFuture();
        serverReleases.open();
        return name;
    }

    /**
     * L2. 같은 연결에서 GET 응답 콜백 안에 300ms 걸리는 감사 로그 INSERT(JDBC)를 넣었다.
     * 그 콜백이 실행 중일 때 <b>같은 연결</b>로 보낸 다른 GET("product:2", 서버는 즉시 응답)은 몇 ms 뒤에 완료되는가?
     */
    public static long l2(RedisAsyncCommands<String, String> redis, Gate serverReleases) {
        FakeBlockingService auditDb = new FakeBlockingService("audit-db", 300);
        Gate callbackStarted = new Gate();
        redis.get("held:product:1").thenAccept(v -> {
            callbackStarted.open();
            auditDb.call("viewed:" + v); // blocking INSERT
        });
        serverReleases.open();
        callbackStarted.await();
        long start = System.nanoTime();
        redis.get("product:2").toCompletableFuture().join();
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * L3. 비동기 GET 의 콜백 안에서 <b>같은 연결의 sync API</b> 로 다른 키를 읽는다. 명령 타임아웃은 300ms 다.
     * 결과는? ({@code "value:<값>"} 또는 원인 예외의 단순 클래스 이름)
     */
    public static String l3(StatefulRedisConnection<String, String> connection, Gate serverReleases) {
        CompletableFuture<String> result = connection.async().get("held:product:1")
                .thenApply(v -> connection.sync().get("product:2"))
                .toCompletableFuture();
        serverReleases.open();
        return result.handle(Threads::outcome).join();
    }

    /**
     * L4. {@code ClientOptions.publishOnScheduler(true)} 로 만든 연결이다.
     * async API 콜백("async")과 reactive API 의 onNext("reactive")는 각각 어느 스레드에서 실행되는가?
     */
    public static CompletableFuture<Void> l4(ThreadRecorder rec, StatefulRedisConnection<String, String> connection, Gate serverReleases) {
        CompletableFuture<String> async = connection.async().get("held:product:1").thenApply(v -> {
            rec.mark("async");
            return v;
        }).toCompletableFuture();
        CompletableFuture<String> reactive = connection.reactive().get("held:product:2")
                .doOnNext(v -> rec.mark("reactive"))
                .toFuture();
        serverReleases.open();
        return CompletableFuture.allOf(async, reactive);
    }
}
