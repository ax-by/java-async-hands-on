package async.m11.p1;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeBlockingService;

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


    public TravelSearchService(FakeBlockingService airlineA, FakeBlockingService airlineB,
                               FakeBlockingService hotel, FakeBlockingService car) {
        this.airlineA = airlineA;
        this.airlineB = airlineB;
        this.hotel = hotel;
        this.car = car;
        // TODO(P1): 이 서비스가 쓸 실행 자원을 만든다. 크기·큐·거절 정책의 근거는 DECISION.md 에 (R11)
    }

    /**
     * 노선(route, 예: "ICN-NRT")으로 네 공급사를 검색해 합친다. Spring MVC 컨트롤러가 반환값을 그대로 돌려준다(서블릿 async).
     */
    public CompletableFuture<SearchResult> search(String route) {
        // TODO(P1): DECISION.md 를 먼저 채운 뒤 구현한다 (R0, R7, R10, R11)
        throw new UnsupportedOperationException("TODO");
    }


    /** 이 서비스가 만든 스레드를 정리한다. */
    @Override
    public void close() {
        throw new UnsupportedOperationException("TODO");
    }
}
