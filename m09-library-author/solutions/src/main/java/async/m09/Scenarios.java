package async.m09;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import async.m09.minikv.FakeTransport;
import async.m09.minikv.MiniKvException;

/**
 * m09 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>이 파일은 라이브러리 코드와 같은 {@code --release 8} 로 컴파일되므로 harness 를 쓰지 않는다.
 * 대신 {@code mark.accept("이름")} 이 "이 줄이 실행된 스레드"를 기록한다(테스트가 ThreadRecorder 를 넘긴다).
 *
 * <p>스레드 이름
 * <ul>
 *   <li>{@code nonblocking-minikv-io-1}: {@link FakeTransport} 의 I/O 스레드 (On.pool("nonblocking-minikv-io"))</li>
 *   <li>{@code app-1}: 사용자가 넘긴 completion executor (On.pool("app"))</li>
 *   <li>{@code minikv-timer-1}: 타임아웃 타이머 (On.pool("minikv-timer"))</li>
 *   <li>테스트 메서드 스레드: On.caller()</li>
 * </ul>
 * FakeTransport 의 기본 응답 지연은 200ms 이고, 응답 값은 {@code "val:" + key} 다.
 * 콜백은 모두 요청 직후(응답 200ms 전)에 등록되므로 "미완료 상태에서 등록"된 것으로 보면 된다.
 * (Java 9+ API 가 필요한 S09 는 src/test/java/async/m09/ModernScenarios.java 에 있다.)
 */
public final class Scenarios {

    private Scenarios() {
    }

    // ---------------------------------------------------------------- 공통 부품

    /** 콜백 API → CompletableFuture. I/O 스레드가 완료시킨다(m01 E3 과 같은 어댑터). */
    static CompletableFuture<String> send(FakeTransport transport, String key) {
        CompletableFuture<String> raw = new CompletableFuture<>();
        transport.send(key, (value, error) -> {
            if (error != null) {
                raw.completeExceptionally(error);
            } else {
                raw.complete(value);
            }
        });
        return raw;
    }

    /** 완료 정책 1: raw 를 완료시킨 스레드가 그대로 사용자 future 를 완료시킨다 (Netty, Lettuce 의 기본). */
    static <T> CompletableFuture<T> completeOnSameThread(CompletableFuture<T> raw) {
        CompletableFuture<T> out = new CompletableFuture<>();
        raw.whenComplete((v, ex) -> {
            if (ex != null) {
                out.completeExceptionally(ex);
            } else {
                out.complete(v);
            }
        });
        return out;
    }

    /** 완료 정책 2: 완료 작업을 사용자가 준 executor 로 넘긴다 (AWS SDK v2 의 FUTURE_COMPLETION_EXECUTOR 설계). */
    static <T> CompletableFuture<T> completeOn(CompletableFuture<T> raw, Executor completionExecutor) {
        CompletableFuture<T> out = new CompletableFuture<>();
        raw.whenComplete((v, ex) -> completionExecutor.execute(() -> {
            if (ex != null) {
                out.completeExceptionally(ex);
            } else {
                out.complete(v);
            }
        }));
        return out;
    }

    // ---------------------------------------------------------------- 완료 스레드 정책

    /** S01. 정책 1(같은 스레드에서 완료). 사용자가 응답 전에 붙인 콜백("user")은 어디서 실행될까? */
    public static String s01(FakeTransport transport, Consumer<String> mark) {
        CompletionStage<String> stage = completeOnSameThread(send(transport, "k"));
        return stage.thenApply(v -> {
            mark.accept("user");
            return v;
        }).toCompletableFuture().join();
    }

    /**
     * S02. 정책 2(completion executor = app). 응답 전에 붙인 콜백("user")과,
     * 완료를 기다린 뒤에 붙인 콜백("late")은 각각 어디서 실행될까?
     */
    public static String s02(FakeTransport transport, Executor app, Consumer<String> mark) {
        CompletableFuture<String> stage = completeOn(send(transport, "k"), app);
        CompletableFuture<String> user = stage.thenApply(v -> {
            mark.accept("user");
            return v;
        });
        user.join();
        return stage.thenApply(v -> {
            mark.accept("late");
            return v;
        }).join();
    }

