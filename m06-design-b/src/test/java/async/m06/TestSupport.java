package async.m06;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import async.harness.BlockHoundSupport;
import async.harness.Pools;
import async.harness.Timed;

/** 채점 보조. 학습자 구현이 멈추거나 오래 걸려도 테스트가 "왜" 실패했는지 알려 준다. */
final class TestSupport {

    private TestSupport() {
    }

    /**
     * call 을 별도 스레드에서 실행하고 반환까지 걸린 시간을 잰다.
     * 2초 안에 반환하지 않으면 "호출 스레드에서 기다렸다"는 메시지로 실패한다.
     */
    static <T> Timed<CompletableFuture<T>> invoke(Supplier<CompletableFuture<T>> call) {
        ExecutorService caller = Executors.newSingleThreadExecutor(Pools.namedFactory("test-caller"));
        try {
            Future<Timed<CompletableFuture<T>>> f = caller.submit(() -> Timed.of(call));
            return f.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("2초가 지나도 메서드가 반환하지 않았다. future 를 반환하기 전에 호출 스레드에서 "
                    + "응답을 기다렸다(join/get/send). B 에서는 즉시 반환해야 한다 (R3, R5)", e);
        } catch (Exception e) {
            throw new AssertionError("메서드 호출 중 예외: " + e.getCause(), e.getCause() != null ? e.getCause() : e);
        } finally {
            caller.shutdown();
        }
    }

    /** future 의 결과를 최대 seconds 초 기다린다. 시간 안에 끝나지 않으면 이유 후보를 알려 주며 실패한다. */
    static <T> T await(CompletableFuture<T> f, long seconds) throws Exception {
        return await(f, seconds, "I/O 스레드(nonblocking-io)가 자기가 전달해야 할 응답을 "
                + "콜백 안에서 join/get 으로 기다리고 있지 않은가? (R1, R4, R5) 위반 기록: " + BlockHoundSupport.violations());
    }

    static <T> T await(CompletableFuture<T> f, long seconds, String why) throws Exception {
        try {
            return f.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(seconds + "초 안에 끝나지 않았다. " + why, e);
        }
    }

    interface Work {
        void run() throws Exception;
    }

    /** work 를 별도 스레드에서 실행하고 2초 안에 끝나지 않으면 why 로 실패한다. (학습자 코드가 막혀도 테스트가 멈추지 않게) */
    static void within2s(String why, Work work) throws Exception {
        ExecutorService runner = Executors.newSingleThreadExecutor(Pools.namedFactory("test-runner"));
        try {
            runner.submit(() -> {
                work.run();
                return null;
            }).get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("2초 안에 끝나지 않았다. " + why, e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw (Exception) e.getCause();
        } finally {
            runner.shutdownNow();
        }
    }
}
