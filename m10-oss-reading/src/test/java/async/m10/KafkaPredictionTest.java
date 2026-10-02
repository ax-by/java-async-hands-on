package async.m10;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.ThreadRecorder;
import async.m10.kafka.KafkaScenarios;

/** 2장 Kafka 예측 채점. Predictions 의 K*, Q_KAFKA_* 를 모두 채운 뒤 실행한다. */
@Timeout(20)
class KafkaPredictionTest {

    @Test
    void k1_exceptionWrapping() {
        new Grader().check("m10.kafka.k1.afterThenApply", Predictions.K1_AFTER_THEN_APPLY, KafkaScenarios.k1(), "R8,O3").verify();
    }

    @Test
    void k2_k3_k4_completionStageContract() {
        new Grader()
                .check("m10.kafka.k2.completeOnStage", Predictions.K2_COMPLETE_ON_STAGE, KafkaScenarios.k2(), "O3")
                .check("m10.kafka.k3.orTimeoutFired", Predictions.K3_OR_TIMEOUT_FIRED, KafkaScenarios.k3(), "O3,R10")
                .check("m10.kafka.k4.originalCancelled", Predictions.K4_ORIGINAL_CANCELLED, KafkaScenarios.k4(), "O3,R10")
                .verify();
    }

    @Test
    void k5_adminClientCallbackThread() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        KafkaScenarios.k5(rec);
        new Grader().check("m10.kafka.k5.callback", Predictions.K5_CALLBACK, Threads.on(rec, "callback"), "O1").verify();
    }

    @Test
    void k6_producerSendWithoutBroker() {
        ThreadRecorder rec = ThreadRecorder.start();
        KafkaScenarios.SendResult r = KafkaScenarios.k6(rec);
        new Grader()
                .checkMillis("m10.kafka.k6.sendReturnedMs", Predictions.K6_SEND_RETURNED_MS, r.sendReturnedMs(), "R4,O1")
                .check("m10.kafka.k6.callback", Predictions.K6_CALLBACK, Threads.on(rec, "callback"), "O1")
                .verify();
    }

    @Test
    void quiz_sourceReading() {
        new Grader()
                .checkHashed("m10.kafka.q.producerIoThread", Predictions.Q_KAFKA_PRODUCER_IO_THREAD,
                        "d7d13e2a3011db0a598fdd892ab8bda35892a0da78b06d359bee960f33f731b8", "O1")
                .checkHashed("m10.kafka.q.recordMetadataWait", Predictions.Q_KAFKA_RECORD_METADATA_WAIT,
                        "d66e506468dcb6a5acdc6cae617d6989d5d177a6d2e53fb0d94b410b79fb271e", "R5,O3")
                .verify();
    }
}
