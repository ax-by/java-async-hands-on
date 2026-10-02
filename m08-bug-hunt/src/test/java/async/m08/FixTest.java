package async.m08;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.m08.domain.CardDeclinedException;
import async.m08.fixed.C01OrderHistory;
import async.m08.fixed.C02ProductDetail;
import async.m08.fixed.C03ProductPage;
import async.m08.fixed.C04OrderPlacement;
import async.m08.fixed.C05OrderPage;
import async.m08.fixed.C06Recommendation;
import async.m08.fixed.C07StockBadge;
import async.m08.fixed.C08Thumbnail;
import async.m08.fixed.C09Payment;
import async.m08.fixed.C10CartPricing;
import async.m08.fixed.C11ShippingQuote;
import async.m08.fixed.C12OrderEvents;
import async.m08.fixed.C13Fulfillment;
import async.m08.fixed.C14SalesReport;
import async.m08.fixed.C15ProductSearch;
import async.m08.fixed.C16OrderStatus;
import async.m08.fixed.C17OrderDetail;
import async.m08.fixed.C18Checkout;
import async.m08.fixed.C19ProductCard;
import async.m08.fixed.C20OrderExport;
import async.m08.fixed.C21PriceCache;
import async.m08.fixed.C22PaymentCancel;
import async.m08.probe.C01Probe;
import async.m08.probe.C02Probe;
import async.m08.probe.C03Probe;
import async.m08.probe.C04Probe;
import async.m08.probe.C05Probe;
import async.m08.probe.C06Probe;
import async.m08.probe.C07Probe;
import async.m08.probe.C08Probe;
import async.m08.probe.C09Probe;
import async.m08.probe.C10Probe;
import async.m08.probe.C11Probe;
import async.m08.probe.C12Probe;
import async.m08.probe.C13Probe;
import async.m08.probe.C14Probe;
import async.m08.probe.C15Probe;
import async.m08.probe.C16Probe;
import async.m08.probe.C17Probe;
import async.m08.probe.C18Probe;
import async.m08.probe.C19Probe;
import async.m08.probe.C20Probe;
import async.m08.probe.C21Probe;
import async.m08.probe.C22Probe;
import async.m08.probe.Diagnosis;
import async.m08.probe.Outcome;
import async.m08.probe.Probes;
import async.m08.support.Log;

/**
 * m08 수정 채점. {@code fixed} 패키지의 구현을 <b>증상 테스트와 같은 관측 조건·같은 측정</b>으로 실행해
 * (1) 증상이 사라졌는지, (2) 각 클래스 javadoc 의 요구사항대로 동작하는지 확인한다.
 *
 * <p><b>SymptomTest 채점을 마친 뒤에 연다.</b> 검증 항목과 실패 메시지가 각 사례의 진단을 담고 있다.
 */
@Timeout(20)
class FixTest {

    private static void assertNoSymptom(Diagnosis d) {
        System.out.println(d.report());
        assertThat(d.symptom())
                .as("같은 관측 조건에서 아직 증상이 측정된다. 측정 근거:%n%s", d.report())
                .isEqualTo(Symptom.NO_BUG);
    }

    @Test
    void c01_ordersAreReadConcurrently_inInputOrder() {
        try (C01Probe p = new C01Probe()) {
            Diagnosis d = p.run(new C01OrderHistory(p.orderDb, p.io)::recentOrders);
            assertThat(p.outcome.error()).as("recentOrders 가 예외로 끝났다: %s", p.outcome.error()).isNull();
            assertNoSymptom(d);
            assertThat(p.outcome.value()).as("입력 순서대로 summary(행)").containsExactly(
                    "summary(order-db:o-1)", "summary(order-db:o-2)", "summary(order-db:o-3)",
                    "summary(order-db:o-4)", "summary(order-db:o-5)");
            assertThat(p.orderDb.maxConcurrency()).as("5건이 동시에 실행돼야 한다 (R7: 먼저 모두 시작)").isEqualTo(5);
            assertThat(p.orderDb.callerThreads()).as("DB 호출은 io 풀에서 (R11)").allMatch(t -> t.startsWith("io-"));
        }
    }

