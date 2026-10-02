package async.m03;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import async.harness.FakeBlockingService;
import async.harness.Sleep;
import async.harness.ThreadRecorder;
import async.m03.Errors.ExternalApiException;
import async.m03.Errors.OutOfStockException;
import async.m03.Errors.PaymentDeclinedException;

/**
 * m03 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>실행 풀: io(스레드 4개). {@link FakeBlockingService#call(String)} 은 지연시간만큼 호출 스레드를 막고,
 * {@code failOn} 으로 지정한 key 면 지연 후 그 예외를 던진다. interrupt 되면 즉시 중단하고 interruptedCount 를 올린다.
 *
 * <p>{@link Outcome#of} 는 결과를 {@code "ok:<값>"} 또는 {@code "failed:<근본 원인 클래스 단순 이름>"} 으로 요약한다.
 */
public final class Scenarios {

    private Scenarios() {
    }

    /**
     * S01. 재고 예약이 실패한다. 어떤 단계가 어떤 순서로 실행될까? (실행된 단계 이름을 쉼표로 이어 적는다)
     */
    public static String s01(ThreadRecorder rec, Executor io) {
        FakeBlockingService stockApi = new FakeBlockingService("stock-api", 50)
                .failOn("p1", new OutOfStockException("p1"));
        CompletableFuture.supplyAsync(() -> stockApi.call("p1"), io)
                .thenApply(s -> {
                    rec.mark("reserve");
                    return s + ":reserved";
                })
                .thenAccept(s -> rec.mark("charge"))
                .exceptionally(ex -> {
                    rec.mark("recover");
                    return null;
                })
                .thenRun(() -> rec.mark("notify"))
                .join();
        return String.join(",", rec.order());
    }

    /** S02. supplyAsync 안에서 던진 예외. 바로 붙인 exceptionally 는 어떤 타입을 받을까? */
    public static Class<?> s02(Executor io) {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CompletableFuture.supplyAsync(() -> {
                    throw new OutOfStockException("p1");
                }, io)
                .exceptionally(ex -> {
                    seen.set(ex);
                    return null;
                })
                .join();
        return seen.get().getClass();
    }

    /**
     * S03. 콜백 API 어댑터(m01 E3)가 completeExceptionally 로 결제 거절을 전달한다.
     * (a) 바로 붙인 exceptionally 와 (b) thenApply 를 하나 거친 exceptionally 는 각각 어떤 타입을 받을까?
     */
    public static Class<?>[] s03() {
        AtomicReference<Throwable> direct = new AtomicReference<>();
        AtomicReference<Throwable> afterMap = new AtomicReference<>();
        CompletableFuture<String> payment = new CompletableFuture<>();
        CompletableFuture<String> a = payment.exceptionally(ex -> {
            direct.set(ex);
            return "declined";
        });
        CompletableFuture<String> b = payment.thenApply(String::toUpperCase).exceptionally(ex -> {
            afterMap.set(ex);
            return "declined";
        });
        payment.completeExceptionally(new PaymentDeclinedException("한도 초과"));
        a.join();
        b.join();
        return new Class<?>[] {direct.get().getClass(), afterMap.get().getClass()};
    }

