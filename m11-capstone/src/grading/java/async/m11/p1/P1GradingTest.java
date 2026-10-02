package async.m11.p1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.FakeBlockingService;
import async.m11.Grading;

/** P1 졸업 채점: PROBLEM.md 요구사항의 목적 달성 여부. */
@Timeout(30)
class P1GradingTest {

    static final String ROUTE = "ICN-NRT";

    FakeBlockingService airlineA;
    FakeBlockingService airlineB;
    FakeBlockingService hotel;
    FakeBlockingService car;

    @BeforeEach
    void vendors() {
        airlineA = new FakeBlockingService("airline-a", 120);
        airlineB = new FakeBlockingService("airline-b", 150);
        hotel = new FakeBlockingService("hotel", 100);
        car = new FakeBlockingService("car", 80);
    }

    private List<FakeBlockingService> all() {
        return List.of(airlineA, airlineB, hotel, car);
    }

    @Test
    void p1_1_callerIsNotBlocked_andVendorsRunInParallel_onADedicatedPool() {
        try (TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car)) {
            long start = System.nanoTime();
            CompletableFuture<SearchResult> f = service.search(ROUTE);
            long returned = Grading.since(start);
            SearchResult r = Grading.await(f, 2000, "검색");
            long total = Grading.since(start);

            assertThat(returned).as("search() 는 요청 스레드를 막지 않고 즉시 future 를 반환해야 한다 (R3). 실제 %dms", returned).isLessThan(30);
            assertThat(total).as("총 소요시간 ≈ max(120,150,100,80) 이어야 한다 (합 450 아님, R7). 실제 %dms", total).isLessThan(260);
            assertThat(r.flights()).as("airline-a + airline-b, 오름차순").containsExactly("airline-a:ICN-NRT", "airline-b:ICN-NRT");
            assertThat(r.hotels()).containsExactly("hotel:ICN-NRT");
            assertThat(r.cars()).containsExactly("car:ICN-NRT");
            assertThat(r.unavailable()).isEmpty();
            String caller = Thread.currentThread().getName();
            for (FakeBlockingService v : all()) {
                assertThat(v.callCount()).as("%s 는 한 번만 호출", v.name()).isEqualTo(1);
                assertThat(v.callerThreads())
                        .as("%s 의 blocking 호출이 commonPool 이나 요청 스레드에서 실행됐다 (R2, R11): %s", v.name(), v.callerThreads())
                        .noneMatch(t -> t.startsWith("ForkJoinPool") || t.equals(caller));
            }
        }
    }

    @Test
    void p1_2_hangingVendor_isCutAt250ms_andItsCallIsInterrupted() {
        airlineB.latencyFor(ROUTE, 5_000);
        try (TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car)) {
            long start = System.nanoTime();
            SearchResult r = Grading.await(service.search(ROUTE), 2000, "검색");
            long total = Grading.since(start);
            assertThat(total).as("공급사별 최대 250ms, 전체 SLO 300ms. 실제 %dms", total).isBetween(230L, 320L);
            assertThat(r.flights()).containsExactly("airline-a:ICN-NRT");
            assertThat(r.unavailable()).containsExactly("airline-b");
            assertThat(r.hotels()).containsExactly("hotel:ICN-NRT");
            assertThat(Grading.pollUntil(() -> airlineB.inFlight() == 0, 300))
                    .as("시간 초과된 airline-b 호출이 여전히 스레드를 점유하고 있다. orTimeout 은 작업을 멈추지 않는다 (R10, #12)").isTrue();
            assertThat(airlineB.interruptedCount()).as("airline-b 호출은 interrupt 로 중단돼야 한다").isEqualTo(1);
        }
    }

    @Test
    void p1_3_failingVendor_givesPartialResult() {
        hotel.failOn(ROUTE, new IllegalStateException("hotel SDK 500"));
        try (TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car)) {
            long start = System.nanoTime();
            SearchResult r = Grading.await(service.search(ROUTE), 2000, "검색");
            assertThat(Grading.since(start)).isLessThan(300);
            assertThat(r.hotels()).as("실패한 공급사는 빈 목록").isEmpty();
            assertThat(r.unavailable()).containsExactly("hotel");
            assertThat(r.flights()).hasSize(2);
            assertThat(r.cars()).containsExactly("car:ICN-NRT");
        }
    }

    @Test
    void p1_4_everyVendorDown_stillAnswersWithinSlo_andFreesAllThreads() {
        for (FakeBlockingService v : all()) {
            v.latencyFor(ROUTE, 5_000);
        }
        try (TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car)) {
            long start = System.nanoTime();
            SearchResult r = Grading.await(service.search(ROUTE), 2000, "검색");
            assertThat(Grading.since(start)).as("모든 공급사가 응답하지 않아도 SLO 300ms").isLessThan(320);
            assertThat(r.flights()).isEmpty();
            assertThat(r.unavailable()).containsExactlyInAnyOrder("airline-a", "airline-b", "hotel", "car");
            assertThat(Grading.pollUntil(() -> all().stream().allMatch(v -> v.inFlight() == 0), 300))
                    .as("시간 초과된 호출 4개가 모두 중단돼야 한다 (R10)").isTrue();
        }
    }

    @Test
    void p1_5_peak30ConcurrentSearches_meetSlo_withoutQueueing() {
        try (TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car)) {
            Grading.await(service.search("warm-up"), 2000, "워밍업");
            long start = System.nanoTime();
            List<CompletableFuture<SearchResult>> fs = IntStream.range(0, 30)
                    .mapToObj(i -> service.search("r-" + i)).collect(Collectors.toList());
            List<SearchResult> results = new ArrayList<>();
            for (CompletableFuture<SearchResult> f : fs) {
                results.add(Grading.await(f, 3000, "피크 검색"));
            }
            long total = Grading.since(start);
            assertThat(results).as("피크(동시 30건)에서도 어떤 공급사도 빠지면 안 된다(풀 크기·큐 부족이면 unavailable 이 생긴다, R11)")
                    .allMatch(r -> r.unavailable().isEmpty());
            assertThat(total).as("동시 30건 × 4호출 = 120 blocking 호출이 한 번에 진행돼야 SLO 300ms 를 지킨다. 실제 %dms", total)
                    .isLessThan(300);
            assertThat(airlineB.maxConcurrency()).as("airline-b 호출 30건이 동시에 진행됐어야 한다").isGreaterThanOrEqualTo(30);
        }
    }

    @Test
    void p1_6_repeatedHangsDoNotExhaustThePool() {
        airlineB.latencyFor("hang", 5_000);
        try (TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car)) {
            for (int wave = 0; wave < 3; wave++) {
                List<CompletableFuture<SearchResult>> fs = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    fs.add(service.search(i % 2 == 0 ? "hang" : "ok-" + i));
                }
                for (CompletableFuture<SearchResult> f : fs) {
                    SearchResult r = Grading.await(f, 2000, "검색");
                    assertThat(r.unavailable()).as("wave %d: 응답하지 않는 airline-b 외에는 모두 결과가 있어야 한다", wave)
                            .isSubsetOf(Set.of("airline-b"));
                }
                assertThat(Grading.pollUntil(() -> airlineB.inFlight() == 0, 300))
                        .as("wave %d: 멈춘 airline-b 호출이 쌓이면 곧 풀이 고갈된다 (R10, #12)", wave).isTrue();
            }
        }
    }

    @Test
    void p1_7_close_terminatesTheServiceThreads() {
        Set<String> vendorThreads;
        TravelSearchService service = new TravelSearchService(airlineA, airlineB, hotel, car);
        try {
            Grading.await(service.search(ROUTE), 2000, "검색");
            vendorThreads = all().stream().flatMap(v -> v.callerThreads().stream()).collect(Collectors.toSet());
        } finally {
            service.close();
        }
        assertThat(Grading.pollUntil(() -> Grading.liveThreadNames().stream().noneMatch(vendorThreads::contains), 1000))
                .as("close() 뒤에도 서비스가 만든 스레드가 살아 있다: %s", vendorThreads).isTrue();
    }
}