    @Test
    void c02_cacheMissDoesNotBlockCaller_andIsCached() throws Exception {
        try (C02Probe p = new C02Probe()) {
            assertNoSymptom(p.run(new C02ProductDetail(p.productDb, p.dbPool)::find));
            assertThat(p.firstReturnMillis).as("캐시 미스여도 즉시 future 를 반환해야 한다 (R3, R4)").isLessThan(50);
            assertThat(p.first.value()).isEqualTo("product-db:p-1");
            assertThat(p.productDb.callerThreads()).as("DB 호출은 db 풀에서").allMatch(t -> t.startsWith("db-"));
            assertThat(p.secondFuture.isDone()).as("두 번째 호출은 캐시 히트 → 이미 완료된 future").isTrue();
            assertThat(p.secondFuture.get(1, TimeUnit.SECONDS)).isEqualTo("product-db:p-1");
            assertThat(p.productDb.callCount()).as("같은 SKU 는 DB 를 한 번만 호출").isEqualTo(1);
        }
    }

    @Test
    void c03_pageIsAssembledInParallel() {
        try (C03Probe p = new C03Probe()) {
            assertNoSymptom(p.run(new C03ProductPage(p.productDb, p.stockDb, p.reviewDb, p.io)::page));
            assertThat(p.outcome.value()).isEqualTo("product-db:p-1 | stock-db:p-1 | review-db:p-1");
            assertThat(p.totalMillis).as("≈ max(100,120,80)").isLessThan(200);
        }
    }

    @Test
    void c04_loyaltyFailureIsLogged_orderSucceeds() {
        try (C04Probe p = new C04Probe()) {
            assertNoSymptom(p.run(new C04OrderPlacement(p.orderDb, p.loyaltyDb, p.io)::place));
            assertThat(p.outcome.value()).as("포인트 적립 실패는 주문을 실패시키지 않는다").isEqualTo("receipt(order-db:o-1)");
            assertThat(Log.entries()).as("적립 실패를 담은 ERROR 로그가 있어야 한다 (R6: 버린 분기의 예외)")
                    .anyMatch(e -> e.level() == Log.Level.ERROR && Probes.causeChainContains(e.error(), p.loyaltyFailure));

            IllegalStateException orderFailure = new IllegalStateException("주문 DB 장애");
            p.orderDb.failOn("o-2", orderFailure);
            Log.reset();
            Outcome<String> failed = Outcome.await(new C04OrderPlacement(p.orderDb, p.loyaltyDb, p.io).place("o-2"), 2000);
            assertThat(failed.failed()).as("주문 저장 실패는 호출자에게 전파").isTrue();
            assertThat(Probes.pollUntil(() -> C04Probe.loggedWithCause(orderFailure), 500))
                    .as("주문 저장 실패도 ERROR 로그").isTrue();
        }
    }

    @Test
    void c05_noBlockingOnEventLoop() {
        try (C05Probe p = new C05Probe()) {
            assertNoSymptom(p.run(new C05OrderPage(p.orderClient, p.couponRepository, p.historyRepository, p.dbPool)::orderPage));
            assertThat(p.outcomes.get(3).value()).isEqualTo("order:o-3 / coupon:o-3 / history:o-3");
            assertThat(p.couponRepository.callerThreads()).as("쿠폰 JDBC 는 db 풀에서 (R4)").allMatch(t -> t.startsWith("db-"));
        }
    }

    @Test
    void c06_failureFallsBackToPopular() {
        try (C06Probe p = new C06Probe()) {
            C06Recommendation fixed = new C06Recommendation(p.recoClient);
            assertNoSymptom(p.run(fixed::forProduct));
            assertThat(p.outcome.value()).as("실패 → 인기 상품 (R9: 결과를 바꾸는 API)").isEqualTo(C06Recommendation.POPULAR);
            assertThat(Log.entries()).as("대체했다는 WARN 로그(예외 포함)")
                    .anyMatch(e -> e.level() == Log.Level.WARN && Probes.causeChainContains(e.error(), p.recoFailure));
            assertThat(Outcome.await(fixed.forProduct("sku-2"), 1000).value()).as("성공 경로").isEqualTo("recs[reco:sku-2]");
        }
    }

