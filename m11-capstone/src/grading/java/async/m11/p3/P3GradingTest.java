package async.m11.p3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m11.Grading;

/** P3 졸업 채점: 리뷰로 찾은 결함(해시 비교)과 고친 코드의 요구사항 충족. */
@Timeout(30)
class P3GradingTest {

    FakeBlockingService kycDb;
    FakeEventLoopClient fxClient;
    FakeBlockingService feeEngine;
    FakeEventLoopClient sanctions;
    FakeBlockingService quoteDb;
    NamedPool io;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        kycDb = new FakeBlockingService("kyc-db", 40);
        fxClient = new FakeEventLoopClient("fx", 30);
        feeEngine = new FakeBlockingService("fee-engine", 50);
        sanctions = new FakeEventLoopClient("sanctions", 60);
        quoteDb = new FakeBlockingService("quote-db", 30);
        io = Pools.fixed("io", 32);
    }

    @AfterEach
    void tearDown() {
        fxClient.close();
        sanctions.close();
        io.close();
        RequestContext.clear();
    }

    private RemittanceQuoteService service() {
        return new RemittanceQuoteService(kycDb, fxClient, feeEngine, sanctions, quoteDb, io);
    }

    /** APP 채널 요청 스레드에서 견적을 요청한다(요청이 끝나면 컨텍스트를 지운다: 서블릿 필터처럼). */
    private CompletableFuture<Quote> quoteFromApp(RemittanceQuoteService s, String customer, String pair) {
        RequestContext.setChannel("APP");
        try {
            return s.quote(customer, pair, 1000);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void p3_0_reviewFindings() {
        Set<Integer> found = ReviewFindings.CHECKLIST_NUMBERS;
        List<String> hits = new ArrayList<>();
        List<Integer> falsePositives = new ArrayList<>();
        Set<String> defects = new TreeSet<>();
        if (found != null) {
            for (Integer n : found) {
                String defect = Expected.DEFECTS.get(Grader.sha256("p3#" + n));
                if (defect == null) {
                    falsePositives.add(n);
                } else {
                    defects.add(defect);
                    hits.add("#" + n);
                }
            }
        }
        String summary = String.format("결함 %d/%d 발견 (적중: %s), 오탐 %d개 %s",
                defects.size(), Expected.TOTAL_DEFECTS, hits, falsePositives.size(), falsePositives);
        System.out.println("[P3 리뷰] " + summary);
        assertThat(found).as("ReviewFindings.CHECKLIST_NUMBERS 를 먼저 작성하라 (미작성)").isNotNull();
        assertThat(falsePositives).as("올바른 코드를 결함으로 지목했다(오탐). %s", summary).isEmpty();
        assertThat(defects).as("놓친 결함이 있다. %s", summary).hasSize(Expected.TOTAL_DEFECTS);
    }

    @Test
    void p3_1_normalQuote_parallel_persistedWithChannel_noBlockingOnEventLoops() {
        RemittanceQuoteService s = service();
        long start = System.nanoTime();
        CompletableFuture<Quote> f = quoteFromApp(s, "c-1", "USD-KRW");
        long returned = Grading.since(start);
        AtomicInteger persistedAtCompletion = new AtomicInteger(-1);
        Quote q = Grading.await(f.whenComplete((v, ex) -> persistedAtCompletion.set(quoteDb.callCount())), 2000, "견적");
        long total = Grading.since(start);

        assertThat(returned).as("quote() 는 호출 스레드를 막지 않는다").isLessThan(30);
        assertThat(q.rate()).isEqualTo("fx:USD-KRW");
        assertThat(q.fee()).isEqualTo("fee-engine:1000@fx:USD-KRW");
        assertThat(q.quoteId()).as("저장 시 요청 채널(APP)이 함께 기록돼야 한다 (R12, #15)").isEqualTo("quote-db:c-1/fx:USD-KRW/APP");
        assertThat(persistedAtCompletion.get()).as("견적 future 는 저장이 끝난 뒤에 완료돼야 한다 (R13, #14)").isEqualTo(1);
        assertThat(total).as("KYC·환율→수수료·스크리닝 병렬 후 저장 ≈ 110ms. 실제 %dms", total).isLessThan(250);
        assertThat(BlockHoundSupport.violations()).as("이벤트 루프 위 blocking (수수료 엔진/중첩 join, R4, #3)").isEmpty();
        assertThat(kycDb.callerThreads()).as("KYC JDBC 는 io 풀에서 (R2, #4)").allMatch(t -> t.startsWith("io-"));
        assertThat(feeEngine.callerThreads()).as("수수료 엔진(동기 HTTP)은 io 풀에서 (R4, #3)").allMatch(t -> t.startsWith("io-"));
    }

    @Test
    void p3_2_fxUnavailable_fallsBackToCachedRate() {
        fxClient.failOn("USD-KRW", new RateUnavailableException("USD-KRW 고시 전"));
        Quote q = Grading.await(quoteFromApp(service(), "c-2", "USD-KRW"), 2000, "견적");
        assertThat(q.rate()).as("RateUnavailableException 이면 캐시 환율 (R8: unwrap, #10)").isEqualTo("fx-cache:USD-KRW");
        assertThat(q.fee()).isEqualTo("fee-engine:1000@fx-cache:USD-KRW");
    }

    @Test
    void p3_3_fxHang_isCutAt200ms_withCachedRate() {
        fxClient.latencyFor("JPY-KRW", 10_000);
        long start = System.nanoTime();
        Quote q = Grading.await(quoteFromApp(service(), "c-3", "JPY-KRW"), 2000, "견적");
        long total = Grading.since(start);
        assertThat(q.rate()).isEqualTo("fx-cache:JPY-KRW");
        assertThat(total).as("환율 200ms 초과 → 캐시 환율, 이후 수수료 50 + 저장 30 (R10, #11). 실제 %dms", total).isBetween(250L, 450L);
    }

    @Test
    void p3_4_sanctionsHit_rejectsTheQuote_andNothingIsPersisted() {
        SanctionsHitException hit = new SanctionsHitException("OFAC SDN 일치");
        sanctions.failOn("c-4", hit);
        Throwable cause = Grading.awaitFailure(quoteFromApp(service(), "c-4", "USD-KRW"), 2000, "제재 대상 견적");
        assertThat(cause).as("스크리닝 실패는 견적 실패여야 한다 (R6: 버린 future, #9)").isSameAs(hit);
        Sleep.ms(150); // 늦게 실행되는 저장이 있는지 본다
        assertThat(quoteDb.callCount()).as("제재 대상의 견적이 저장되면 안 된다").isZero();
    }

    @Test
    void p3_5_sanctionsSilent_failsClosedAt300ms() {
        sanctions.latencyFor("c-5", 10_000);
        long start = System.nanoTime();
        Throwable cause = Grading.awaitFailure(quoteFromApp(service(), "c-5", "USD-KRW"), 2000, "스크리닝 무응답 견적");
        long total = Grading.since(start);
        assertThat(cause).as("스크리닝 300ms 무응답 → 거절(fail-closed)").isInstanceOf(TimeoutException.class);
        assertThat(total).isBetween(280L, 450L);
        assertThat(quoteDb.callCount()).isZero();
    }

    @Test
    void p3_6_kycRejected_isMapped() {
        IllegalStateException notVerified = new IllegalStateException("KYC 서류 미제출");
        kycDb.failOn("c-6", notVerified);
        Throwable cause = Grading.awaitFailure(quoteFromApp(service(), "c-6", "USD-KRW"), 2000, "KYC 미승인 견적");
        assertThat(cause).isInstanceOf(KycRejectedException.class);
        assertThat(cause.getCause()).isSameAs(notVerified);
        assertThat(quoteDb.callCount()).isZero();
    }

    @Test
    void p3_7_overallSlo_whenKycHangs() {
        kycDb.latencyFor("c-7", 5_000);
        long start = System.nanoTime();
        Throwable cause = Grading.awaitFailure(quoteFromApp(service(), "c-7", "USD-KRW"), 2000, "KYC 무응답 견적");
        assertThat(cause).as("전체 SLO 600ms 를 넘으면 TimeoutException").isInstanceOf(TimeoutException.class);
        assertThat(Grading.since(start)).isBetween(550L, 750L);
    }

    @Test
    void p3_8_concurrentQuotes_keepTheirOwnChannel_andEventLoopsStayFree() {
        RemittanceQuoteService s = service();
        List<CompletableFuture<Quote>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RequestContext.setChannel(i % 2 == 0 ? "APP" : "WEB");
            try {
                fs.add(s.quote("k-" + i, "USD-KRW", 1000));
            } finally {
                RequestContext.clear();
            }
        }
        for (int i = 0; i < 20; i++) {
            Quote q = Grading.await(fs.get(i), 3000, "동시 견적 " + i);
            assertThat(q.quoteId()).isEqualTo("quote-db:k-" + i + "/fx:USD-KRW/" + (i % 2 == 0 ? "APP" : "WEB"));
        }
        assertThat(BlockHoundSupport.violations()).isEmpty();
        assertThat(fxClient.maxLagMillis()).as("환율 클라이언트 이벤트 루프 lag").isLessThan(50);
    }

    /** 결함별 정답 체크리스트 번호의 해시 (SHA-256 of "p3#번호"). 같은 결함의 별칭 번호는 같은 결함 ID 로 묶는다. */
    private static final class Expected {
        static final int TOTAL_DEFECTS = 7;
        static final Map<String, String> DEFECTS = Map.of(
                "e2651fb8807125c83c39893ec8ab3c35227fd602a3111f977055e389f6fbea2a", "D-fee-on-event-loop",
                "0be30b2e8812d13a13a34b753b3fb99b6ae75452915c0f38660766a13db6df7a", "D-kyc-common-pool",
                "2a17f02ca6ceb1558b21413305cba331d2de464d1efbbfabdd09f8b91a3e7db7", "D-screening-discarded",
                "418401cf434acb1365a5f9b7636c322b0a7f60b1c5e410b07d12edf1e7d463e0", "D-no-unwrap",
                "fe65f6d3e81c0b4cbc5f3676228ab8aba0ad84a1a8aa3526dc361d756719e646", "D-no-timeout",
                "0d83665df827a20753a32e35d34a6bf845e2331fc98e1ef97714f09af459400f", "D-nested-future",
                "c4ea0b35b315c92ed7df696718dd63ac0981f12527966373618e75688cb071eb", "D-nested-future",
                "e050f628bbdf44e4be17f703536b2e39e1183c5ace58bab408682244cfcee538", "D-context-lost");
    }
}
