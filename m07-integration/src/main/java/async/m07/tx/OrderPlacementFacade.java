package async.m07.tx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import async.harness.FakeBlockingService;
import async.harness.Pools.NamedPool;

/**
 * E4. 트랜잭션 안에서 쓰기를 다른 스레드로 넘기는 S11 의 설계를 고친다.
 *
 * <p>요구사항
 * <ol>
 *   <li>재고(stockApi)와 가격(priceApi) 조회는 서로 독립이다. lookupPool 에서 <b>병렬로</b> 실행해 ≈200ms 에 끝낸다(A 설계).</li>
 *   <li>DB 쓰기(orders + audit)는 {@link PlacementExercise.OrderWriter#write} 한 번으로, <b>호출 스레드에서</b> 한 트랜잭션으로 한다.
 *       실패하면 둘 다 롤백되어야 한다.</li>
 *   <li>외부 API 를 기다리는 동안 트랜잭션(=DB 커넥션)을 잡고 있지 않는다. 즉 이 클래스에는 @Transactional 을 붙이지 않는다.</li>
 *   <li>조회 전체 타임아웃 1초.</li>
 * </ol>
 * 조회 키는 {@code "o" + id} 다.
 */
@Service
public class OrderPlacementFacade {

    private final FakeBlockingService stockApi;
    private final FakeBlockingService priceApi;
    private final NamedPool lookupPool;
    private final PlacementExercise.OrderWriter writer;

    public OrderPlacementFacade(@Qualifier("stockApi") FakeBlockingService stockApi,
            @Qualifier("priceApi") FakeBlockingService priceApi,
            NamedPool lookupPool, PlacementExercise.OrderWriter writer) {
        this.stockApi = stockApi;
        this.priceApi = priceApi;
        this.lookupPool = lookupPool;
        this.writer = writer;
    }

    public void place(int id, boolean failAfterInsert) {
        // TODO(E4): 병렬 조회는 CF 로(트랜잭션 밖), 쓰기는 호출 스레드에서 writer 한 번으로 (R7, R12)
        throw new UnsupportedOperationException("TODO");
    }
}
