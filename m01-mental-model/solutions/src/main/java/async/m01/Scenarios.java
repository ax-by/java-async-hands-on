package async.m01;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.Sleep;
import async.harness.ThreadRecorder;

/**
 * m01 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>각 시나리오의 {@code rec.mark("이름")} 이 "어느 스레드에서 실행되는지"를 {@link Predictions} 에 적는다.
 * 실행 풀 이름: io, cpu, completer, a, b (각각 이름이 "io-1" 같은 스레드를 가진 풀).
 * 테스트 메서드를 실행하는 스레드는 {@code On.caller()} 다.
 *
 * <p>{@link Gate} 는 "이 시점까지 future 가 확실히 완료되지 않았다"를 보장하는 장치다.
 * Gate 가 닫혀 있는 동안 그 future 는 미완료 상태다.
 */
public final class Scenarios {

    private Scenarios() {
    }

    /** S01. 미완료 future 에 non-async 콜백을 등록한다. */
    public static CompletableFuture<String> s01(ThreadRecorder rec, Executor io) {
        Gate gate = new Gate();
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            gate.await();
            rec.mark("supply");
            return "v";
        }, io);
        CompletableFuture<String> g = f.thenApply(v -> {
            rec.mark("map");
            return v + "!";
        });
        gate.open();
        return g;
    }

    /** S02. 이미 완료된 future 에 non-async 콜백을 등록한다. */
    public static CompletableFuture<String> s02(ThreadRecorder rec, Executor io) {
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> "v", io);
        f.join();
        return f.thenApply(v -> {
            rec.mark("map");
            return v + "!";
        });
    }

    /** S03. executor 를 지정하지 않은 Async 콜백. */
    public static CompletableFuture<String> s03(ThreadRecorder rec, Executor io) {
        Gate gate = new Gate();
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            gate.await();
            return "v";
        }, io);
        CompletableFuture<String> g = f.thenApplyAsync(v -> {
            rec.mark("map");
            return v + "!";
        });
        gate.open();
        return g;
    }

    /** S04. executor 를 지정한 Async 콜백. 이번엔 future 가 이미 완료된 뒤에 등록한다. */
    public static CompletableFuture<String> s04(ThreadRecorder rec, Executor io, Executor cpu) {
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> "v", io);
        f.join();
        return f.thenApplyAsync(v -> {
            rec.mark("map");
            return v + "!";
        }, cpu);
    }

    /** S05. 직접 만든 future 를 다른 풀(completer)이 완료시킨다. */
    public static CompletableFuture<String> s05(ThreadRecorder rec, Executor completer) {
        CompletableFuture<String> f = new CompletableFuture<>();
        CompletableFuture<String> g = f.thenApply(v -> {
            rec.mark("map");
            return v + "!";
        });
        completer.execute(() -> f.complete("v"));
        return g;
    }

    /** S06. 직접 만든 future 를 caller 가 직접 완료시킨다. */
    public static CompletableFuture<String> s06(ThreadRecorder rec) {
        CompletableFuture<String> f = new CompletableFuture<>();
        CompletableFuture<String> g = f.thenApply(v -> {
            rec.mark("map");
            return v + "!";
        });
        f.complete("v");
        return g;
    }

    /** S07. thenCombine: a 가 먼저 끝나고, 그다음 b 가 끝난다. combine 함수는 어디서 실행될까? */
    public static CompletableFuture<String> s07(ThreadRecorder rec, Executor a, Executor b) {
        Gate gateA = new Gate();
        Gate gateB = new Gate();
        CompletableFuture<String> fa = CompletableFuture.supplyAsync(() -> {
            gateA.await();
            return "A";
        }, a);
        CompletableFuture<String> fb = CompletableFuture.supplyAsync(() -> {
            gateB.await();
            return "B";
        }, b);
        CompletableFuture<String> combined = fa.thenCombine(fb, (x, y) -> {
            rec.mark("combine");
            return x + y;
        });
        gateA.open();
        fa.join();
        gateB.open();
        return combined;
    }

    /** S08. anyOf: a 가 먼저 끝난다. anyOf 결과에 붙인 콜백은 어디서 실행될까? */
    public static CompletableFuture<Object> s08(ThreadRecorder rec, Executor a, Executor b) {
        Gate gateA = new Gate();
        Gate gateB = new Gate();
        CompletableFuture<String> fa = CompletableFuture.supplyAsync(() -> {
            gateA.await();
            return "A";
        }, a);
        CompletableFuture<String> fb = CompletableFuture.supplyAsync(() -> {
            gateB.await();
            return "B";
        }, b);
        CompletableFuture<Object> first = CompletableFuture.anyOf(fa, fb).thenApply(v -> {
            rec.mark("first");
            return v;
        });
        gateA.open();
        first.join();
        gateB.open();
        return first;
    }

    /** S09. 중간에 Async 단계가 끼어 있는 체인. 마지막 non-async 콜백은 어디서 실행될까? */
    public static CompletableFuture<String> s09(ThreadRecorder rec, Executor io, Executor cpu) {
        Gate gate = new Gate();
        CompletableFuture<String> g = CompletableFuture.supplyAsync(() -> {
                    gate.await();
                    rec.mark("supply");
                    return "v";
                }, io)
                .thenApplyAsync(v -> {
                    rec.mark("parse");
                    return v + "1";
                }, cpu)
                .thenApply(v -> {
                    rec.mark("after");
                    return v + "2";
                });
        gate.open();
        return g;
    }

    /** S10. 영원히 완료되지 않는 future 에 타임아웃을 건다. 후속 콜백은 어디서 실행될까? */
    public static CompletableFuture<String> s10(ThreadRecorder rec) {
        CompletableFuture<String> never = new CompletableFuture<>();
        return never.orTimeout(50, TimeUnit.MILLISECONDS)
                .whenComplete((v, ex) -> rec.mark("afterTimeout"))
                .exceptionally(ex -> "timeout");
    }

    /** S11. 받은 Runnable 을 그 자리에서 바로 실행하는 executor. */
    public static CompletableFuture<Void> s11(ThreadRecorder rec) {
        Executor direct = Runnable::run;
        return CompletableFuture.runAsync(() -> rec.mark("task"), direct);
    }

    /**
     * S12. 200ms 걸리는 blocking 호출을 supplyAsync 로 넘긴다.
     * "returned": supplyAsync 호출이 반환된 시점(시작 후 ms), "joined": join 이 반환된 시점(시작 후 ms).
     */
    public static String s12(ThreadRecorder rec, Executor io) {
        FakeBlockingService db = new FakeBlockingService("db", 200);
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> db.call("k"), io);
        rec.mark("returned");
        String v = f.join();
        rec.mark("joined");
        return v;
    }

    /** S13. completedFuture 에 콜백을 붙인다. */
    public static CompletableFuture<String> s13(ThreadRecorder rec) {
        return CompletableFuture.completedFuture("v").thenApply(v -> {
            rec.mark("map");
            return v + "!";
        });
    }

    /**
     * S14. thenCompose. 바깥 future 는 io 가, 안쪽 future 는 cpu 가 완료시킨다.
     * compose 함수(compose-fn), 안쪽 작업(inner), 그 뒤의 non-async 콜백(after)은 각각 어디서 실행될까?
     */
    public static CompletableFuture<String> s14(ThreadRecorder rec, Executor io, Executor cpu) {
        Gate outerGate = new Gate();
        Gate innerGate = new Gate();
        CompletableFuture<String> g = CompletableFuture.supplyAsync(() -> {
                    outerGate.await();
                    return "v";
                }, io)
                .thenCompose(v -> {
                    rec.mark("compose-fn");
                    return CompletableFuture.supplyAsync(() -> {
                        innerGate.await();
                        rec.mark("inner");
                        return v + "!";
                    }, cpu);
                })
                .thenApply(v -> {
                    rec.mark("after");
                    return v + "?";
                });
        outerGate.open();
        while (!rec.has("compose-fn")) {
            Thread.onSpinWait();
        }
        Sleep.ms(100); // (결정성 장치) thenCompose 가 안쪽 future 에 연결될 시간을 준다
        innerGate.open();
        return g;
    }

    /**
     * S15. 200ms blocking 호출을 non-async 콜백 안에서 한다. 콜백을 "등록하는 데" 걸린 시간을 예측하라.
     * a) 미완료 future 에 등록 → "registeredA" 시점
     * b) 완료된 future 에 등록 → "registeredB" 시점 (시작 후 ms. a 가 끝난 뒤 b 를 시작하므로 b 는 b 의 시작점부터 잰다)
     */
    public static long[] s15(Executor io) {
        FakeBlockingService db = new FakeBlockingService("db", 200);
        Gate gate = new Gate();

        CompletableFuture<String> incomplete = CompletableFuture.supplyAsync(() -> {
            gate.await();
            return "a";
        }, io);
        long startA = System.nanoTime();
        CompletableFuture<String> a = incomplete.thenApply(db::call);
        long registeredA = (System.nanoTime() - startA) / 1_000_000;
        gate.open();
        a.join();

        CompletableFuture<String> done = CompletableFuture.completedFuture("b");
        long startB = System.nanoTime();
        CompletableFuture<String> b = done.thenApply(db::call);
        long registeredB = (System.nanoTime() - startB) / 1_000_000;
        b.join();

        return new long[] {registeredA, registeredB};
    }
}
