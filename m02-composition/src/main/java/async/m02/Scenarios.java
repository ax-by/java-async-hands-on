package async.m02;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.Sleep;
import async.harness.ThreadRecorder;

/**
 * m02 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>시간 예측은 {@code rec.mark("done")} 같은 지점이 "기록기 시작 후 몇 ms 에" 실행됐는지를 묻는다.
 * 기록기는 시나리오를 호출하기 직전에 시작된다고 보면 된다. 시간은 100ms 단위로만 차이 나게 설계했다.
 *
 * <p>실행 풀 이름: io(스레드 4개), small(스레드 2개), a, b(각 스레드 1개).
 * {@link FakeBlockingService#call(String)} 은 지정한 지연시간만큼 호출 스레드를 막은 뒤 "{서비스 이름}:{key}" 를 반환한다.
 */
public final class Scenarios {

    private Scenarios() {
    }

    /** 비동기 주문 클라이언트 흉내: 호출 즉시 future 를 반환하고, io 스레드에서 200ms 걸려 완료된다. */
    static CompletableFuture<String> fetchOrdersAsync(FakeBlockingService orderDb, String user, Executor io) {
        return CompletableFuture.supplyAsync(() -> orderDb.call(user), io);
    }

    /**
     * S01. 사용자를 조회한 뒤, 그 사용자의 주문을 조회한다. 그런데 thenApply 를 썼다.
     * "joined": 바깥 join 이 반환된 시점(ms). 반환값의 클래스도 예측하라.
     */
    public static Object s01(ThreadRecorder rec, Executor io) {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 200);
        Object joined = CompletableFuture.supplyAsync(() -> userDb.call("u1"), io)
                .thenApply(user -> fetchOrdersAsync(orderDb, user, io))
                .join();
        rec.mark("joined");
        return joined;
    }

    /** S02. S01 을 thenCompose 로 고쳤다. "done": join 이 반환된 시점(ms). */
    public static String s02(ThreadRecorder rec, Executor io) {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 200);
        String orders = CompletableFuture.supplyAsync(() -> userDb.call("u1"), io)
                .thenCompose(user -> fetchOrdersAsync(orderDb, user, io))
                .join();
        rec.mark("done");
        return orders;
    }

    /** S03. 상품(100ms)과 가격(300ms)을 먼저 모두 시작하고 thenCombine 으로 합친다. "done"(ms)? */
    public static String s03(ThreadRecorder rec, Executor io) {
        FakeBlockingService productDb = new FakeBlockingService("product-db", 100);
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 300);
        CompletableFuture<String> product = CompletableFuture.supplyAsync(() -> productDb.call("p1"), io);
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> priceApi.call("p1"), io);
        String page = product.thenCombine(price, (p, q) -> p + " / " + q).join();
        rec.mark("done");
        return page;
    }

    /** S04. 상품 3개의 재고를 조회한다(300ms, 100ms, 200ms). 루프 안에서 supplyAsync 후 바로 join 한다. "done"(ms)? */
    public static List<String> s04(ThreadRecorder rec, Executor io) {
        FakeBlockingService stockDb = new FakeBlockingService("stock-db", 100)
                .latencyFor("p1", 300).latencyFor("p2", 100).latencyFor("p3", 200);
        List<String> stocks = new ArrayList<>();
        for (String sku : List.of("p1", "p2", "p3")) {
            stocks.add(CompletableFuture.supplyAsync(() -> stockDb.call(sku), io).join());
        }
        rec.mark("done");
        return stocks;
    }

    /** S05. S04 와 같은 조회. 이번에는 먼저 전부 시작해 리스트에 담고, 그다음 루프에서 하나씩 join 한다. "done"(ms)? */
    public static List<String> s05(ThreadRecorder rec, Executor io) {
        FakeBlockingService stockDb = new FakeBlockingService("stock-db", 100)
                .latencyFor("p1", 300).latencyFor("p2", 100).latencyFor("p3", 200);
        List<CompletableFuture<String>> started = new ArrayList<>();
        for (String sku : List.of("p1", "p2", "p3")) {
            started.add(CompletableFuture.supplyAsync(() -> stockDb.call(sku), io));
        }
        List<String> stocks = new ArrayList<>();
        for (CompletableFuture<String> f : started) {
            stocks.add(f.join());
        }
        rec.mark("done");
        return stocks;
    }

    /**
     * S06. 상품 6개의 가격(각 100ms)을 스레드 2개짜리 풀(small)에서 병렬로 조회한다.
     * "done"(ms)과 가격 API 의 최대 동시 호출 수를 예측하라.
     */
    public static int s06(ThreadRecorder rec, Executor small) {
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 100);
        List<CompletableFuture<String>> prices = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String sku = "p" + i;
            prices.add(CompletableFuture.supplyAsync(() -> priceApi.call(sku), small));
        }
        CompletableFuture.allOf(prices.toArray(new CompletableFuture[0])).join();
        rec.mark("done");
        return priceApi.maxConcurrency();
    }

    /**
     * S07. allOf 로 세 결과를 모은다. 완료 순서는 io → a → b 다.
     * 모으는 콜백(collect)은 어느 스레드에서 실행될까? 결과 문자열은?
     */
    public static String s07(ThreadRecorder rec, Executor a, Executor b, Executor io) {
        Gate gateA = new Gate();
        Gate gateB = new Gate();
        Gate gateIo = new Gate();
        CompletableFuture<String> fa = CompletableFuture.supplyAsync(() -> {
            gateA.await();
            return "A";
        }, a);
        CompletableFuture<String> fb = CompletableFuture.supplyAsync(() -> {
            gateB.await();
            return "B";
        }, b);
        CompletableFuture<String> fc = CompletableFuture.supplyAsync(() -> {
            gateIo.await();
            return "C";
        }, io);
        List<CompletableFuture<String>> all = List.of(fa, fb, fc);
        CompletableFuture<String> collected = CompletableFuture.allOf(fa, fb, fc).thenApply(v -> {
            rec.mark("collect");
            return all.stream().map(CompletableFuture::join).collect(Collectors.joining(","));
        });
        gateIo.open();
        fc.join();
        gateA.open();
        fa.join();
        gateB.open();
        return collected.join();
    }

    /**
     * S08. 주문을 불러온 뒤 "검증"과 "보강"을 각각 붙였다. 검증은 실패한다.
     * 반환값은? 검증 콜백(validate)은 실행됐을까?
     */
    public static String s08(ThreadRecorder rec, Executor io) {
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 100);
        CompletableFuture<String> order = CompletableFuture.supplyAsync(() -> orderDb.call("o1"), io);
        order.thenAccept(o -> {
            rec.mark("validate");
            throw new IllegalStateException("결제 수단이 만료된 주문: " + o);
        });
        CompletableFuture<String> result = order.thenApply(o -> o + "+enriched");
        String v = result.join();
        Sleep.ms(100); // (결정성 장치) 다른 분기가 실행될 시간을 준다
        return v;
    }

    /** S09. 기본 가격(1000)에 쿠폰(-100)과 배송비(+30)를 반영하려 했다. 반환값은? */
    public static int s09(Executor io) {
        CompletableFuture<Integer> base = CompletableFuture.supplyAsync(() -> 1000, io);
        base.thenApply(p -> p - 100);
        return base.thenApply(p -> p + 30).join();
    }

    static CompletableFuture<String> loadUser(FakeBlockingService userDb, Executor io) {
        return CompletableFuture.supplyAsync(() -> userDb.call("u1"), io);
    }

    /** S10. 사용자 조회 결과를 여러 곳에서 쓴다. userDb 는 모두 몇 번 호출될까? */
    public static int s10(Executor io) {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        CompletableFuture<String> user = loadUser(userDb, io);
        CompletableFuture<String> upper = user.thenApply(String::toUpperCase);
        CompletableFuture<Integer> length = user.thenApply(String::length);
        CompletableFuture<String> pair = loadUser(userDb, io).thenCombine(loadUser(userDb, io), (x, y) -> x + y);
        CompletableFuture.allOf(upper, length, pair).join();
        return userDb.callCount();
    }

    /**
     * S11. 재고를 캐시(100ms, 장애로 실패)와 DB(300ms, 성공)에 동시에 물어보고 anyOf 로 먼저 온 것을 쓴다.
     * anyOf 결과는 실패일까? "done"(ms)? anyOf 가 끝난 직후 DB 호출은 몇 개 진행 중일까?
     * 반환값: [실패했는가(1/0), 그 직후 stockDb.inFlight()]
     */
    public static int[] s11(ThreadRecorder rec, Executor io) {
        FakeBlockingService stockCache = new FakeBlockingService("stock-cache", 100)
                .failOn("p1", new IllegalStateException("캐시 연결 끊김"));
        FakeBlockingService stockDb = new FakeBlockingService("stock-db", 300);
        CompletableFuture<String> cache = CompletableFuture.supplyAsync(() -> stockCache.call("p1"), io);
        CompletableFuture<String> db = CompletableFuture.supplyAsync(() -> stockDb.call("p1"), io);
        int failed;
        try {
            CompletableFuture.anyOf(cache, db).join();
            failed = 0;
        } catch (CompletionException e) {
            failed = 1;
        }
        rec.mark("done");
        int inFlight = stockDb.inFlight();
        db.join();
        return new int[] {failed, inFlight};
    }

    /** S12. primary(300ms)와 replica(100ms)에 같은 조회를 보내고 applyToEither 로 먼저 온 것을 쓴다. "done"(ms)? */
    public static String s12(ThreadRecorder rec, Executor io) {
        FakeBlockingService primary = new FakeBlockingService("primary", 300);
        FakeBlockingService replica = new FakeBlockingService("replica", 100);
        CompletableFuture<String> p = CompletableFuture.supplyAsync(() -> primary.call("p1"), io);
        CompletableFuture<String> r = CompletableFuture.supplyAsync(() -> replica.call("p1"), io);
        String v = p.applyToEither(r, x -> "view(" + x + ")").join();
        rec.mark("done");
        p.join();
        return v;
    }

    /** S13. 둘 다 이미 완료된 future 를 thenCombine 한다. combine 함수는 어느 스레드에서 실행될까? */
    public static String s13(ThreadRecorder rec, Executor a, Executor b) {
        CompletableFuture<String> fa = CompletableFuture.supplyAsync(() -> "A", a);
        CompletableFuture<String> fb = CompletableFuture.supplyAsync(() -> "B", b);
        fa.join();
        fb.join();
        return fa.thenCombine(fb, (x, y) -> {
            rec.mark("combine");
            return x + y;
        }).join();
    }

    /**
     * S14. 100/200/300ms 걸리는 세 조회를 allOf 로 기다린 뒤, 콜백 안에서 각 future 를 join 한다.
     * 콜백 안에서 join 들이 걸린 시간(ms)을 예측하라. (반환값이 그 시간이다)
     */
    public static long s14(Executor io) {
        FakeBlockingService api = new FakeBlockingService("api", 100).latencyFor("k2", 200).latencyFor("k3", 300);
        List<CompletableFuture<String>> fs = List.of(
                CompletableFuture.supplyAsync(() -> api.call("k1"), io),
                CompletableFuture.supplyAsync(() -> api.call("k2"), io),
                CompletableFuture.supplyAsync(() -> api.call("k3"), io));
        return CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).thenApply(v -> {
            long start = System.nanoTime();
            fs.forEach(CompletableFuture::join);
            return (System.nanoTime() - start) / 1_000_000;
        }).join();
    }

    /**
     * S15. 사용자(100ms)를 먼저 조회하고, 그 사용자로 주문(200ms)과 포인트(300ms)를 조회해 합친다. "done"(ms)?
     */
    public static String s15(ThreadRecorder rec, Executor io) {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        FakeBlockingService orderDb = new FakeBlockingService("order-db", 200);
        FakeBlockingService pointApi = new FakeBlockingService("point-api", 300);
        String view = CompletableFuture.supplyAsync(() -> userDb.call("u1"), io)
                .thenCompose(user -> {
                    CompletableFuture<String> orders = CompletableFuture.supplyAsync(() -> orderDb.call(user), io);
                    CompletableFuture<String> points = CompletableFuture.supplyAsync(() -> pointApi.call(user), io);
                    return orders.thenCombine(points, (o, p) -> o + " / " + p);
                })
                .join();
        rec.mark("done");
        return view;
    }

    /**
     * S16. 쿠폰 조회는 100ms 만에 실패하고, 배송 조회는 300ms 걸려 성공한다. allOf 의 join 은 언제 반환(예외)될까? "done"(ms)
     */
    public static void s16(ThreadRecorder rec, Executor io) {
        FakeBlockingService couponApi = new FakeBlockingService("coupon-api", 100)
                .failOn("u1", new IllegalStateException("쿠폰 서버 500"));
        FakeBlockingService shippingApi = new FakeBlockingService("shipping-api", 300);
        CompletableFuture<String> coupon = CompletableFuture.supplyAsync(() -> couponApi.call("u1"), io);
        CompletableFuture<String> shipping = CompletableFuture.supplyAsync(() -> shippingApi.call("u1"), io);
        try {
            CompletableFuture.allOf(coupon, shipping).join();
        } catch (CompletionException expected) {
            rec.mark("done");
        }
    }

    /** S17. S16 과 같은 두 조회를 thenCombine 으로 합친다. join 은 언제 반환(예외)될까? "done"(ms) */
    public static void s17(ThreadRecorder rec, Executor io) {
        FakeBlockingService couponApi = new FakeBlockingService("coupon-api", 100)
                .failOn("u1", new IllegalStateException("쿠폰 서버 500"));
        FakeBlockingService shippingApi = new FakeBlockingService("shipping-api", 300);
        CompletableFuture<String> coupon = CompletableFuture.supplyAsync(() -> couponApi.call("u1"), io);
        CompletableFuture<String> shipping = CompletableFuture.supplyAsync(() -> shippingApi.call("u1"), io);
        try {
            coupon.thenCombine(shipping, (c, s) -> c + s).join();
        } catch (CompletionException expected) {
            rec.mark("done");
        }
    }
}