    @Test
    void c07_badges() {
        try (C07Probe p = new C07Probe()) {
            assertNoSymptom(p.run(new C07StockBadge(p.inventoryClient)::badge));
            assertThat(p.outcomes.get(0).value()).isEqualTo("품절");
            assertThat(p.outcomes.get(1).value()).isEqualTo("구매 가능");
        }
    }

    @Test
    void c08_thumbnailsDoNotStarveCommonPool() {
        try (C08Probe p = new C08Probe()) {
            assertNoSymptom(p.run(new C08Thumbnail(p.imageCdn, p.imagePool)::thumbnails));
            assertThat(p.outcome.value()).hasSize(p.skuCount).first().isEqualTo("image-cdn:thumb/sku-0");
            assertThat(p.imageCdn.callerThreads()).as("blocking 호출은 image 풀에서 (R2, R11)").allMatch(t -> t.startsWith("image-"));
        }
    }

    @Test
    void c09_declineIsAnswered_otherFailuresPropagate() {
        try (C09Probe p = new C09Probe()) {
            C09Payment fixed = new C09Payment(p.paymentGateway);
            assertNoSymptom(p.run(fixed::pay));
            assertThat(p.outcome.value()).as("카드 거절 → 안내 응답 (R8: unwrap 후 판별)").isEqualTo("declined(o-1)");
            assertThat(Outcome.await(fixed.pay("o-2"), 1000).value()).isEqualTo("paid(pg:o-2)");
            Outcome<String> outage = Outcome.await(fixed.pay("o-3"), 1000);
            assertThat(outage.failed()).as("장애는 전파").isTrue();
            assertThat(Probes.unwrap(outage.error())).as("원래 예외가 cause 로 보여야 한다").isSameAs(p.outage);
            assertThat(Probes.unwrap(outage.error())).isNotInstanceOf(CardDeclinedException.class);
        }
    }

    @Test
    void c10_noJoinOnEventLoop() {
        try (C10Probe p = new C10Probe()) {
            assertNoSymptom(p.run(new C10CartPricing(p.cartStore, p.pricingClient)::priceCart));
            assertThat(p.outcomes.get(2).value()).isEqualTo("cart:c-2 = pricing:cart:c-2");
            assertThat(p.totalMillis).as("5건이 동시에 진행되므로 ≈ 20 + 100ms").isLessThan(300);
        }
    }

    @Test
    void c11_everyPathIsBounded() {
        try (C11Probe p = new C11Probe()) {
            C11ShippingQuote fixed = new C11ShippingQuote(p.carrierA, p.carrierB);
            assertNoSymptom(p.run(fixed::cheapest));
            assertThat(p.outcome.value()).as("B 가 응답하지 않으면 A 의 견적").isEqualTo("carrier-a:o-1");
            assertThat(p.outcome.millis()).as("운송사별 최대 300ms (R10)").isLessThan(500);

            p.carrierA.latencyFor("o-2", 60_000);
            Outcome<String> both = Outcome.await(fixed.cheapest("o-2"), 1500);
            assertThat(both.value()).as("둘 다 응답하지 않으면 quote-unavailable").isEqualTo("quote-unavailable");
            assertThat(both.millis()).isLessThan(500);

            p.carrierB.latencyFor("o-3", 30);
            assertThat(Outcome.await(fixed.cheapest("o-3"), 1500).value()).as("둘 다 응답하면 싼 쪽").isEqualTo("carrier-a:o-3");
        }
    }

    @Test
    void c12_bestEffortEventIsLogged() {
        try (C12Probe p = new C12Probe()) {
            assertNoSymptom(p.run(new C12OrderEvents(p.orderDb, p.analyticsClient, p.io)::place));
            assertThat(p.outcome.value()).isEqualTo("receipt(order-db:o-1)");
            assertThat(p.outcome.millis()).as("응답은 analytics(200ms)를 기다리지 않는다").isLessThan(150);
            assertThat(Log.entries()).anyMatch(e -> e.level() == Log.Level.WARN && Probes.causeChainContains(e.error(), p.analyticsFailure));
        }
    }

