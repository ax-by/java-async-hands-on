package async.m11.p2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m11.Grading;

/** P2 졸업 채점: PROBLEM.md 요구사항의 목적 달성 여부. */
@Timeout(30)
class P2GradingTest {

    FakeEventLoopClient redis;
    FakeEventLoopClient push;
    FakeEventLoopClient email;
    FakeEventLoopClient sms;
    FakeBlockingService auditDb;
    ChannelPreferences prefs;
    /** WebFlux 핸들러를 흉내 낸 호출 스레드. 이름이 nonblocking- 이라 여기서 blocking 하면 BlockHound 가 잡는다. */
    NamedPool webflux;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        redis = new FakeEventLoopClient("redis", 20);
        push = new FakeEventLoopClient("push", 50);
        email = new FakeEventLoopClient("email", 120);
        sms = new FakeEventLoopClient("sms", 200);
        auditDb = new FakeBlockingService("audit-db", 30);
        prefs = new ChannelPreferences(redis)
                .optIn("u1", Channel.PUSH, Channel.EMAIL, Channel.SMS)
                .optIn("u2", Channel.PUSH, Channel.EMAIL, Channel.SMS)
                .optIn("u3", Channel.PUSH, Channel.EMAIL, Channel.SMS);
        webflux = Pools.fixed("nonblocking-http", 2);
    }

    @AfterEach
    void tearDown() {
        redis.close();
        push.close();
        email.close();
        sms.close();
        webflux.close();
    }

    private NotificationGateway gateway() {
        return new NotificationGateway(prefs, push, email, sms, auditDb);
    }

    /** 이벤트 루프(nonblocking-http) 스레드에서 테넌트를 설정하고 send 를 호출한다. 호출에 걸린 시간도 잰다. */
    private CompletableFuture<DeliveryReport> sendFromEventLoop(NotificationGateway g, String tenant, String id, String user,
                                                                long[] callMillis) {
        CompletableFuture<CompletableFuture<DeliveryReport>> handoff = new CompletableFuture<>();
        webflux.execute(() -> {
            TenantContext.set(tenant);
            long start = System.nanoTime();
            try {
                handoff.complete(g.send(id, user));
            } catch (Throwable t) {
                handoff.completeExceptionally(t);
            } finally {
                if (callMillis != null) {
                    callMillis[0] = Grading.since(start);
                }
                TenantContext.clear();
            }
        });
        return Grading.await(handoff, 2000, "send() 호출 자체");
    }

    private List<BlockHoundSupport.Violation> violations() {
        return BlockHoundSupport.violations();
    }

    @Test
    void p2_1_happyPath_allChannels_auditedWithTenant_noBlockingAnywhere() {
        try (NotificationGateway g = gateway()) {
            long[] call = new long[1];
            long start = System.nanoTime();
            CompletableFuture<DeliveryReport> f = sendFromEventLoop(g, "tenant-7", "n-1", "u1", call);
            AtomicReference<String> completionThread = new AtomicReference<>();
            CompletableFuture<DeliveryReport> observed = f.whenComplete((r, ex) -> completionThread.set(Thread.currentThread().getName()));
            DeliveryReport r = Grading.await(observed, 2000, "발송");
            long total = Grading.since(start);

            assertThat(call[0]).as("send() 는 이벤트 루프인 호출 스레드를 막으면 안 된다 (R3, R4). 실제 %dms", call[0]).isLessThan(20);
            assertThat(r.delivered()).isEqualTo(Map.of(Channel.PUSH, "push:u1/n-1", Channel.EMAIL, "email:u1/n-1", Channel.SMS, "sms:u1/n-1"));
            assertThat(r.failed()).isEmpty();
            assertThat(r.auditReceipt()).as("감사 키 = 테넌트|알림ID|성공수/시도수. 테넌트는 호출 스레드의 TenantContext (R12)")
                    .isEqualTo("audit-db:tenant-7|n-1|3/3");
            assertThat(total).as("≈ prefs 20 + max(50,120,200) + audit 30 = 250ms (채널은 병렬, R7). 실제 %dms", total).isLessThan(340);
            assertThat(violations()).as("이벤트 루프·호출 스레드 위 blocking 이 없어야 한다 (B 의 정의, R0/R4)").isEmpty();
            assertThat(auditDb.callerThreads()).as("감사 JDBC 는 전용 blocking 풀에서 (격리 구간, R11): %s", auditDb.callerThreads())
                    .noneMatch(t -> t.startsWith("event-loop") || t.startsWith("nonblocking") || t.startsWith("ForkJoinPool"));
            assertThat(completionThread.get()).as("반환 future 가 이벤트 루프나 타임아웃 스케줄러에서 완료되면 호출자의 콜백이 거기서 돈다")
                    .doesNotStartWith("event-loop").doesNotContain("delayScheduler").doesNotStartWith("CompletableFutureDelayScheduler");
        }
    }

    @Test
    void p2_2_hangingChannel_isCutAt300ms() {
        sms.latencyFor("u1/n-2", 10_000);
        try (NotificationGateway g = gateway()) {
            long start = System.nanoTime();
            DeliveryReport r = Grading.await(sendFromEventLoop(g, "tenant-7", "n-2", "u1", null), 2000, "발송");
            long total = Grading.since(start);
            assertThat(r.failed()).containsExactly(Channel.SMS);
            assertThat(r.delivered()).containsOnlyKeys(Channel.PUSH, Channel.EMAIL);
            assertThat(r.auditReceipt()).isEqualTo("audit-db:tenant-7|n-2|2/3");
            assertThat(total).as("채널별 최대 300ms + 감사 30ms. 실제 %dms", total).isBetween(300L, 420L);
            assertThat(violations()).isEmpty();
        }
    }

    @Test
    void p2_3_failingChannel_isReportedNotThrown() {
        email.failOn("u1/n-3", new IllegalStateException("SES 500"));
        try (NotificationGateway g = gateway()) {
            DeliveryReport r = Grading.await(sendFromEventLoop(g, "tenant-7", "n-3", "u1", null), 2000, "발송");
            assertThat(r.failed()).containsExactly(Channel.EMAIL);
            assertThat(r.delivered()).containsOnlyKeys(Channel.PUSH, Channel.SMS);
            assertThat(r.auditReceipt()).isEqualTo("audit-db:tenant-7|n-3|2/3");
        }
    }

    @Test
    void p2_4_slowOrFailingPreferences_fallBackToPushOnly() {
        redis.latencyFor("prefs/u2", 5_000);
        redis.failOn("prefs/u3", new IllegalStateException("redis down"));
        try (NotificationGateway g = gateway()) {
            long start = System.nanoTime();
            DeliveryReport slow = Grading.await(sendFromEventLoop(g, "tenant-7", "n-4", "u2", null), 2000, "발송");
            long slowMillis = Grading.since(start);
            assertThat(slow.delivered()).as("수신 동의 조회가 100ms 를 넘으면 PUSH 만").containsOnlyKeys(Channel.PUSH);
            assertThat(slow.auditReceipt()).isEqualTo("audit-db:tenant-7|n-4|1/1");
            assertThat(slowMillis).as("≈ 100 + 50 + 30ms. 실제 %dms", slowMillis).isLessThan(260);

            DeliveryReport failed = Grading.await(sendFromEventLoop(g, "tenant-7", "n-5", "u3", null), 2000, "발송");
            assertThat(failed.delivered()).as("조회 실패도 PUSH 만").containsOnlyKeys(Channel.PUSH);
            assertThat(violations()).isEmpty();
        }
    }

    @Test
    void p2_5_auditFailure_failsTheSend() {
        IllegalStateException auditDown = new IllegalStateException("audit DB down");
        auditDb.failOn("tenant-7|n-6|3/3", auditDown);
        try (NotificationGateway g = gateway()) {
            Throwable cause = Grading.awaitFailure(sendFromEventLoop(g, "tenant-7", "n-6", "u1", null), 2000, "감사 실패한 발송");
            assertThat(cause).as("감사 기록이 커밋되지 않으면 발송 결과는 실패여야 한다(규정). unwrap 한 원인은 감사 예외").isSameAs(auditDown);
        }
    }

    @Test
    void p2_6_peakLoad_tenantsDoNotMix_eventLoopsStayResponsive() {
        try (NotificationGateway g = gateway()) {
            Grading.await(sendFromEventLoop(g, "warm", "w", "u1", null), 2000, "워밍업");
            BlockHoundSupport.reset();
            long start = System.nanoTime();
            List<CompletableFuture<DeliveryReport>> fs = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                fs.add(sendFromEventLoop(g, "tenant-" + (i % 7), "p-" + i, "u1", null));
            }
            for (int i = 0; i < 60; i++) {
                DeliveryReport r = Grading.await(fs.get(i), 3000, "피크 발송 " + i);
                assertThat(r.auditReceipt()).as("동시 발송에서도 각 감사 기록은 자기 요청의 테넌트여야 한다 (R12)")
                        .isEqualTo("audit-db:tenant-" + (i % 7) + "|p-" + i + "|3/3");
            }
            long total = Grading.since(start);
            assertThat(total).as("동시 60건도 SLO 450ms 안에. 실제 %dms", total).isLessThan(450);
            assertThat(violations()).as("피크에도 blocking 0건").isEmpty();
            for (FakeEventLoopClient c : List.of(redis, push, email, sms)) {
                assertThat(c.maxLagMillis()).as("%s 이벤트 루프 lag", c.name()).isLessThan(50);
            }
            assertThat(auditDb.callerThreads().size()).as("감사 풀 스레드 수는 근거 있는 상한이 있어야 한다 (R11)").isLessThanOrEqualTo(64);
        }
    }

    @Test
    void p2_7_tenantContextIsNotLeftOnWorkerThreads() {
        try (NotificationGateway g = gateway()) {
            Grading.await(sendFromEventLoop(g, "tenant-9", "n-8", "u1", null), 2000, "발송");
            // 테넌트 없이 보낸 요청의 감사 키는 "null|..." 이어야 한다 (이전 요청의 값이 풀 스레드에 남으면 안 된다)
            DeliveryReport anonymous = Grading.await(sendFromEventLoop(g, null, "n-9", "u1", null), 2000, "발송");
            assertThat(anonymous.auditReceipt()).as("테넌트 없는 요청에 이전 요청의 테넌트가 섞였다 (R12)").isEqualTo("audit-db:null|n-9|3/3");
        }
    }
}
