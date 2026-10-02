package async.m01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.harness.Timed;

class ExercisesTest {

    @Test
    void e1_mapOn_runsOnExecutor_whenFutureAlreadyCompleted() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (NamedPool cpu = Pools.fixed("cpu", 1)) {
            String v = Exercises.mapOn(CompletableFuture.completedFuture("v"), s -> {
                rec.mark("fn");
                return s + "!";
            }, cpu).get(1, TimeUnit.SECONDS);
            assertThat(v).isEqualTo("v!");
        }
        assertThat(rec.on("fn")).as("이미 완료된 future 라도 executor 에서 실행돼야 한다").isEqualTo(On.pool("cpu"));
    }

    @Test
    void e1_mapOn_runsOnExecutor_whenCompletedByAnotherThread() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (NamedPool cpu = Pools.fixed("cpu", 1); NamedPool io = Pools.fixed("io", 1)) {
            Gate gate = new Gate();
            CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                gate.await();
                return "v";
            }, io);
            CompletableFuture<String> mapped = Exercises.mapOn(f, s -> {
                rec.mark("fn");
                return s + "!";
            }, cpu);
            gate.open();
            assertThat(mapped.get(1, TimeUnit.SECONDS)).isEqualTo("v!");
        }
        assertThat(rec.on("fn")).as("io 가 완료시켜도 executor 에서 실행돼야 한다").isEqualTo(On.pool("cpu"));
    }

    @Test
    void e2_callBoth_returnsImmediately_andRunsInParallel() throws Exception {
        FakeBlockingService a = new FakeBlockingService("a", 200);
        FakeBlockingService b = new FakeBlockingService("b", 200);
        try (NamedPool io = Pools.fixed("io", 4)) {
            Timed<CompletableFuture<String>> call = Timed.of(() -> Exercises.callBoth(a, b, "k", io));
            assertThat(call.millis()).as("호출 스레드는 막히면 안 된다").isLessThan(50);
            Timed<String> total = Timed.of(() -> call.value().join());
            assertThat(total.value()).isEqualTo("a:k|b:k");
            assertThat(call.millis() + total.millis()).as("총 소요시간 ≈ max(200, 200)").isLessThan(320);
        }
        assertThat(a.callerThreads()).allMatch(t -> t.startsWith("io-"));
        assertThat(b.callerThreads()).allMatch(t -> t.startsWith("io-"));
    }

    @Test
    void e3_fromCallback_success_and_threadOfCallbacks() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (NamedPool apiThread = Pools.single("api")) {
            Exercises.CallbackApi api = (key, cb) -> apiThread.execute(() -> {
                cb.accept("value-" + key, null);
            });
            Gate registered = new Gate();
            CompletableFuture<String> f = Exercises.fromCallback(
                    (key, cb) -> api.fetch(key, (v, e) -> {
                        registered.await();
                        cb.accept(v, e);
                    }), "k");
            CompletableFuture<String> after = f.thenApply(v -> {
                rec.mark("after");
                return v;
            });
            registered.open();
            assertThat(after.get(1, TimeUnit.SECONDS)).isEqualTo("value-k");
        }
        assertThat(rec.on("after")).as("콜백 API 의 스레드가 future 를 완료시킨다 (R1)").isEqualTo(On.pool("api"));
    }

    @Test
    void e3_fromCallback_failure() {
        IllegalStateException boom = new IllegalStateException("boom");
        CompletableFuture<String> f = Exercises.fromCallback((key, cb) -> cb.accept(null, boom), "k");
        assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(boom);
    }
}
