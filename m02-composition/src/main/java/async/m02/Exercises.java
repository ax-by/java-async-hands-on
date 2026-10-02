package async.m02;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * m02 구현 실습. 각 메서드의 TODO 를 구현한다. 채점: ExercisesTest
 *
 * <p>공통 요구사항: 모든 메서드는 <b>호출 즉시 future 를 반환</b>해야 한다(호출 스레드를 막지 않는다, R3/R5).
 * 메서드 본문에서 {@code join()}/{@code get()} 으로 기다리면 테스트가 실패한다.
 */
public final class Exercises {

    private Exercises() {
    }

    /**
     * E1. future 목록을 "목록의 future" 로 바꿔라.
     * <ul>
     *   <li>결과 리스트의 순서는 <b>입력 순서</b>와 같아야 한다(완료 순서가 아니다).</li>
     *   <li>입력 중 하나라도 실패하면 결과도 실패한다.</li>
     *   <li>빈 목록이면 빈 리스트로 즉시 완료된다.</li>
     * </ul>
     * 실무에서는 이 메서드가 거의 모든 fan-out 코드의 마지막 단계다. 한 번 제대로 써 두고 계속 쓴다.
     */
    public static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        // TODO(E1): allOf 로 "모두 끝남"을 기다린 뒤, 그 콜백 안에서 join 으로 꺼낸다. 그 join 은 왜 막히지 않는가? (R5, R13)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E2. 아래는 운영 중인 코드다. 상품 N개의 가격을 조회하는데 "병렬로 하려고" CF 를 썼다.
     * <pre>{@code
     * List<String> prices = new ArrayList<>();
     * for (String sku : skus) {
     *     prices.add(CompletableFuture.supplyAsync(() -> priceApi.call(sku), io).join());
     * }
     * return prices;
     * }</pre>
     * 이 코드를 고쳐서, 호출 즉시 future 를 반환하고 io 풀 안에서 실제로 병렬 실행되게 하라.
     * 결과 순서는 skus 순서와 같아야 한다. 총 소요시간은 ≈ ceil(N / io 스레드 수) × 지연시간이어야 한다.
     */
    public static CompletableFuture<List<String>> fetchPrices(FakeBlockingService priceApi, List<String> skus, Executor io) {
        // TODO(E2): "시작"과 "대기"를 분리한다. 먼저 전부 시작해 담고, 마지막에 한 번만 모은다 (R7)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E3. 사용자를 JDBC(blocking, userDb)로 조회하고, 그 결과(user)로 주문을 비동기 HTTP 클라이언트(orderClient)로 조회하라.
     * <ul>
     *   <li>userDb 호출은 io 풀에서 실행한다(호출 스레드나 commonPool 이 아니다).</li>
     *   <li>orderClient.fetch(user) 는 future 를 반환한다. 결과는 "orders:user-db:u1" 같은 문자열이다.</li>
     *   <li>io 풀의 스레드가 1개뿐이어도 동작해야 한다.</li>
     * </ul>
     * 생각해 볼 것: 이 메서드는 A 인가 B 인가? 어느 구간이 스레드를 점유하는가? (R0)
     */
    public static CompletableFuture<String> ordersOfUser(FakeBlockingService userDb, FakeEventLoopClient orderClient,
                                                         String userId, Executor io) {
        // TODO(E3): 함수가 future 를 반환하면 무엇을 써야 하는가? 람다 안에서 join 하면 어떻게 되는가? (R13, R4, R11)
        throw new UnsupportedOperationException("TODO");
    }

    /** 상품 상세 화면에 필요한 세 가지 정보. */
    public record ProductPage(String product, String price, String stock) {
    }

    /**
     * E4. 상품(products), 가격(prices), 재고(stocks)는 서로 독립이다. 셋을 동시에 조회해 ProductPage 로 합쳐라.
     * 총 소요시간은 셋 중 가장 느린 것과 같아야 한다.
     */
    public static CompletableFuture<ProductPage> productPage(FakeBlockingService products, FakeBlockingService prices,
                                                             FakeBlockingService stocks, String id, Executor io) {
        // TODO(E4): 독립 future 셋을 먼저 시작하고 합친다. thenCombine 을 두 번 쓰거나 allOf 를 쓴다 (R7)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E5. 여러 복제본에 같은 요청을 보냈다(hedged request). <b>가장 먼저 성공한</b> 결과로 완료되는 future 를 만들어라.
     * <ul>
     *   <li>실패는 무시한다. 단, <b>모두 실패</b>하면 결과도 실패한다(마지막으로 실패한 입력의 예외로).</li>
     *   <li>빈 목록이면 IllegalArgumentException 으로 실패한 future 를 반환한다.</li>
     *   <li>{@code anyOf} 와 무엇이 다른가? anyOf 는 먼저 끝난 것이 실패여도 그 실패로 끝난다(R13).</li>
     * </ul>
     */
    public static <T> CompletableFuture<T> firstSuccessful(List<CompletableFuture<T>> futures) {
        // TODO(E5): new CompletableFuture<>() 를 만들고 각 입력에 whenComplete 를 건다. complete 는 처음 한 번만 이긴다 (R13, R6)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E6. 주문 저장 후 감사 로그(audit)와 알림(notifier)을 보낸다. 아래 코드는 테스트를 통과하지만 운영에서 문제가 됐다.
     * <pre>{@code
     * CompletableFuture<String> saved = CompletableFuture.supplyAsync(() -> orderDb.call(orderId), io);
     * saved.thenAccept(s -> audit.call(s));                // 감사 로그
     * saved.thenAccept(s -> notifier.call(s));             // 알림
     * return saved;
     * }</pre>
     * 요구사항
     * <ul>
     *   <li>반환 future 는 저장 결과(orderDb 의 반환값)로 완료되지만, <b>감사 로그와 알림이 모두 끝난 뒤</b>에 완료된다.</li>
     *   <li>감사 로그나 알림이 실패하면 반환 future 도 실패한다(실패가 사라지면 안 된다).</li>
     *   <li>감사 로그와 알림은 서로 독립이므로 저장이 끝난 뒤 <b>동시에</b> 실행한다. 모든 blocking 호출은 io 풀에서 실행한다.</li>
     * </ul>
     */
    public static CompletableFuture<String> placeOrder(FakeBlockingService orderDb, FakeBlockingService audit,
                                                       FakeBlockingService notifier, String orderId, Executor io) {
        // TODO(E6): 버려진 분기(R6)를 하나의 체인으로 모은다. 저장 → (감사 ∥ 알림) → 저장 결과 (R7, R13)
        throw new UnsupportedOperationException("TODO");
    }
}