    /**
     * S04. 실패한 future 를 get() 과 join() 으로 기다린다.
     * (a) get() 이 던진 예외의 getCause() 타입, (b) join() 이 던진 예외의 타입은?
     */
    public static Class<?>[] s04(Executor io) {
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            throw new OutOfStockException("p1");
        }, io);
        Class<?> getCause = null;
        Class<?> joinThrew = null;
        try {
            f.get();
        } catch (ExecutionException e) {
            getCause = e.getCause().getClass();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            f.join();
        } catch (RuntimeException e) {
            joinThrew = e.getClass();
        }
        return new Class<?>[] {getCause, joinThrew};
    }

    /** S05. 품절이면 "SOLD_OUT" 으로 응답하려고 했다. 결과는? (Outcome 형식) */
    public static String s05(Executor io) {
        FakeBlockingService stockApi = new FakeBlockingService("stock-api", 50)
                .failOn("p1", new OutOfStockException("p1"));
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> stockApi.call("p1"), io)
                .exceptionally(ex -> {
                    if (ex instanceof OutOfStockException) {
                        return "SOLD_OUT";
                    }
                    throw new CompletionException(ex);
                });
        return Outcome.of(stock);
    }

    /** S06. 가격 API 장애 시 로그를 남기고 기본 가격을 쓰려고 했다. 결과는? (Outcome 형식) */
    public static String s06(ThreadRecorder rec, Executor io) {
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 50)
                .failOn("p1", new ExternalApiException("price-api 503"));
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> priceApi.call("p1"), io)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        rec.mark("logged");
                    }
                });
        return Outcome.of(price);
    }

    /**
     * S07. 결제가 거절됐고, 로그를 남기는 whenComplete 액션에 버그가 있어 NullPointerException 을 던진다.
     * (a) 최종 결과(Outcome 형식)는? (b) 예외 체인 어딘가에 suppressed 로 붙은 예외의 타입은?
     */
    public static Object[] s07(Executor io) {
        FakeBlockingService paymentApi = new FakeBlockingService("payment-api", 50)
                .failOn("o1", new PaymentDeclinedException("한도 초과"));
        String auditChannel = null; // 로그 코드의 버그: 설정값이 비어 있다
        CompletableFuture<String> paid = CompletableFuture.supplyAsync(() -> paymentApi.call("o1"), io)
                .whenComplete((v, ex) -> {
                    System.out.println("[" + auditChannel.trim() + "] 결제 결과: " + v); // NullPointerException
                });
        String outcome = Outcome.of(paid);
        Throwable failure = paid.handle((v, ex) -> ex).join();
        return new Object[] {outcome, firstSuppressedInChain(failure)};
    }

    /** 원인 체인 전체를 따라가며 처음 발견한 suppressed 예외의 클래스. 없으면 Void.class */
    static Class<?> firstSuppressedInChain(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getSuppressed().length > 0) {
                return cur.getSuppressed()[0].getClass();
            }
        }
        return Void.class;
    }

    /** S08. S07 과 같은 버그가 있는 로그 코드. 이번에는 결제가 성공한다. 결과는? (Outcome 형식) */
    public static String s08(Executor io) {
        FakeBlockingService paymentApi = new FakeBlockingService("payment-api", 50);
        String auditChannel = null; // 로그 코드의 버그: 설정값이 비어 있다
        CompletableFuture<String> paid = CompletableFuture.supplyAsync(() -> paymentApi.call("o1"), io)
                .whenComplete((v, ex) -> {
                    System.out.println("[" + auditChannel.trim() + "] 결제 결과: " + v); // NullPointerException
                });
        return Outcome.of(paid);
    }

    /**
     * S09. 할인 조회가 실패한다. 복구 단계의 위치만 다른 두 파이프라인의 결과는? (Outcome 형식)
     * (a) exceptionally 를 먼저, (b) exceptionally 를 나중에
     */
    public static String[] s09(Executor io) {
        FakeBlockingService discountApi = new FakeBlockingService("discount-api", 50)
                .failOn("u1", new ExternalApiException("discount-api 503"));
        CompletableFuture<String> discountA = CompletableFuture.supplyAsync(() -> discountApi.call("u1"), io);
        CompletableFuture<String> discountB = CompletableFuture.supplyAsync(() -> discountApi.call("u1"), io);

        CompletableFuture<String> a = discountA
                .exceptionally(ex -> "0%")
                .thenApply(d -> "discount=" + d);
        CompletableFuture<String> b = discountB
                .thenApply(d -> "discount=" + d)
                .exceptionally(ex -> "no-discount");
        return new String[] {Outcome.of(a), Outcome.of(b)};
    }

    /**
     * S10. 300ms 걸리는 배송 조회에 orTimeout(100ms)을 걸었다.
     * (a) 바로 붙인 exceptionally 가 받은 예외 타입은? (b) exceptionally 가 끝난 직후 배송 조회의 inFlight() 는?
     */
    public static Object[] s10(Executor io) {
        FakeBlockingService shippingApi = new FakeBlockingService("shipping-api", 300);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CompletableFuture.supplyAsync(() -> shippingApi.call("o1"), io)
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    seen.set(ex);
                    return "unknown";
                })
                .join();
        int inFlight = shippingApi.inFlight();
        Sleep.ms(300); // 남은 호출이 끝날 때까지 기다린다 (다음 테스트에 영향이 없도록)
        return new Object[] {seen.get().getClass(), inFlight};
    }

    /**
     * S11. 300ms 걸리는 가격 조회에 completeOnTimeout("cached", 100ms) 을 걸고 화면을 그린다.
     * (a) render 는 어느 스레드에서 실행될까? (b) 실제 호출이 끝난 뒤, <b>원래 future(price)</b>의 값은?
     */
    public static String s11(ThreadRecorder rec, Executor io) {
        FakeBlockingService priceApi = new FakeBlockingService("price-api", 300);
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> priceApi.call("p1"), io);
        CompletableFuture<String> withDefault = price.completeOnTimeout("cached", 100, TimeUnit.MILLISECONDS);
        withDefault.thenApply(v -> {
            rec.mark("render");
            return "view:" + v;
        }).join();
        Sleep.ms(300); // 실제 가격 조회가 끝날 때까지 기다린다
        return price.join();
    }

    /**
     * S12. 300ms 걸리는 조회를 get(100ms) 로 기다린다.
     * (a) get 이 TimeoutException 을 던진 시점(ms)은? (b) 그 직후 f.isDone() 은?
     */
    public static boolean s12(ThreadRecorder rec, Executor io) throws InterruptedException, ExecutionException {
        FakeBlockingService api = new FakeBlockingService("api", 300);
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> api.call("k"), io);
        boolean done;
        try {
            f.get(100, TimeUnit.MILLISECONDS);
            done = true;
        } catch (TimeoutException e) {
            rec.mark("timedOut");
            done = f.isDone();
        }
        f.join();
        return done;
    }

    /**
     * S13. 200ms 걸리는 리포트 생성을 50ms 뒤에 cancel(true) 로 취소한다.
     * 실제 호출이 끝날 시간이 지난 뒤, 리포트 서비스의 interruptedCount() 는?
     */
    public static int s13(Executor io) {
        FakeBlockingService reportApi = new FakeBlockingService("report-api", 200);
        CompletableFuture<String> report = CompletableFuture.supplyAsync(() -> reportApi.call("r1"), io);
        Sleep.ms(50);
        report.cancel(true);
        Sleep.ms(300);
        return reportApi.interruptedCount();
    }

    /**
     * S14. 하류(view)를 취소했다. 상류(user)도 취소될까? 반환값: user.isCancelled()
     */
    public static boolean s14(Executor io) {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> userDb.call("u1"), io);
        CompletableFuture<String> view = user.thenApply(u -> "view:" + u);
        view.cancel(true);
        user.join();
        return user.isCancelled();
    }

    /**
     * S15. 상류(user)를 취소했다. 거기에 붙어 있던 하류(view)는?
     * (a) view.join() 이 던지는 예외의 타입, (b) view.isCancelled()
     */
    public static Object[] s15(Executor io) {
        FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
        CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> userDb.call("u1"), io);
        CompletableFuture<String> view = user.thenApply(u -> "view:" + u);
        user.cancel(true);
        Class<?> thrown = null;
        try {
            view.join();
        } catch (RuntimeException e) {
            thrown = e.getClass();
        }
        Sleep.ms(150); // 남은 호출이 끝날 때까지 기다린다
        return new Object[] {thrown, view.isCancelled()};
    }

    /**
     * S16. 같은 리포트 생성을 이번에는 ExecutorService.submit 으로 제출하고, 반환된 Future 를 cancel(true) 한다.
     * 실제 호출이 끝날 시간이 지난 뒤, 리포트 서비스의 interruptedCount() 는?
     */
    public static int s16(ExecutorService io) {
        FakeBlockingService reportApi = new FakeBlockingService("report-api", 200);
        Future<String> report = io.submit(() -> reportApi.call("r1"));
        Sleep.ms(50);
        report.cancel(true);
        Sleep.ms(300);
        return reportApi.interruptedCount();
    }
}