    @Test
    void c13_completesOnlyAfterCapture_andPropagatesFailure() {
        try (C13Probe p = new C13Probe()) {
            C13Fulfillment fixed = new C13Fulfillment(p.inventoryClient, p.orderClient, p.paymentClient);
            assertNoSymptom(p.run(fixed::fulfill));
            assertThat(p.completedAtMillis).as("order 50 + capture 300 이후에 완료 (R13: thenCompose)").isGreaterThanOrEqualTo(330);

            IllegalStateException captureFailure = new IllegalStateException("매입 실패");
            p.paymentClient.failOn("capture:order:o-2", captureFailure);
            Outcome<Void> failed = Outcome.await(fixed.fulfill("o-2"), 2000);
            assertThat(failed.failed()).as("결제 매입 실패는 결과 future 의 실패로 전파돼야 한다").isTrue();
            assertThat(Probes.unwrap(failed.error())).isSameAs(captureFailure);
        }
    }

    @Test
    void c14_timedOutWorkIsInterrupted() {
        try (C14Probe p = new C14Probe()) {
            C14SalesReport fixed = new C14SalesReport(p.reportDb, p.reportPool);
            assertNoSymptom(p.run(fixed::salesReport));
            assertThat(p.outcomes).extracting(Outcome::value).containsExactly("cached-report(q1)", "cached-report(q2)");
            assertThat(p.outcomes).as("200ms 에 폴백").allMatch(o -> o.millis() < 350);
            assertThat(p.reportDb.interruptedCount()).as("시간 초과된 집계는 interrupt 로 중단 (R10: Future.cancel(true))").isEqualTo(2);
            assertThat(Probes.pollUntil(() -> p.reportPool.getActiveCount() == 0, 300)).as("report 풀 스레드 반환").isTrue();

            p.reportDb.latencyFor("fast", 50);
            assertThat(Outcome.await(fixed.salesReport("fast"), 1000).value()).as("200ms 안에 끝나면 실제 결과").isEqualTo("report-db:fast");
        }
    }

    @Test
    void c15_cheapStagesDoNotRequeue() {
        try (C15Probe p = new C15Probe()) {
            assertNoSymptom(p.run(new C15ProductSearch(p.searchDb, p.io)::search));
            assertThat(p.first.value()).isEqualTo("response(visible(parsed(search-db:q1)))");
            assertThat(p.all).extracting(Outcome::value).allMatch(v -> v.startsWith("response(visible(parsed(search-db:q"));
            assertThat(p.maxReturnMillis).as("요청 스레드를 막지 않는다").isLessThan(50);
        }
    }

    @Test
    void c16_everyLogLineHasTraceId() {
        try (C16Probe p = new C16Probe()) {
            assertNoSymptom(p.run(new C16OrderStatus(p.orderDb, p.deliveryClient, p.mdcAwareIo)::status));
            assertThat(p.outcome.value()).isEqualTo("status(delivery:order-db:o-1)");
            assertThat(p.lines).extracting(Log.Entry::message)
                    .containsExactly("배송 상태 조회 요청: o-1", "주문 조회: o-1", "배송 상태 수신: delivery:order-db:o-1");
            assertThat(p.lines).as("세 줄 모두 traceId=t-42. 힌트: Async + 감싼 executor 로 바꿨는데도 실패한다면, "
                    + "그 Async 단계를 executor 에 '제출'하는 스레드가 누구인지 보라 (R1, R12)").allMatch(e -> "t-42".equals(e.traceId()));
        }
    }

    @Test
    void c17_isolatedJdbcSegment() {
        try (C17Probe p = new C17Probe()) {
            assertNoSymptom(p.run(new C17OrderDetail(p.orderClient, p.legacyDb, p.jdbcPool)::detail));
            assertThat(p.outcomes.get(7).value()).isEqualTo("detail(legacy-db:order:o-7)");
            assertThat(p.legacyDb.callerThreads()).allMatch(t -> t.startsWith("jdbc-"));
            assertThat(p.totalMillis).isLessThan(400);
        }
    }