    /**
     * S03. 정책 1 + Java 8 식 타임아웃. "slow" 키는 응답에 1000ms 가 걸리고 타임아웃은 100ms 다.
     * 사용자 콜백("user")은 어디서 실행되고, 그 콜백이 받는 예외의 단순 클래스 이름은 무엇일까?
     */
    public static String s03(FakeTransport transport, ScheduledExecutorService timer, Consumer<String> mark) {
        CompletableFuture<String> raw = send(transport, "slow");
        timer.schedule(() -> raw.completeExceptionally(new TimeoutException()), 100, TimeUnit.MILLISECONDS);
        CompletableFuture<String> stage = completeOnSameThread(raw);
        return stage.handle((v, ex) -> {
            mark.accept("user");
            return ex.getClass().getSimpleName();
        }).join();
    }

    // ---------------------------------------------------------------- 공개 API 형태: 무엇이 보호되는가

    /**
     * S04. 라이브러리가 진행 중인 요청을 키별로 캐시하고 <b>그 내부 future 자체</b>를 반환한다.
     * 호출자 A 가 실수로 complete("hacked") 한 뒤, 호출자 B 가 받는 값은?
     */
    public static String s04(FakeTransport transport) {
        Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();
        Function<String, CompletableFuture<String>> getAsync = key -> cache.computeIfAbsent(key, k -> send(transport, k));

        CompletableFuture<String> callerA = getAsync.apply("k");
        CompletableFuture<String> callerB = getAsync.apply("k");
        callerA.complete("hacked"); // 예: 테스트용 기본값을 넣으려던 코드, 또는 "빨리 끝내기" 해킹
        return callerB.join();
    }

