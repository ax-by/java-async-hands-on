package async.m09.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.m09.minikv.MiniKvException;

/** E1 채점: Stages.copyOf (L1 공개 API 보호, R8 예외 감싸기). */
@Timeout(5)
class StagesTest {

    @Test
    void e1_returnsNewFuturePerCall() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> a = Stages.copyOf(source);
        CompletableFuture<String> b = Stages.copyOf(source);
        assertThat(a).as("[L1] 사본은 source 와 다른 객체여야 한다. source 를 그대로 반환하면 호출자가 내부 상태를 오염시킨다")
                .isNotSameAs(source);
        assertThat(a).as("[L1] 호출마다 새 사본이어야 한다. 사본 하나를 공유하면 보호가 되지 않는다")
                .isNotSameAs(b);
    }

    @Test
    void e1_callerCompletingCopy_doesNotCorruptSourceOrOtherCallers() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> a = Stages.copyOf(source);
        CompletableFuture<String> b = Stages.copyOf(source);

        a.complete("hacked");
        source.complete("v");

        assertThat(source.get(1, TimeUnit.SECONDS)).as("[L1] 호출자 A 의 complete 가 source 를 바꾸면 안 된다").isEqualTo("v");
        assertThat(b.get(1, TimeUnit.SECONDS)).as("[L1] 호출자 A 의 complete 가 호출자 B 의 결과를 바꾸면 안 된다").isEqualTo("v");
    }

    @Test
    void e1_callerCancellingCopy_doesNotCancelSource() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> a = Stages.copyOf(source);
        CompletableFuture<String> b = Stages.copyOf(source);

        a.cancel(true);
        source.complete("v");

        assertThat(source.isCancelled()).as("[L1, R10] 사본의 cancel 이 source 를 취소하면 안 된다").isFalse();
        assertThat(b.get(1, TimeUnit.SECONDS)).as("[L1] 다른 호출자는 정상 값을 받아야 한다").isEqualTo("v");
    }

    @Test
    void e1_obtrudeOnCopy_doesNotTouchSource() {
        CompletableFuture<String> source = CompletableFuture.completedFuture("v");
        CompletableFuture<String> a = Stages.copyOf(source);
        a.obtrudeValue("evil");
        assertThat(source.join()).as("[L1] 이미 완료된 뒤에도 obtrudeValue 는 값을 덮어쓴다. source 는 무사해야 한다").isEqualTo("v");
        assertThat(Stages.copyOf(source).join()).isEqualTo("v");
    }

    @Test
    void e1_failureIsDeliveredUnwrapped() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> copy = Stages.copyOf(source);
        MiniKvException failure = new MiniKvException("NOT_FOUND");

        CompletableFuture<Throwable> seen = copy.handle((v, ex) -> ex);
        source.completeExceptionally(failure);

        assertThat(seen.join())
                .as("[R8, L6] 사본에 바로 붙인 handle 은 원래 예외를 받아야 한다(의존 단계를 거치면 감싸진다)")
                .isSameAs(failure);
    }

    @Test
    void e1_failureFromWrappedSourceIsUnwrapped() {
        MiniKvException failure = new MiniKvException("NOT_FOUND");
        CompletableFuture<String> upstream = new CompletableFuture<>();
        CompletableFuture<String> source = upstream.thenApply(v -> {
            throw failure;
        });
        CompletableFuture<Throwable> seen = Stages.copyOf(source).handle((v, ex) -> ex);
        upstream.complete("v");

        assertThat(source.handle((v, ex) -> ex).join()).isInstanceOf(CompletionException.class);
        assertThat(seen.join())
                .as("[R8] source 가 CompletionException(원인)으로 실패해도 사본은 원인으로 실패해야 한다 (unwrap)")
                .isSameAs(failure);
    }

    @Test
    void e1_copyCompletesOnTheThreadThatCompletesSource_noExtraHop() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> after = Stages.copyOf(source).thenApply(v -> {
            rec.mark("after");
            return v;
        });
        try (NamedPool completer = Pools.single("completer")) {
            completer.execute(() -> source.complete("v"));
            assertThat(after.get(1, TimeUnit.SECONDS)).isEqualTo("v");
        }
        assertThat(rec.on("after"))
                .as("[R1, #19] copyOf 는 스레드를 바꾸지 않는다(xxxAsync 로 commonPool 에 넘기면 안 된다). 실제: " + rec.threadName("after"))
                .isEqualTo(On.pool("completer"));
    }

    @Test
    void e1_alreadyCompletedSource_copyIsCompletedImmediately() {
        CompletableFuture<String> copy = Stages.copyOf(CompletableFuture.completedFuture("v"));
        assertThat(copy.isDone()).as("[R1] 이미 완료된 source 의 사본은 즉시 완료돼야 한다").isTrue();
        assertThat(copy.join()).isEqualTo("v");
    }
}
