package async.m10;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;

import async.harness.Timed;
import async.m10.kafka.KafkaFutures;

/** E3, E4 채점. */
@Timeout(20)
class KafkaExercisesTest {

    /** Kafka 2.x 클라이언트 흉내: toCompletionStage() 가 없다. */
    static final class Kafka2xFuture<T> extends KafkaFutureImpl<T> {
        @Override
        public CompletionStage<T> toCompletionStage() {
            throw new UnsupportedOperationException("Kafka 2.x 에는 toCompletionStage() 가 없다 (3.0+). whenComplete 로 브리지하라");
        }

        void succeed(T v) {
            complete(v);
        }

        void fail(Throwable t) {
            completeExceptionally(t);
        }
    }

    @Test
    void e3_success_andReturnsPlainCompletableFuture() throws Exception {
        Kafka2xFuture<String> source = new Kafka2xFuture<>();
        CompletableFuture<String> cf = KafkaFutures.toCompletableFuture(source);
        assertThat(cf.getClass()).as("반환 타입은 평범한 CompletableFuture 여야 한다 (서브클래스 금지)").isEqualTo(CompletableFuture.class);
        source.succeed("cluster-1");
        assertThat(cf.get(1, TimeUnit.SECONDS)).isEqualTo("cluster-1");
    }

    @Test
    void e3_failure_isDeliveredUnwrapped_alsoFromDependentKafkaFuture() throws Exception {
        Kafka2xFuture<String> source = new Kafka2xFuture<>();
        KafkaFuture<Integer> dependent = source.thenApply(String::length);
        IllegalStateException boom = new IllegalStateException("UNKNOWN_TOPIC_OR_PARTITION");

        AtomicReference<Throwable> seenDirect = new AtomicReference<>();
        AtomicReference<Throwable> seenDependent = new AtomicReference<>();
        KafkaFutures.toCompletableFuture(source).whenComplete((v, ex) -> seenDirect.set(ex));
        KafkaFutures.toCompletableFuture(dependent).whenComplete((v, ex) -> seenDependent.set(ex));
        source.fail(boom);

        assertThat(seenDirect.get()).as("원본 실패는 원인 예외 그대로 전달돼야 한다 (R8)").isSameAs(boom);
        assertThat(seenDependent.get())
                .as("thenApply 로 만든 KafkaFuture 가 실패해도 원인 예외 그대로 전달돼야 한다 (R8, O3)")
                .isSameAs(boom);
    }

    @Test
    void e3_userCanApplyOrTimeout_andComplete() throws Exception {
        Kafka2xFuture<String> neverAnswers = new Kafka2xFuture<>();
        CompletableFuture<String> cf = KafkaFutures.toCompletableFuture(neverAnswers).orTimeout(100, TimeUnit.MILLISECONDS);
        Throwable error = cf.handle((v, ex) -> ex).get(1, TimeUnit.SECONDS);
        assertThat(Threads.unwrap(error)).as("반환된 future 에 건 orTimeout 은 발화해야 한다").isInstanceOf(TimeoutException.class);

        CompletableFuture<String> other = KafkaFutures.toCompletableFuture(new Kafka2xFuture<>());
        assertThat(other.complete("fallback")).as("사용자 코드가 complete 할 수 있어야 한다").isTrue();
    }

    @Test
    void e3_cancellingTheBridge_cancelsTheKafkaFuture() {
        Kafka2xFuture<String> source = new Kafka2xFuture<>();
        CompletableFuture<String> cf = KafkaFutures.toCompletableFuture(source);
        cf.cancel(true);
        assertThat(source.isCancelled()).as("반환된 future 를 cancel 하면 원본 KafkaFuture 도 취소돼야 한다 (R10 의 '상류로 전파되지 않는다'를 직접 연결)").isTrue();
    }

    @Test
    void e4_allSucceed_inInputOrder_withoutBlocking() throws Exception {
        Kafka2xFuture<String> orders = new Kafka2xFuture<>();
        Kafka2xFuture<String> payments = new Kafka2xFuture<>();
        Kafka2xFuture<String> shipments = new Kafka2xFuture<>();
        Map<String, KafkaFuture<String>> input = new LinkedHashMap<>();
        input.put("orders", orders);
        input.put("payments", payments);
        input.put("shipments", shipments);

        Timed<CompletableFuture<Map<String, String>>> call = Timed.of(() -> KafkaFutures.allOf(input));
        assertThat(call.millis()).as("allOf 는 즉시 반환해야 한다 (R3, R5)").isLessThan(50);
        assertThat(call.value()).isNotDone();

        shipments.succeed("3 partitions");
        orders.succeed("12 partitions");
        payments.succeed("6 partitions");
        Map<String, String> result = call.value().get(1, TimeUnit.SECONDS);
        assertThat(result.keySet()).as("입력 맵의 순서를 유지해야 한다").containsExactly("orders", "payments", "shipments");
        assertThat(result).containsEntry("orders", "12 partitions").containsEntry("shipments", "3 partitions");
    }

    @Test
    void e4_failsFast_withOriginalCause() throws Exception {
        Kafka2xFuture<String> ok = new Kafka2xFuture<>(); // 끝나지 않는다
        Kafka2xFuture<String> bad = new Kafka2xFuture<>();
        Map<String, KafkaFuture<String>> input = new LinkedHashMap<>();
        input.put("orders", ok);
        input.put("payments", bad);
        CompletableFuture<Map<String, String>> all = KafkaFutures.allOf(input);
        IllegalStateException boom = new IllegalStateException("TOPIC_AUTHORIZATION_FAILED");
        bad.fail(boom);
        Throwable error = all.handle((v, ex) -> ex).get(1, TimeUnit.SECONDS);
        assertThat(Threads.unwrap(error)).as("하나가 실패하면 나머지를 기다리지 않고 그 원인으로 즉시 실패해야 한다").isSameAs(boom);
    }

    @Test
    void e4_emptyMap() throws Exception {
        assertThat(KafkaFutures.allOf(Map.<String, KafkaFuture<String>>of()).get(1, TimeUnit.SECONDS)).isEmpty();
    }
}
