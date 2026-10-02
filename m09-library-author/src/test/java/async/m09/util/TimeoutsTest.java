package async.m09.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.On;
import async.harness.Pools;
import async.harness.Sleep;
import async.harness.ThreadRecorder;

/** E2 채점: Timeouts.failAfter (L5 타이머 정리, R10). */
@Timeout(5)
class TimeoutsTest {

    ScheduledThreadPoolExecutor timer;

    @BeforeEach
    void setUp() {
        timer = new ScheduledThreadPoolExecutor(1, Pools.namedFactory("minikv-timer"));
        timer.setRemoveOnCancelPolicy(true);
    }

    @AfterEach
    void tearDown() {
        timer.shutdownNow();
    }

    @Test
    void e2_returnsTheSameFuture() {
        CompletableFuture<String> f = new CompletableFuture<>();
        assertThat(Timeouts.failAfter(f, 1, TimeUnit.SECONDS, timer)).as("orTimeout 처럼 같은 future 를 반환한다").isSameAs(f);
        f.complete("v");
    }

    @Test
    void e2_timesOutWithUnwrappedTimeoutException_onTimerThread() {
        ThreadRecorder rec = ThreadRecorder.start();
        CompletableFuture<String> never = new CompletableFuture<>();
        long start = System.nanoTime();
        Throwable seen = Timeouts.failAfter(never, 100, TimeUnit.MILLISECONDS, timer)
                .handle((v, ex) -> {
                    rec.mark("handle");
                    return ex;
                }).join();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(seen).as("[R8] completeExceptionally(new TimeoutException()) 로 직접 실패시켜야 한다(감싸지 않음)")
                .isExactlyInstanceOf(TimeoutException.class);
        assertThat(elapsed).as("[R10] 100ms 타임아웃").isBetween(80L, 600L);
        assertThat(rec.on("handle")).as("[R1, R10] 타임아웃이 future 를 완료시키므로 콜백은 timer 스레드에서 실행된다")
                .isEqualTo(On.pool("minikv-timer"));
    }

    @Test
    void e2_completedFirst_timerTaskIsCancelled_noLeak() {
        for (int i = 0; i < 100; i++) {
            CompletableFuture<String> f = new CompletableFuture<>();
            Timeouts.failAfter(f, 10, TimeUnit.SECONDS, timer);
            f.complete("v");
        }
        assertThat(timer.getQueue().size())
                .as("[L5] 응답이 먼저 오면 타이머 작업을 cancel 해야 한다. 그러지 않으면 요청마다 10초 동안 큐에 남는다(누수)")
                .isZero();
    }

    @Test
    void e2_alreadyCompletedFuture_leavesNoTimer() {
        Timeouts.failAfter(CompletableFuture.completedFuture("v"), 10, TimeUnit.SECONDS, timer);
        assertThat(timer.getQueue().size()).as("[L5, R1] 이미 완료된 future 라면 whenComplete 가 즉시 실행되어 타이머를 지운다").isZero();
    }

    @Test
    void e2_onTimeoutRunsOnlyWhenTimeoutWins() {
        AtomicInteger aborted = new AtomicInteger();

        CompletableFuture<String> slow = new CompletableFuture<>();
        Timeouts.failAfter(slow, 50, TimeUnit.MILLISECONDS, timer, aborted::incrementAndGet);
        slow.handle((v, ex) -> ex).join();
        Sleep.ms(50);
        assertThat(aborted.get()).as("[L5] 타임아웃이 이기면 onTimeout(하부 요청 취소)을 정확히 한 번 실행한다").isEqualTo(1);

        CompletableFuture<String> fast = new CompletableFuture<>();
        Timeouts.failAfter(fast, 50, TimeUnit.MILLISECONDS, timer, aborted::incrementAndGet);
        fast.complete("v");
        Sleep.ms(150);
        assertThat(aborted.get()).as("[L5] 응답이 먼저 오면 onTimeout 을 실행하면 안 된다(이미 받은 요청을 취소하지 마라)").isEqualTo(1);
        assertThat(fast.join()).isEqualTo("v");
    }

    @Test
    void e2_timeoutDoesNotOverrideAnEarlierResult() {
        CompletableFuture<String> f = new CompletableFuture<>();
        Timeouts.failAfter(f, 50, TimeUnit.MILLISECONDS, timer);
        f.complete("v");
        Sleep.ms(150);
        assertThat(f.join()).as("이미 완료된 future 를 타임아웃이 덮어쓰면 안 된다(obtrudeException 금지)").isEqualTo("v");
    }
}
