package async.m05;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.harness.Timed;

/**
 * m05 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>상황: Java 17 + Spring MVC(서블릿) 서비스의 "상품 상세 집계 API". 다운스트림은 {@link ProductBackends} 를 보라.
 * 앞쪽 네 메서드는 팀에서 실제로 나온 구현들이고, 뒤쪽 t01~l01 은 그 구현을 실행해 보는 시나리오다.
 * 풀 이름은 "io" (스레드 이름 io-1, io-2 ...).
 */
public final class Scenarios {

    private Scenarios() {
    }

    // ================================================================================================
    // 팀에서 나온 구현들
    // ================================================================================================

    /** 지금 운영 중인 코드. 컨트롤러가 요청 스레드(Tomcat)에서 직접 호출한다. */
    public static ProductDetail naiveSequential(ProductBackends b, String id) {
        String product = b.product(id);
        String price = b.price(id);
        String stock;
        try {
            stock = b.stock(id);
        } catch (RuntimeException e) {
            stock = ProductDetail.UNKNOWN_STOCK;
        }
        List<String> reviews;
        try {
            reviews = b.reviews(id);
        } catch (RuntimeException e) {
            reviews = List.of();
        }
        return new ProductDetail(product, price, stock, reviews);
    }

    /** 동료 PR #1: "CompletableFuture 로 비동기화해서 빨라졌습니다." */
    public static CompletableFuture<ProductDetail> commonPoolFanOut(ProductBackends b, String id) {
        CompletableFuture<String> product = CompletableFuture.supplyAsync(() -> b.product(id));
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> b.price(id));
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> b.stock(id));
        CompletableFuture<List<String>> reviews = CompletableFuture.supplyAsync(() -> b.reviews(id));
        return CompletableFuture.allOf(product, price, stock, reviews)
                .thenApply(v -> new ProductDetail(product.join(), price.join(), stock.join(), reviews.join()));
    }

    /** 동료 PR #2: "리뷰 반영해서 전용 풀(io)로 바꿨습니다. 이제 모든 호출이 비동기입니다." */
    public static CompletableFuture<ProductDetail> joinEachCall(ProductBackends b, String id, Executor io) {
        String product = CompletableFuture.supplyAsync(() -> b.product(id), io).join();
        String price = CompletableFuture.supplyAsync(() -> b.price(id), io).join();
        String stock = CompletableFuture.supplyAsync(() -> b.stock(id), io).join();
        List<String> reviews = CompletableFuture.supplyAsync(() -> b.reviews(id), io).join();
        return CompletableFuture.completedFuture(new ProductDetail(product, price, stock, reviews));
    }

    /** 병렬 fan-out 의 뼈대. 타임아웃, 폴백, 예외 변환이 <b>없다</b>. */
    public static CompletableFuture<ProductDetail> bareFanOut(ProductBackends b, String id, Executor io) {
        CompletableFuture<String> product = CompletableFuture.supplyAsync(() -> b.product(id), io);
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> b.price(id), io);
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> b.stock(id), io);
        CompletableFuture<List<String>> reviews = CompletableFuture.supplyAsync(() -> b.reviews(id), io);
        return CompletableFuture.allOf(product, price, stock, reviews)
                .thenApply(v -> new ProductDetail(product.join(), price.join(), stock.join(), reviews.join()));
    }

    // ================================================================================================
    // 시나리오
    // ================================================================================================

    /** T01. 기존 순차 코드. 응답시간(ms)과 product 호출이 실행된 스레드는? */
    public static Timed<ProductDetail> t01(ProductBackends b) {
        return Timed.of(() -> naiveSequential(b, "p1"));
    }

    /** T02. PR #1 을 실행해 결과까지 기다린다(테스트 JVM: JDK 25, CPU 여러 개). 응답시간과 price 호출 스레드는? */
    public static Timed<ProductDetail> t02(ProductBackends b) {
        return Timed.of(() -> commonPoolFanOut(b, "p1").join());
    }

    /** T03. PR #2. joinEachCall 이 future 를 <b>반환하기까지</b> 걸린 시간(ms)은? */
    public static Timed<CompletableFuture<ProductDetail>> t03(ProductBackends b, Executor io) {
        return Timed.of(() -> joinEachCall(b, "p1", io));
    }

    /** T04 결과: bareFanOut 이 future 를 반환하기까지(returned), 그리고 결과가 나오기까지(total) 걸린 시간. */
    public record FanOutTiming(long returnedMillis, long totalMillis) {
    }

    /** T04. 병렬 fan-out 을 충분한 풀(io, 8개)에서 실행한다. */
    public static FanOutTiming t04(ProductBackends b, Executor io) {
        long start = System.nanoTime();
        CompletableFuture<ProductDetail> f = bareFanOut(b, "p1", io);
        long returned = (System.nanoTime() - start) / 1_000_000;
        f.join();
        return new FanOutTiming(returned, (System.nanoTime() - start) / 1_000_000);
    }

    /** T05. 같은 병렬 fan-out 을 스레드 <b>2개</b>짜리 풀에서 실행한다. 결과까지 걸린 시간은? */
    public static long t05(ProductBackends b) {
        try (NamedPool io = Pools.fixed("io", 2)) {
            long start = System.nanoTime();
            bareFanOut(b, "p1", io).join();
            return (System.nanoTime() - start) / 1_000_000;
        }
    }

    /** T06 관찰 결과. */
    public record TimeoutObservation(long failedAtMillis, int priceInFlightAtFailure, Class<?> exceptionSeenByHandle,
                                     On handleThread) {
    }

    /**
     * T06. 가격 API 가 장애로 1000ms 걸린다. 병렬 fan-out 에 전체 타임아웃 300ms 를 걸었다.
     * <ul>
     *   <li>failedAtMillis: handle 이 실행된 시점 (시작 후 ms)</li>
     *   <li>priceInFlightAtFailure: 그 시점에 아직 진행 중인 가격 API 호출 수</li>
     *   <li>exceptionSeenByHandle: handle 이 받은 ex 의 클래스</li>
     *   <li>handleThread: handle 이 실행된 스레드</li>
     * </ul>
     */
    public static TimeoutObservation t06(ProductBackends b, Executor io) {
        b.priceApi().latencyFor("p1", 1000);
        ThreadRecorder rec = ThreadRecorder.start();
        long start = System.nanoTime();
        CompletableFuture<ProductDetail> detail = bareFanOut(b, "p1", io).orTimeout(300, TimeUnit.MILLISECONDS);
        CompletableFuture<Throwable> seen = detail
                .thenApply(ProductDetail::product)
                .handle((v, ex) -> {
                    rec.mark("handle");
                    return ex;
                });
        Throwable ex = seen.join();
        long failedAt = (System.nanoTime() - start) / 1_000_000;
        int priceInFlight = b.priceApi().inFlight();
        return new TimeoutObservation(failedAt, priceInFlight, ex.getClass(), rec.on("handle"));
    }

    /**
     * L01. 100 RPS(10ms 간격)로 요청 40개를 보낸다. 요청마다 병렬 fan-out + 전체 타임아웃 300ms.
     * 풀 크기가 poolThreads 일 때 결과는 {@link LoadOutcome} 중 무엇인가?
     */
    public static LoadDriver.LoadReport l01(int poolThreads) {
        ProductBackends b = new ProductBackends();
        try (NamedPool io = Pools.fixed("io", poolThreads)) {
            return LoadDriver.run(40, 10, id -> bareFanOut(b, id, io).orTimeout(300, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * L02. 리뷰 DB 가 장애로 <b>모든 요청에서</b> 1000ms 걸린다. 다행히 리뷰에는 소스별 타임아웃 200ms(폴백: 빈 목록)가 걸려 있다.
     * 풀 60개(L01 의 큰 풀과 같은 크기), 100 RPS 로 요청 150개(1.5초). 결과는 {@link LoadOutcome} 중 무엇인가?
     */
    public static LoadDriver.LoadReport l02() {
        ProductBackends b = new ProductBackends();
        for (int i = 0; i < 150; i++) {
            b.reviewDb().latencyFor("p" + i, 1000);
        }
        try (NamedPool io = Pools.fixed("io", 60)) {
            return LoadDriver.run(150, 10, id -> {
                CompletableFuture<String> product = CompletableFuture.supplyAsync(() -> b.product(id), io);
                CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> b.price(id), io);
                CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> b.stock(id), io);
                CompletableFuture<List<String>> reviews = CompletableFuture.supplyAsync(() -> b.reviews(id), io)
                        .completeOnTimeout(List.of(), 200, TimeUnit.MILLISECONDS);     // 리뷰는 200ms 에 포기하고 빈 목록
                return CompletableFuture.allOf(product, price, stock, reviews)
                        .thenApply(v -> new ProductDetail(product.join(), price.join(), stock.join(), reviews.join()))
                        .orTimeout(300, TimeUnit.MILLISECONDS);
            });
        }
    }
}
