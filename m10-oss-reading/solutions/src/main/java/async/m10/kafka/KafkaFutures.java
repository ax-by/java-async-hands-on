package async.m10.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.kafka.common.KafkaFuture;

/**
 * E3, E4. KafkaFuture ↔ CompletableFuture 브리지 (기여 시뮬레이션: 사내 Kafka 운영 도구 라이브러리).
 *
 * <p>이 라이브러리는 <b>Kafka 2.x 클라이언트를 쓰는 서비스도 지원</b>해야 한다. 2.x 의 KafkaFuture 에는
 * {@code toCompletionStage()}(3.0+)가 없으므로 <b>쓰지 않는다</b>. 쓸 수 있는 것은 {@code whenComplete},
 * {@code thenApply}, {@code get}, {@code cancel} 등 2.x 에도 있는 API 뿐이다.
 * (테스트는 toCompletionStage 를 호출하면 예외를 던지는 KafkaFuture 를 넘긴다.)
 */
public final class KafkaFutures {

    private KafkaFutures() {
    }

    /**
     * E3. KafkaFuture 를 <b>평범한</b> CompletableFuture 로 바꾼다.
     * <ol>
     *   <li>반환된 future 는 사용자 코드가 마음대로 써도 된다: {@code orTimeout}, {@code complete} 를 포함해 평범한 CompletableFuture 처럼 동작해야 한다.</li>
     *   <li>성공이면 같은 값. 실패면 <b>원래 원인 예외</b>로 완료한다(R8). 원본이 {@code thenApply} 로 만든 KafkaFuture 인 경우도 포함한다.</li>
     *   <li>반환된 future 를 {@code cancel} 하면 원본 KafkaFuture 도 취소한다(더 이상 결과를 기다리지 않는다는 신호).</li>
     *   <li>(문서화) 반환된 future 의 non-async 콜백은 원본을 완료시키는 Kafka 스레드에서 실행된다. → Javadoc 에 적어 둔다.</li>
     * </ol>
     */
    public static <T> CompletableFuture<T> toCompletableFuture(KafkaFuture<T> source) {
        // TODO(E3): new CompletableFuture + source.whenComplete 로 옮겨 담고, 취소는 반대 방향으로 전달한다 (O3, R8, R10)
        // SOLUTION-BEGIN
        CompletableFuture<T> target = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (error != null) {
                target.completeExceptionally(unwrapCompletion(error));
            } else {
                target.complete(value);
            }
        });
        target.whenComplete((value, error) -> {
            if (target.isCancelled()) {
                source.cancel(false);
            }
        });
        return target;
        // SOLUTION-END
    }

    /**
     * E4. AdminClient 결과처럼 "키별 KafkaFuture 맵"을 하나의 future 로 모은다
     * (예: {@code DescribeTopicsResult.topicNameValues()}).
     * <ol>
     *   <li>모두 성공하면 <b>입력 맵의 순서대로</b> 결과 맵을 만든다.</li>
     *   <li>하나라도 실패하면 <b>나머지를 기다리지 않고 즉시</b> 그 원인 예외로 실패한다.</li>
     *   <li>빈 맵이면 즉시 빈 맵으로 완료한다.</li>
     *   <li>어떤 스레드도 막지 않는다(join 은 완료가 보장된 뒤에만, R5).</li>
     * </ol>
     */
    public static <K, V> CompletableFuture<Map<K, V>> allOf(Map<K, KafkaFuture<V>> futures) {
        // TODO(E4): E3 를 재사용하고, "모두 성공"과 "하나라도 실패"를 각각 누가 완료시키는지 정한다 (R1, R5, R13)
        // SOLUTION-BEGIN
        Map<K, CompletableFuture<V>> converted = new LinkedHashMap<>();
        futures.forEach((k, f) -> converted.put(k, toCompletableFuture(f)));
        CompletableFuture<Map<K, V>> result = new CompletableFuture<>();
        converted.values().forEach(f -> f.whenComplete((v, ex) -> {
            if (ex != null) {
                result.completeExceptionally(ex);
            }
        }));
        CompletableFuture.allOf(converted.values().toArray(new CompletableFuture<?>[0])).thenRun(() -> {
            Map<K, V> values = new LinkedHashMap<>();
            converted.forEach((k, f) -> values.put(k, f.join()));
            result.complete(values);
        });
        return result;
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    private static Throwable unwrapCompletion(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
    // SOLUTION-REMOVE-END
}
