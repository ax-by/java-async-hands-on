package async.m10;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.ProtocolVersion;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.Gate;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Timed;
import async.m10.lettuce.ProductRedisRepository;

/** E6 채점. 가짜 Redis 서버 + 진짜 Lettuce 연결 + BlockHound. */
@Timeout(20)
class LettuceExercisesTest {

    FakeRedisServer server;
    Gate serverReleases;
    RedisClient client;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        serverReleases = new Gate();
        server = FakeRedisServer.start().holdUntil(serverReleases, "product:1")
                .put("product:1", "keyboard").put("product:2", "mouse");
        client = RedisClient.create(RedisURI.builder().withHost("127.0.0.1").withPort(server.port())
                .withTimeout(Duration.ofSeconds(2)).build());
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());
    }

    @AfterEach
    void tearDown() {
        serverReleases.open();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        server.close();
    }

    @Test
    void e6_auditRunsOffTheEventLoop_andDoesNotStallTheConnection() throws Exception {
        FakeBlockingService auditDb = new FakeBlockingService("audit-db", 300);
        try (NamedPool dbPool = Pools.fixed("db", 2);
             StatefulRedisConnection<String, String> conn = client.connect()) {
            ProductRedisRepository repo = new ProductRedisRepository(conn.async(), auditDb, dbPool);

            Timed<CompletableFuture<String>> call = Timed.of(() -> repo.findNameWithAudit("1"));
            assertThat(call.millis()).as("호출 스레드를 막으면 안 된다 (R3)").isLessThan(100);

            serverReleases.open(); // 이제 Redis 가 product:1 에 응답한다
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (auditDb.inFlight() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(auditDb.inFlight()).as("감사 로그가 실행되지 않았다").isEqualTo(1);

            Timed<String> other = Timed.of(() -> conn.async().get("product:2").toCompletableFuture().join());
            assertThat(other.value()).isEqualTo("mouse");
            assertThat(other.millis())
                    .as("감사 로그(300ms)가 도는 동안 같은 연결의 다른 GET 이 %dms 걸렸다. 이벤트 루프에서 blocking 하고 있다 (R1, R4, O1)", other.millis())
                    .isLessThan(150);

            assertThat(call.value().get(2, TimeUnit.SECONDS)).isEqualTo("keyboard");
        }
        assertThat(auditDb.callerThreads()).as("감사 로그는 dbPool(db-N)에서 실행돼야 한다 (R2)").allMatch(t -> t.startsWith("db-"));
        assertThat(BlockHoundSupport.violations())
                .as("Lettuce 이벤트 루프에서 blocking 이 감지됐다 (O1, R4)")
                .noneMatch(v -> v.threadName().startsWith("lettuce-"));
    }
}
