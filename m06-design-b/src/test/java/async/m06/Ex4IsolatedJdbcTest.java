package async.m06;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Timed;
import async.m06.support.Cpu;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class Ex4IsolatedJdbcTest {

    FakeEventLoopClient sessionStore;
    FakeEventLoopClient recoApi;
    FakeBlockingService userDb;
    NamedPool jdbc;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        sessionStore = new FakeEventLoopClient("session", 30);
        recoApi = new FakeEventLoopClient("reco", 100);
        userDb = new FakeBlockingService("user-db", 100);
        jdbc = Pools.fixed("jdbc", 4);
    }

    @AfterEach
    void tearDown() {
        sessionStore.close();
        recoApi.close();
        jdbc.close();
    }

    private CompletableFuture<String> view(String token) {
        return Ex4IsolatedJdbc.orderView(sessionStore, recoApi, userDb, token, jdbc);
    }

    @Test
    void singleRequest_isolatesJdbc_andRunsDbAndRecoInParallel() throws Exception {
        Timed<CompletableFuture<String>> call = Timed.of(() -> view("u1"));
        assertThat(call.millis()).as("즉시 반환해야 한다 (R3)").isLessThan(30);
        Timed<String> total = Timed.run(() -> call.value().get(2, TimeUnit.SECONDS));

        assertThat(total.value()).isEqualTo("user-db:u1|reco:reco:u1");
        assertThat(BlockHoundSupport.violations())
                .as("이벤트 루프 위에서 JDBC(blocking)가 실행됐다 (R1, R4)")
                .isEmpty();
        assertThat(userDb.callerThreads())
                .as("회원 DB 조회는 jdbc 풀에서만 실행해야 한다. commonPool 이면 BlockHound 는 조용하지만 오답이다 (R2, R11)")
                .allMatch(t -> t.startsWith("jdbc-"));
        assertThat(call.millis() + total.millis())
                .as("총 시간 ≈ 30 + max(100, 100) = 130ms. 230ms 근처라면 DB 와 추천 호출이 순차로 실행됐다 (R7)")
                .isLessThan(200);
    }

    @Test
    void underLoad_slowJdbcDoesNotSlowTheEventLoops() throws Exception {
        List<CompletableFuture<String>> views = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            views.add(view("u" + i));
        }
        for (int i = 0; i < 20; i++) {
            assertThat(views.get(i).get(5, TimeUnit.SECONDS)).isEqualTo("user-db:u" + i + "|reco:reco:u" + i);
        }
        System.out.printf("20건: 세션 루프 지연 %dms, 추천 루프 지연 %dms, DB 동시 호출 최대 %d%n",
                sessionStore.maxLagMillis(), recoApi.maxLagMillis(), userDb.maxConcurrency());
        assertThat(BlockHoundSupport.violations()).as("이벤트 루프 위 blocking (R4)").isEmpty();
        assertThat(sessionStore.maxLagMillis() + recoApi.maxLagMillis())
                .as("JDBC 가 밀려도(풀 4개, 20건) 이벤트 루프는 지연되지 않아야 한다. 격리가 깨졌다 (R1, R4)")
                .isLessThan(100);
        assertThat(userDb.maxConcurrency())
                .as("DB 동시 호출은 jdbc 풀 크기(4)로 제한돼야 한다. 다른 스레드에서 DB 를 호출했다 (R11)")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void dbFailure_failsTheResult() {
        IllegalStateException down = new IllegalStateException("user db down");
        userDb.failOn("u-bad", down);
        Throwable thrown = catchThrowable(() -> view("u-bad").get(2, TimeUnit.SECONDS));
        assertThat(thrown).isNotNull();
        assertThat(Cpu.unwrap(thrown)).as("원인 예외가 그대로 전달돼야 한다 (R8)").isSameAs(down);
    }
}
