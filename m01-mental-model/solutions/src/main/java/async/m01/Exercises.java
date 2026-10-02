package async.m01;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

import async.harness.FakeBlockingService;

/**
 * m01 구현 실습. 각 메서드의 TODO 를 구현한다. 채점: ExercisesTest
 */
public final class Exercises {

    private Exercises() {
    }

    /**
     * E1. fn 이 future 의 완료 타이밍과 상관없이 <b>항상 executor 에서</b> 실행되도록 변환하라.
     * (future 가 이미 완료됐든, 다른 스레드가 나중에 완료시키든 상관없이)
     */
    public static <T, R> CompletableFuture<R> mapOn(CompletableFuture<T> future, Function<T, R> fn, Executor executor) {
        // TODO(E1): R1 과 R2 중 어느 규칙이 "항상"을 보장하는가?
        // SOLUTION-BEGIN
        return future.thenApplyAsync(fn, executor);
        // SOLUTION-END
    }

    /**
     * E2. 두 blocking 서비스를 key 로 <b>동시에</b> 호출하고 "{a 결과}|{b 결과}" 로 합쳐라.
     * 호출 스레드는 막히면 안 된다(즉시 future 를 반환). 총 소요시간은 두 지연시간의 합이 아니라 최댓값이어야 한다.
     */
    public static CompletableFuture<String> callBoth(FakeBlockingService a, FakeBlockingService b, String key, Executor io) {
        // TODO(E2): 두 작업을 "먼저 모두 시작"한 뒤 합친다 (R3, R7)
        // SOLUTION-BEGIN
        CompletableFuture<String> fa = CompletableFuture.supplyAsync(() -> a.call(key), io);
        CompletableFuture<String> fb = CompletableFuture.supplyAsync(() -> b.call(key), io);
        return fa.thenCombine(fb, (x, y) -> x + "|" + y);
        // SOLUTION-END
    }

    /** 콜백 방식의 비동기 API. 결과나 예외 중 하나가 정확히 한 번, API 내부 스레드에서 전달된다. */
    public interface CallbackApi {
        void fetch(String key, BiConsumer<String, Throwable> callback);
    }

    /**
     * E3. 콜백 방식 API 를 CompletableFuture 로 감싸라.
     * 성공이면 결과로, 실패면 그 예외로 future 를 완료시킨다.
     * 생각해 볼 것: 이 future 에 붙은 non-async 콜백은 어느 스레드에서 실행될까? (R1)
     */
    public static CompletableFuture<String> fromCallback(CallbackApi api, String key) {
        // TODO(E3): new CompletableFuture<>() 와 complete / completeExceptionally
        // SOLUTION-BEGIN
        CompletableFuture<String> result = new CompletableFuture<>();
        api.fetch(key, (value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
        });
        return result;
        // SOLUTION-END
    }
}
