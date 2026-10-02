package async.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** harness 자체가 기대대로 동작하는지 검증한다 (학습자용 실습이 아님). */
class HarnessSelfTest {

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
    }

    @Test
    void blockHoundIsInstalledOnThisJdk() {
        assertThat(BlockHoundSupport.installFailure()).as("BlockHound 설치 실패").isNull();
        assertThat(BlockHoundSupport.isInstalled()).isTrue();
    }

    @Test
    void recorderClassifiesThreads() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (Pools.NamedPool io = Pools.fixed("io", 2)) {
            rec.mark("caller");
            CompletableFuture.runAsync(() -> rec.mark("io"), io).get(1, TimeUnit.SECONDS);
            CompletableFuture.runAsync(() -> rec.mark("common")).get(1, TimeUnit.SECONDS);
            new CompletableFuture<String>().orTimeout(10, TimeUnit.MILLISECONDS)
                    .whenComplete((v, e) -> rec.mark("timeout"))
                    .exceptionally(e -> null).get(1, TimeUnit.SECONDS);
        }
        assertThat(rec.on("caller")).isEqualTo(On.caller());
        assertThat(rec.on("io")).isEqualTo(On.pool("io"));
        assertThat(rec.on("common")).isEqualTo(On.commonPool());
        assertThat(rec.on("timeout")).isEqualTo(On.timeoutScheduler());
    }

    @Test
    void eventLoopCompletesFutureAndCallbacksRunThere() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (FakeEventLoopClient client = new FakeEventLoopClient("redis", 50)) {
            String v = client.fetch("k").thenApply(s -> {
                rec.mark("callback");
                return s;
            }).get(1, TimeUnit.SECONDS);
            assertThat(v).isEqualTo("redis:k");
        }
        assertThat(rec.on("callback")).isEqualTo(On.eventLoop());
    }

    @Test
    void blockingOnEventLoopIsDetectedAndCausesLag() throws Exception {
        try (FakeEventLoopClient client = new FakeEventLoopClient("redis", 20)) {
            FakeBlockingService db = new FakeBlockingService("db", 200);
            CompletableFuture<String> slow = client.fetch("a").thenApply(s -> db.call(s)); // 이벤트 루프에서 blocking
            CompletableFuture<String> other = client.fetch("b");
            CompletableFuture.allOf(slow, other).get(2, TimeUnit.SECONDS);
            assertThat(client.maxLagMillis()).isGreaterThanOrEqualTo(150);
        }
        assertThat(BlockHoundSupport.violations()).isNotEmpty();
        assertThat(BlockHoundSupport.violations().get(0).threadName()).startsWith("event-loop-");
    }

    @Test
    void idleEventLoopAndPoolsProduceNoViolations() throws Exception {
        try (FakeEventLoopClient client = new FakeEventLoopClient("redis", 10);
             Pools.NamedPool np = Pools.fixed("nonblocking", 2)) {
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                client.fetch("k" + i).thenComposeAsync(s -> client.fetch(s + "!"), np).thenRun(latch::countDown);
            }
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100); // 스레드들이 idle 상태로 돌아갈 시간
        }
        assertThat(BlockHoundSupport.violations()).isEmpty();
    }

    @Test
    void graderRecordsAndReports() {
        new Grader().check("harness.self.ok", On.caller(), On.caller(), "R0").verify();
    }

    @Test
    void hashedAnswerMatchesPythonTool() throws Exception {
        Process p = new ProcessBuilder("python3", "../tools/hash_answer.py", "  Lettuce-NioEventLoop  ").start();
        String py = new String(p.getInputStream().readAllBytes()).trim();
        assertThat(Grader.sha256(Grader.normalize("lettuce-nioeventloop"))).isEqualTo(py);
        new Grader().checkHashed("harness.self.hash", "\"lettuce-nioEventLoop\"", py, "R0").verify();
    }
}
