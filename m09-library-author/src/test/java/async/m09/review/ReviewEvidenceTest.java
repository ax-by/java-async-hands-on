package async.m09.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Sleep;
import async.harness.ThreadRecorder;
import async.harness.On;
import async.harness.Timed;
import async.m09.minikv.FakeTransport;

/**
 * REVIEW.md 의 지적 사항을 <b>실행으로 증명</b>한다. 리뷰를 먼저 쓰고 나서 읽어라.
 * 이 테스트는 BadKvClient 의 "증상"을 단언하므로, 정상이면 항상 통과한다.
 */
@Timeout(10)
class ReviewEvidenceTest {

    FakeTransport transport;
    BadKvClient client;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport(200).failOn("broken", new IOException("connection reset"));
        client = new BadKvClient(transport);
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    void getAsync_blocksACommonPoolWorker() throws Exception {
        client.getAsync("k").get(2, TimeUnit.SECONDS);
        assertThat(transport.senderThreads())
                .as("[#19, #4] fetch(blocking get)가 commonPool 워커에서 실행된다 → JVM 전체 공유 풀을 200ms 씩 점유")
                .allMatch(name -> name.startsWith("ForkJoinPool.commonPool-worker"));
    }

    @Test
    void callerCanPoisonTheCacheForEveryone() throws Exception {
        CompletableFuture<String> a = client.getAsync("k");
        a.complete("hacked");
        assertThat(client.getAsync("k").get(2, TimeUnit.SECONDS)).as("[#17] 한 호출자의 complete 가 캐시를 영구 오염").isEqualTo("hacked");
        Sleep.ms(300);
        assertThat(client.getAsync("k").join()).as("응답이 와도 캐시 값은 그대로다").isEqualTo("hacked");
    }

    @Test
    void callerCancelBreaksOtherCallers_andDoesNotReachTransport() throws Exception {
        CompletableFuture<String> a = client.getAsync("x");
        CompletableFuture<String> b = client.getAsync("x");
        while (transport.requestCount() == 0) { // fetch 가 commonPool 에서 시작될 때까지 (시작 전 취소는 supplier 를 건너뛴다)
            Thread.onSpinWait();
        }
        a.cancel(true);
        assertThat(b.isCancelled()).as("[#17] 같은 객체이므로 B 도 취소됨").isTrue();
        Sleep.ms(400);
        assertThat(transport.abortedCount()).as("[R10] 취소가 transport 까지 가지 않는다").isZero();
        assertThat(transport.responseCount()).as("[R10] 요청은 끝까지 실행됐다(commonPool 스레드도 그동안 점유)").isEqualTo(1);
    }

    @Test
    void failureIsCachedForever() {
        CompletableFuture<String> first = client.getAsync("broken");
        assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> client.getAsync("broken").get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(transport.requestCount()).as("[L7] 실패가 캐시되어 재시도하지 않는다").isEqualTo(1);
    }

    @Test
    void getFreshAsync_isFakeAsync() {
        ThreadRecorder rec = ThreadRecorder.start();
        Timed<CompletableFuture<String>> call = Timed.of(() -> client.getFreshAsync("k"));
        call.value().thenRun(() -> rec.mark("user"));
        assertThat(call.millis()).as("[#1] 비동기라는 메서드가 응답까지 호출자를 막는다").isGreaterThanOrEqualTo(150L);
        assertThat(rec.on("user")).as("[R1] 이미 완료된 future → 콜백은 호출자 스레드").isEqualTo(On.caller());
    }

    @Test
    void syncGet_leaksThreeLayersOfWrapping() {
        assertThatThrownBy(() -> client.get("broken"))
                .as("[L6] 사용자가 CompletionException → RuntimeException → ExecutionException → IOException 을 세 번 벗겨야 한다")
                .isExactlyInstanceOf(CompletionException.class)
                .cause().isExactlyInstanceOf(RuntimeException.class)
                .cause().isExactlyInstanceOf(ExecutionException.class)
                .cause().isExactlyInstanceOf(IOException.class);
    }
}
