package async.m10.kafka;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.serialization.StringSerializer;

import async.harness.Sleep;
import async.harness.ThreadRecorder;

/**
 * 2장 Kafka clients 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽기 가이드: {@code guides/02-kafka.md}
 *
 * <p>브로커가 없다. K1~K4 는 Kafka 가 내부에서 쓰는 {@code KafkaFutureImpl} 을 직접 만들어 "AdminClient 결과"를 흉내 낸다.
 * K5, K6 은 <b>아무도 듣고 있지 않은 로컬 포트</b>를 bootstrap 으로 준 진짜 AdminClient / KafkaProducer 다(네트워크 불필요).
 */
public final class KafkaScenarios {

    private KafkaScenarios() {
    }

    /**
     * K1. 원본 KafkaFuture 가 {@code IllegalStateException} 으로 실패한다.
     * {@code thenApply} 로 만든 KafkaFuture 에 붙인 {@code whenComplete} 가 받는 예외의 단순 클래스 이름은?
     */
    public static String k1() {
        KafkaFutureImpl<String> topicDescription = new KafkaFutureImpl<>();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        topicDescription.thenApply(String::length).whenComplete((v, ex) -> seen.set(ex));
        topicDescription.completeExceptionally(new IllegalStateException("UNKNOWN_TOPIC"));
        return seen.get().getClass().getSimpleName();
    }

    /**
     * K2. {@code toCompletionStage().toCompletableFuture()} 로 꺼낸 CompletableFuture 에 {@code complete("x")} 를 호출한다.
     * 결과는? ("completed" 또는 던져진 예외의 단순 클래스 이름)
     */
    public static String k2() {
        KafkaFutureImpl<String> result = new KafkaFutureImpl<>();
        CompletableFuture<String> cf = result.toCompletionStage().toCompletableFuture();
        try {
            cf.complete("x");
            return "completed";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * K3. 끝나지 않는 KafkaFuture 를 CompletionStage 로 바꾸고 {@code thenApply} 한 뒤,
     * {@code toCompletableFuture().orTimeout(100ms)} 를 걸었다. 300ms 뒤 그 future 는 완료(isDone)되어 있는가?
     */
    public static boolean k3() {
        KafkaFutureImpl<String> neverAnswers = new KafkaFutureImpl<>();
        CompletionStage<Integer> stage = neverAnswers.toCompletionStage().thenApply(String::length);
        CompletableFuture<Integer> withTimeout = stage.toCompletableFuture().orTimeout(100, TimeUnit.MILLISECONDS);
        Sleep.ms(300);
        return withTimeout.isDone();
    }

    /**
     * K4. {@code toCompletionStage().toCompletableFuture()} 로 꺼낸 future 를 {@code cancel(true)} 했다.
     * 원본 KafkaFuture 는 취소되었는가? (m01~m03 의 R10 "취소는 상류로 전파되지 않는다"와 비교하라)
     */
    public static boolean k4() {
        KafkaFutureImpl<String> original = new KafkaFutureImpl<>();
        original.toCompletionStage().toCompletableFuture().cancel(true);
        return original.isCancelled();
    }

    /**
     * K5. 브로커 없는 AdminClient. {@code describeCluster().clusterId()} 는 default.api.timeout.ms(500ms) 뒤 실패한다.
     * 그 KafkaFuture 에 붙인 {@code whenComplete}("callback")는 어느 스레드에서 실행되는가?
     */
    public static void k5(ThreadRecorder rec) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + unusedLocalPort());
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "500");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "400");
        try (Admin admin = Admin.create(props)) {
            KafkaFuture<String> clusterId = admin.describeCluster().clusterId();
            CompletableFuture<Void> done = new CompletableFuture<>();
            clusterId.whenComplete((id, ex) -> {
                rec.mark("callback");
                done.complete(null);
            });
            done.get(5, TimeUnit.SECONDS);
        }
    }

    /** K6 결과: send() 가 반환되기까지 걸린 시간(ms). */
    public record SendResult(long sendReturnedMs) {
    }

    /**
     * K6. 브로커 없는 KafkaProducer, {@code max.block.ms=300}. {@code send(record, callback)} 은
     * 얼마 만에 반환되는가(sendReturnedMs)? 콜백("callback")은 어느 스레드에서 실행되는가?
     */
    public static SendResult k6(ThreadRecorder rec) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + unusedLocalPort());
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "300");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long start = System.nanoTime();
            producer.send(new ProducerRecord<>("order-events", "order-1", "CREATED"),
                    (metadata, ex) -> rec.mark("callback"));
            long sendReturnedMs = (System.nanoTime() - start) / 1_000_000;
            producer.close(Duration.ofMillis(200));
            return new SendResult(sendReturnedMs);
        }
    }

    /** 아무도 듣고 있지 않은 로컬 포트. 연결하면 즉시 거절된다. */
    public static int unusedLocalPort() {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