    @Test
    void c18_noStarvationUnderPeak() {
        try (C18Probe p = new C18Probe()) {
            assertNoSymptom(p.run(new C18Checkout(p.orderDb, p.inventoryDb, p.taskExecutor)::checkout));
            assertThat(p.peak).extracting(Outcome::value)
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, 8)
                            .mapToObj(i -> "order-db:o-" + i + " + inventory-db:order-db:o-" + i).collect(Collectors.toList()));
            assertThat(p.peak).as("8건이 4스레드에서 2라운드 ≈ 200ms 안팎").allMatch(o -> o.millis() < 800);
        }
    }

    @Test
    void c19_eventLoopNeverRunsEnrichment_loadIsShed() {
        try (C19Probe p = new C19Probe(); C19ProductCard fixed = new C19ProductCard(p.catalogClient, p.priceDb)) {
            assertNoSymptom(p.run(fixed::card));
            assertThat(p.normal).extracting(Outcome::value).as("평시에는 모두 가격 포함")
                    .containsExactly("catalog:sku-0 / price-db:sku-0", "catalog:sku-1 / price-db:sku-1", "catalog:sku-2 / price-db:sku-2");
            List<String> peak = p.peak.stream().map(Outcome::value).collect(Collectors.toList());
            assertThat(peak).as("피크 결과는 가격 포함 또는 price:unknown 둘 중 하나")
                    .allMatch(v -> v.endsWith(" / price-db:" + v.substring("catalog:".length(), v.indexOf(' '))) || v.endsWith(" / price:unknown"));
            assertThat(peak.stream().filter(v -> v.endsWith("price:unknown")).count())
                    .as("풀이 가득 차면 거절 → 기본 카드 (R3 예외 2를 응답으로 변환)").isGreaterThan(0);
            assertThat(p.priceDb.callerThreads()).as("가격 JDBC 는 enrich 풀에서만").allMatch(t -> t.startsWith("enrich-"));
        }
    }

    @Test
    void c20_contextIsRestoredAfterEachTask() {
        try (C20Probe p = new C20Probe()) {
            assertNoSymptom(p.run(new C20OrderExport(p.exportDb, p.pool)::export));
            assertThat(p.aliceRequest.value()).as("제출한 사용자의 권한으로 실행").isEqualTo("export-db:alice/q1");
            assertThat(p.nightlyBatch.value()).as("로그인 정보 없는 호출은 anonymous").isEqualTo("export-db:anonymous/nightly");
            assertThat(p.leftover.value()).as("작업이 끝난 풀 스레드에는 아무 사용자도 남지 않는다 (R12: 복원)").isNull();
        }
    }

    @Test
    void c21_callersAreIsolated_singleFlightKept() throws Exception {
        try (C21Probe p = new C21Probe()) {
            C21PriceCache fixed = new C21PriceCache(p.priceClient);
            assertNoSymptom(p.run(fixed::price));
            assertThat(p.callerB.value()).isEqualTo("price:sku-1");
            assertThat(Probes.unwrap(p.callerA.error())).as("A 는 자기 예산대로 타임아웃").isInstanceOf(TimeoutException.class);
            assertThat(p.priceClient.requestCount()).as("single-flight 유지: API 호출 1회").isEqualTo(1);

            CompletableFuture<String> c = fixed.price("sku-9");
            CompletableFuture<String> d = fixed.price("sku-9");
            c.complete("hacked");
            assertThat(d.get(1, TimeUnit.SECONDS)).as("한 호출자의 complete() 가 다른 호출자에게 보이면 안 된다").isEqualTo("price:sku-9");
            assertThat(p.priceClient.requestCount()).isEqualTo(2);
        }
    }

    @Test
    void c22_traceIdSurvivesTheAsyncHop() {
        try (C22Probe p = new C22Probe()) {
            assertNoSymptom(p.run(new C22PaymentCancel(p.pgClient, p.refundDb, p.mdcAwareIo)::cancel));
            assertThat(p.outcome.value()).isEqualTo("refund-db:pg:cancel/p-1");
            assertThat(p.lines).extracting(Log.Entry::message).containsExactly("결제 취소 요청: p-1", "PG 취소 완료: pg:cancel/p-1");
            assertThat(p.refundDb.callerThreads()).as("JDBC 는 io 풀에서 (이벤트 루프 금지)").allMatch(t -> t.startsWith("io-"));
        }
    }

    @Test
    void blockHoundIsInstalled() {
        BlockHoundSupport.reset();
        assertThat(BlockHoundSupport.isInstalled()).as("BlockHound 설치 실패: %s", BlockHoundSupport.installFailure()).isTrue();
    }
}
