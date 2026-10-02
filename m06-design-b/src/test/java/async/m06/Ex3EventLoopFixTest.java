package async.m06;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
class Ex3EventLoopFixTest {

    static final int PAGES = 8;

    FakeEventLoopClient cache;
    FakeBlockingService auditLog;
    NamedPool cpu;
    NamedPool blocking;
    Set<String> renderThreads;
    Function<String, String> render;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        cache = new FakeEventLoopClient("page-cache", 30);
        auditLog = new FakeBlockingService("audit", 50);
        cpu = Pools.fixed("cpu", 4);
        blocking = Pools.fixed("blocking", 8);
        renderThreads = ConcurrentHashMap.newKeySet();
        render = data -> {
            renderThreads.add(Thread.currentThread().getName());
            Cpu.burn(80);
            return "<html>" + data + "</html>";
        };
    }

    @AfterEach
    void tearDown() {
        cache.close();
        cpu.close();
        blocking.close();
    }

    private List<String> renderAll(boolean broken) throws Exception {
        List<CompletableFuture<String>> pages = new ArrayList<>();
        for (int i = 0; i < PAGES; i++) {
            String key = "p" + i;
            pages.add(broken
                    ? Ex3EventLoopFix.renderBroken(cache, key, render, auditLog)
                    : Ex3EventLoopFix.render(cache, key, render, auditLog, cpu, blocking));
        }
        List<String> out = new ArrayList<>();
        for (CompletableFuture<String> p : pages) {
            out.add(p.get(5, TimeUnit.SECONDS));
        }
        return out;
    }

    /** 대조군: 운영 코드가 정말로 이벤트 루프를 막는지 확인한다 (학습자 구현과 무관하게 항상 통과). */
    @Test
    void control_brokenVersionBlocksTheEventLoop() throws Exception {
        renderAll(true);
        System.out.printf("[대조군] renderBroken: 이벤트 루프 최대 지연 %dms, 위반 %d건%n",
                cache.maxLagMillis(), BlockHoundSupport.violations().size());
        assertThat(cache.maxLagMillis()).isGreaterThan(300);
        assertThat(BlockHoundSupport.violations()).isNotEmpty();
    }

    @Test
    void fixed_keepsEventLoopFree() throws Exception {
        Timed<CompletableFuture<String>> first = Timed.of(
                () -> Ex3EventLoopFix.render(cache, "first", render, auditLog, cpu, blocking));
        assertThat(first.millis()).as("즉시 반환해야 한다 (R3)").isLessThan(30);
        first.value().get(2, TimeUnit.SECONDS);

        List<String> pages = renderAll(false);
        for (int i = 0; i < PAGES; i++) {
            assertThat(pages.get(i)).isEqualTo("<html>page-cache:p" + i + "</html>");
        }
        System.out.printf("[구현] render: 이벤트 루프 최대 지연 %dms%n", cache.maxLagMillis());
        assertThat(BlockHoundSupport.violations())
                .as("이벤트 루프 위에서 blocking 호출이 일어났다 (R1, R4)")
                .isEmpty();
        assertThat(cache.maxLagMillis())
                .as("이벤트 루프가 응답을 제때 처리하지 못했다. CPU 작업(render)도 이벤트 루프를 점유한다. "
                        + "BlockHound 는 CPU 점유를 못 잡는다 (R1, R2)")
                .isLessThan(100);
        assertThat(renderThreads)
                .as("render 는 cpu 풀에서 실행돼야 한다 (R2)")
                .allMatch(t -> t.startsWith("cpu-"));
        assertThat(auditLog.callerThreads())
                .as("감사 로그(blocking)는 blocking 풀에서 실행돼야 한다. commonPool 이나 cpu 풀이면 오답 (R11)")
                .allMatch(t -> t.startsWith("blocking-"));
        assertThat(auditLog.callCount()).as("결과가 완료되기 전에 감사 로그가 기록돼야 한다").isEqualTo(PAGES + 1);
    }

    @Test
    void auditFailure_failsTheResult() {
        IllegalStateException down = new IllegalStateException("audit db down");
        auditLog.failOn("bad", down);
        Throwable thrown = catchThrowable(
                () -> Ex3EventLoopFix.render(cache, "bad", render, auditLog, cpu, blocking).get(2, TimeUnit.SECONDS));
        assertThat(thrown).as("감사 로그가 실패하면 결과도 실패해야 한다. fire-and-forget 하지 마라 (R6, R8)").isNotNull();
        assertThat(Cpu.unwrap(thrown)).as("감사 로그의 예외가 그대로 원인으로 전달돼야 한다 (R8)").isSameAs(down);
    }
}
