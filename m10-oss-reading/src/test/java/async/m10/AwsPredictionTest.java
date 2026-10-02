package async.m10;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.StubHttpServer;
import async.harness.ThreadRecorder;
import async.m10.aws.AwsScenarios;

/** 5장 AWS SDK v2 예측 채점. Predictions 의 A*, Q_AWS_* 를 모두 채운 뒤 실행한다. */
@Timeout(30)
class AwsPredictionTest {

    static final String ITEM = "{\"Item\":{\"id\":{\"S\":\"1\"},\"name\":{\"S\":\"keyboard\"}}}";

    StubHttpServer server;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void a1_defaultCompletionExecutor() throws Exception {
        server.route("/", 200, 200, ITEM);
        ThreadRecorder rec = ThreadRecorder.start();
        AwsScenarios.a1(rec, server.uri(""));
        new Grader().check("m10.aws.a1.callback", Predictions.A1_CALLBACK, Threads.on(rec, "callback"), "O1,O4").verify();
    }

    @Test
    void a2_directCompletionExecutor() throws Exception {
        server.route("/", 200, 200, ITEM);
        ThreadRecorder rec = ThreadRecorder.start();
        AwsScenarios.a2(rec, server.uri(""));
        new Grader().check("m10.aws.a2.callback", Predictions.A2_CALLBACK, Threads.on(rec, "callback"), "R3,O1,O4").verify();
    }

    @Test
    void a3_apiCallTimeout() throws Exception {
        server.route("/", 1000, 200, ITEM);
        ThreadRecorder rec = ThreadRecorder.start();
        String cause = AwsScenarios.a3(rec, server.uri(""));
        new Grader()
                .check("m10.aws.a3.cause", Predictions.A3_CAUSE, cause, "R8,R10")
                .check("m10.aws.a3.callback", Predictions.A3_CALLBACK, Threads.on(rec, "callback"), "R1,R10,O1")
                .verify();
    }

    @Test
    void a4_retries() throws Exception {
        server.route("/", 10, 500, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#InternalServerError\",\"message\":\"boom\"}");
        new Grader().check("m10.aws.a4.attempts", Predictions.A4_ATTEMPTS, AwsScenarios.a4(server), "O1").verify();
    }

    @Test
    void quiz_sourceReading() {
        new Grader()
                .checkHashed("m10.aws.q.queueCapacity", Predictions.Q_AWS_QUEUE_CAPACITY,
                        "40510175845988f13f6162ed8526f0b09f73384467fa855e1e79b44a56562a58", "R11,O4")
                .checkHashed("m10.aws.q.dynamoDefaultAttempts", Predictions.Q_AWS_DYNAMODB_DEFAULT_ATTEMPTS,
                        "19581e27de7ced00ff1ce50b2047e7a567c76b1cbaebabe5ef03f7c3017bb5b7", "R7")
                .verify();
    }
}
