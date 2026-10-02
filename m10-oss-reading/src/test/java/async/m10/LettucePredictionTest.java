package async.m10;

import java.time.Duration;
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

import async.harness.Gate;
import async.harness.Grader;
import async.harness.ThreadRecorder;
import async.m10.lettuce.LettuceScenarios;

/** 4장 Lettuce 예측 채점. Predictions 의 L*, Q_LETTUCE_* 를 모두 채운 뒤 실행한다. */
@Timeout(20)
class LettucePredictionTest {

    FakeRedisServer server;
    Gate serverReleases;
    RedisClient client;

    @BeforeEach
    void setUp() {
        serverReleases = new Gate();
        server = FakeRedisServer.start().holdUntil(serverReleases)
                .put("held:product:1", "keyboard").put("held:product:2", "mouse").put("product:2", "mouse");
        client = RedisClient.create(RedisURI.builder().withHost("127.0.0.1").withPort(server.port())
                .withTimeout(Duration.ofMillis(300)).build());
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());
    }

    @AfterEach
    void tearDown() {
        serverReleases.open();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        server.close();
    }

    @Test
    void l1_callbackThread() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            LettuceScenarios.l1(rec, conn.async(), serverReleases).get(2, TimeUnit.SECONDS);
        }
        new Grader().check("m10.lettuce.l1.callback", Predictions.L1_CALLBACK, Threads.on(rec, "callback"), "R1,O1").verify();
    }

    @Test
    void l2_otherGetWhileCallbackBlocks() {
        long ms;
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            ms = LettuceScenarios.l2(conn.async(), serverReleases);
        }
        new Grader().checkMillis("m10.lettuce.l2.sameConnectionMs", Predictions.L2_SAME_CONNECTION_MS, ms, "R1,R4,O1").verify();
    }

    @Test
    void l3_syncCallInsideCallback() {
        String outcome;
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            outcome = LettuceScenarios.l3(conn, serverReleases);
        }
        new Grader().check("m10.lettuce.l3.syncInCallback", Predictions.L3_SYNC_IN_CALLBACK, outcome, "R4,R5,O4").verify();
    }

    @Test
    void l4_publishOnScheduler() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).publishOnScheduler(true).build());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            LettuceScenarios.l4(rec, conn, serverReleases).get(2, TimeUnit.SECONDS);
        }
        new Grader()
                .check("m10.lettuce.l4.async", Predictions.L4_ASYNC, Threads.on(rec, "async"), "O1")
                .check("m10.lettuce.l4.reactive", Predictions.L4_REACTIVE, Threads.on(rec, "reactive"), "O1")
                .verify();
    }

    @Test
    void quiz_sourceReading() {
        new Grader()
                .checkHashed("m10.lettuce.q.asyncCommandSuper", Predictions.Q_LETTUCE_ASYNC_COMMAND_SUPER,
                        "42b510c65e319b4c6f1185aa03cabb64fb37ffe632232eae074d79aaa39c45eb", "O3")
                .checkHashed("m10.lettuce.q.publishOnReader", Predictions.Q_LETTUCE_PUBLISH_ON_READER,
                        "34f1d08c7759c82f4a8edacf093b8f2eb2a2900c33d21f496cf0e9f70773daf6", "O1")
                .verify();
    }
}
