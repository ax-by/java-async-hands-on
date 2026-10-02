package async.m11.p1;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeBlockingService;
// SOLUTION-REMOVE-BEGIN (모범 답안에서만 쓰는 import: 설계 힌트가 되지 않도록 main 에서 제거)
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import async.harness.Pools;
// SOLUTION-REMOVE-END

/**
 * P1. 여행 통합 검색. 요구사항은 {@code p1-travel-search/PROBLEM.md}, 설계 근거는 {@code DECISION.md} 에 먼저 쓴다.
 *
 * <p>공급사 SDK 4개는 모두 blocking 이다({@link FakeBlockingService}: 호출 스레드를 응답 시간만큼 멈추고,
 * interrupt 되면 즉시 중단한다). 이름: "airline-a", "airline-b", "hotel", "car". 각 SDK 의 {@code call(route)} 를 부른다.
 *
 * <p>필요한 필드(executor 등)는 자유롭게 추가한다. 생성자·메서드 시그니처는 바꾸지 않는다.
 */
public final class TravelSearchService implements AutoCloseable {

    private final FakeBlockingService airlineA;
    private final FakeBlockingService airlineB;
    private final FakeBlockingService hotel;
    private final FakeBlockingService car;

    // SOLUTION-REMOVE-BEGIN
    static final long VENDOR_TIMEOUT_MS = 250;
    /**
     * 공급사 호출 전용 blocking 풀. 크기 근거(Little): 피크 동시 검색 30건 × 검색당 호출 4개 = 동시 blocking 호출 120 → 128.
     * 큐 256 + AbortPolicy. 코어 수에 도달한 TPE 는 놀고 있는 스레드가 있어도 큐를 거쳐 전달하므로, 큐가 제출 버스트(동시 120)보다
     * 작으면 멀쩡한 호출이 거절된다. 대기 시간의 상한은 큐 크기가 아니라 공급사별 타임아웃(250ms, 제출 시점부터)이 정한다.
     */
    private final ThreadPoolExecutor vendorPool;
    // SOLUTION-REMOVE-END

    public TravelSearchService(FakeBlockingService airlineA, FakeBlockingService airlineB,
                               FakeBlockingService hotel, FakeBlockingService car) {
        this.airlineA = airlineA;
        this.airlineB = airlineB;
        this.hotel = hotel;
        this.car = car;
        // TODO(P1): 이 서비스가 쓸 실행 자원을 만든다. 크기·큐·거절 정책의 근거는 DECISION.md 에 (R11)
        // SOLUTION-REMOVE-BEGIN
        this.vendorPool = new ThreadPoolExecutor(128, 128, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), Pools.namedFactory("vendor"), new ThreadPoolExecutor.AbortPolicy());
        // SOLUTION-REMOVE-END
    }

    /**
     * 노선(route, 예: "ICN-NRT")으로 네 공급사를 검색해 합친다. Spring MVC 컨트롤러가 반환값을 그대로 돌려준다(서블릿 async).
     */
    public CompletableFuture<SearchResult> search(String route) {
        // TODO(P1): DECISION.md 를 먼저 채운 뒤 구현한다 (R0, R7, R10, R11)
        // SOLUTION-BEGIN
        CompletableFuture<String> a = callVendor(airlineA, route);   // 네 호출을 먼저 모두 시작 (R7)
        CompletableFuture<String> b = callVendor(airlineB, route);
        CompletableFuture<String> h = callVendor(hotel, route);
        CompletableFuture<String> c = callVendor(car, route);
        return CompletableFuture.allOf(a, b, h, c)                    // 각 분기는 실패하지 않는다(null = unavailable)
                .thenApply(v -> {                                     // [마지막에 완료된 분기의 스레드] 가벼운 조립
                    Set<String> unavailable = new TreeSet<>();
                    List<String> flights = new ArrayList<>();
                    collect(a.join(), airlineA, flights, unavailable);   // 모두 완료됨 → 막지 않음 (R5)
                    collect(b.join(), airlineB, flights, unavailable);
                    flights.sort(null);
                    List<String> hotels = new ArrayList<>();
                    collect(h.join(), hotel, hotels, unavailable);
                    List<String> cars = new ArrayList<>();
                    collect(c.join(), car, cars, unavailable);
                    return new SearchResult(flights, hotels, cars, unavailable);
                });
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    private static void collect(String response, FakeBlockingService vendor, List<String> into, Set<String> unavailable) {
        if (response == null) {
            unavailable.add(vendor.name());
        } else {
            into.add(response);
        }
    }

    /**
     * 공급사 호출 하나. 결과 future 는 절대 실패하지 않는다: 실패·250ms 초과·거절이면 null.
     * 시간 초과 시 Future.cancel(true) 로 실제 호출을 interrupt 해 스레드를 돌려준다 (R10: orTimeout 만으로는 작업이 멈추지 않는다).
     */
    private CompletableFuture<String> callVendor(FakeBlockingService vendor, String route) {
        CompletableFuture<String> response = new CompletableFuture<>();
        Future<?> task;
        try {
            task = vendorPool.submit(() -> {
                try {
                    response.complete(vendor.call(route));          // [vendor] [blocking]
                } catch (Throwable t) {
                    response.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            return CompletableFuture.completedFuture(null);         // 설계 한도 초과: 이 공급사는 unavailable (R3 예외 2)
        }
        response.orTimeout(VENDOR_TIMEOUT_MS, TimeUnit.MILLISECONDS) // response 자체에 상한 (orTimeout 은 this 반환)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        task.cancel(true);                          // 큐에 있으면 실행 안 됨, 실행 중이면 interrupt
                    }
                });
        return response.handle((v, ex) -> ex == null ? v : null);
    }
    // SOLUTION-REMOVE-END

    /** 이 서비스가 만든 스레드를 정리한다. */
    @Override
    public void close() {
        // SOLUTION-BEGIN
        vendorPool.shutdownNow();
        // SOLUTION-END
    }
}
