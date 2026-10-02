package async.m07;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import async.m07.async.AsyncExerciseConfig;
import async.m07.ctx.Ctx;

/** m07 구현 채점 E6. */
@Timeout(30)
@SpringBootTest(classes = AsyncExerciseConfig.class)
class AsyncExerciseTest {

    @Autowired AsyncExerciseConfig.NotificationService notifications;
    @Autowired ThreadPoolTaskExecutor asyncExecutor;

    @AfterEach
    void tearDown() {
        Ctx.clear();
    }

    @Test
    void e6_runsOnAsyncPool_withContext() throws Exception {
        Ctx.enter("t-e6", "alice");
        String seen = notifications.send("bob@example.com").get(2, TimeUnit.SECONDS);
        assertThat(seen).as("@Async 는 async-* 스레드에서 실행돼야 한다 (getAsyncExecutor)").startsWith("async-");
        assertThat(seen).as("traceId 와 user 가 전파돼야 한다 (R12)").endsWith("t-e6/alice");
    }

    @Test
    void e6_noLeak() throws Exception {
        Ctx.enter("t-e6", "alice");
        notifications.send("x").get(2, TimeUnit.SECONDS);
        Ctx.clear();
        for (int i = 0; i < 8; i++) { // 스레드 4개를 모두 한 번 이상 거치게 한다
            assertThat(notifications.send("y").get(2, TimeUnit.SECONDS))
                    .as("컨텍스트 없는 호출자의 작업에 이전 요청의 컨텍스트가 보이면 안 된다").endsWith("NONE/NONE");
        }
    }

    @Test
    void e6_executorIsBounded() {
        assertThat(asyncExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(asyncExecutor.getMaxPoolSize()).isEqualTo(4);
        assertThat(asyncExecutor.getQueueCapacity()).as("무제한 큐는 장애를 지연으로 숨긴다 (R11)").isEqualTo(100);
    }

    @Test
    void e6_voidFailureReachesHandler() throws Exception {
        AsyncExerciseConfig.FAILURES.clear();
        notifications.sendAndForget("ops@example.com");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (AsyncExerciseConfig.FAILURES.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(AsyncExerciseConfig.FAILURES)
                .as("void @Async 의 예외는 AsyncUncaughtExceptionHandler 로만 관찰할 수 있다 (R6)")
                .containsExactly("sendAndForget: SMTP 장애: ops@example.com");
    }
}