    /** S05. S04 와 같은 라이브러리. 호출자 A 가 기다리기 싫어서 cancel 한다. 호출자 B 의 join 결과는? (값 또는 예외 단순 이름) */
    public static String s05(FakeTransport transport) {
        Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();
        Function<String, CompletableFuture<String>> getAsync = key -> cache.computeIfAbsent(key, k -> send(transport, k));

        CompletableFuture<String> callerA = getAsync.apply("k");
        CompletableFuture<String> callerB = getAsync.apply("k");
        callerA.cancel(true);
        try {
            return callerB.join();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * S06. Java 8 식 보호: 반환 타입을 CompletionStage 로 바꾸고, <b>호출마다</b> {@code thenApply(x -> x)} 사본을 준다.
     * 호출자 A 가 {@code toCompletableFuture().complete("hacked")} 한다.
     * [0] 호출자 B 가 받는 값은? [1] {@code stageA.toCompletableFuture() == stageA} 인가?
     */
    public static Object[] s06(FakeTransport transport) {
        Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();
        Function<String, CompletionStage<String>> getAsync =
                key -> cache.computeIfAbsent(key, k -> send(transport, k)).thenApply(x -> x);

        CompletionStage<String> stageA = getAsync.apply("k");
        CompletionStage<String> stageB = getAsync.apply("k");
        boolean same = stageA.toCompletableFuture() == stageA;
        stageA.toCompletableFuture().complete("hacked");
        return new Object[] {stageB.toCompletableFuture().join(), same};
    }

    /**
     * S07. S06 과 비슷하지만 <b>사본을 캐시</b>한다(사본을 한 번만 만들어 모두에게 준다).
     * 호출자 A 가 complete("hacked") 한 뒤, 호출자 B 가 받는 값은?
     */
    public static String s07(FakeTransport transport) {
        Map<String, CompletionStage<String>> cache = new ConcurrentHashMap<>();
        Function<String, CompletionStage<String>> getAsync =
                key -> cache.computeIfAbsent(key, k -> send(transport, k).thenApply(x -> x));

        CompletionStage<String> stageA = getAsync.apply("k");
        CompletionStage<String> stageB = getAsync.apply("k");
        stageA.toCompletableFuture().complete("hacked");
        return stageB.toCompletableFuture().join();
    }

    /**
     * S08. 내부 future 를 {@code completeExceptionally(new MiniKvException(..))} 로 실패시킨다.
     * 사용자가 반환된 stage 에 <b>바로</b> 붙인 exceptionally 가 받는 예외의 단순 클래스 이름은?
     * [0] {@code thenApply(x -> x)} 사본을 반환한 경우, [1] whenComplete 로 중계한 새 future 를 반환한 경우
     */
    public static String[] s08() {
        CompletableFuture<String> internal = new CompletableFuture<>();

        CompletionStage<String> viaThenApply = internal.thenApply(x -> x);
        CompletableFuture<String> viaRelay = completeOnSameThread(internal);

        internal.completeExceptionally(new MiniKvException("NOT_FOUND"));

        String a = viaThenApply.exceptionally(ex -> ex.getClass().getSimpleName()).toCompletableFuture().join();
        String b = viaRelay.exceptionally(ex -> ex.getClass().getSimpleName()).join();
        return new String[] {a, b};
    }

    // ---------------------------------------------------------------- sync / async 쌍

    /** 가짜 async: 응답을 기다린 뒤 completedFuture 로 감싼다. */
    static CompletableFuture<String> fakeGetAsync(FakeTransport transport, String key) {
        return CompletableFuture.completedFuture(send(transport, key).join());
    }

    /**
     * S10. 가짜 async 를 호출한다(지연 200ms). "returned": fakeGetAsync 가 반환된 시점(ms),
     * "user": 반환된 future 에 붙인 콜백이 실행된 스레드.
     */
    public static String s10(FakeTransport transport, Consumer<String> mark) {
        CompletableFuture<String> f = fakeGetAsync(transport, "k");
        mark.accept("returned");
        return f.thenApply(v -> {
            mark.accept("user");
            return v;
        }).join();
    }

    /** "sync = async 결과를 기다린다" 로 만든 sync API. async 쪽은 정책 2(completion executor)를 쓴다. */
    static String syncGet(FakeTransport transport, Executor completionExecutor, String key) throws Exception {
        return completeOn(send(transport, key), completionExecutor).get(500, TimeUnit.MILLISECONDS);
    }

    /**
     * S11. completion executor(app, <b>스레드 1개</b>) 위에서 실행되는 사용자 코드(예: 다른 응답의 콜백)가
     * 위의 sync API({@code syncGet})를 호출한다. 서버는 200ms 만에 응답한다. 결과는? ("value=..." 또는 예외 단순 이름)
     */
    public static String s11(FakeTransport transport, Executor app) {
        CompletableFuture<String> outcome = new CompletableFuture<>();
        app.execute(() -> {
            try {
                outcome.complete("value=" + syncGet(transport, app, "k"));
            } catch (Exception e) {
                outcome.complete(e.getClass().getSimpleName());
            }
        });
        return outcome.join();
    }

    // ---------------------------------------------------------------- 타임아웃 정리

    /**
     * S12. 요청 100개에 각각 10초 타이머를 걸고, 요청이 먼저 끝나면 타이머를 cancel 한다.
     * 스케줄러는 {@code new ScheduledThreadPoolExecutor(1)} (기본 설정)이다. 끝난 뒤 스케줄러 큐에 남은 작업 수는?
     */
    public static int s12(ScheduledThreadPoolExecutor timer) {
        for (int i = 0; i < 100; i++) {
            CompletableFuture<String> request = new CompletableFuture<>();
            ScheduledFuture<?> timeout = timer.schedule(
                    () -> request.completeExceptionally(new TimeoutException()), 10, TimeUnit.SECONDS);
            request.whenComplete((v, ex) -> timeout.cancel(false));
            request.complete("v"); // 응답이 먼저 왔다
        }
        return timer.getQueue().size();
    }

    // ---------------------------------------------------------------- 컴파일 대상

    /**
     * S13. 아래 코드를 JDK 25 의 javac 로 컴파일한다. 컴파일에 성공할까? (true/false)
     * [a] {@code javac --release 8}, [b] {@code javac -source 8 -target 8}
     */
    public static final String S13_SNIPPET = String.join("\n",
            "import java.util.concurrent.*;",
            "class Snippet {",
            "    CompletableFuture<String> m(CompletableFuture<String> f) {",
            "        return f.orTimeout(1, TimeUnit.SECONDS);",
            "    }",
            "}");
}
