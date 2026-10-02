package async.m10;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m10.cassandra.CassandraScenarios;

/** 6장 DataStax 드라이버 예측 채점. Predictions 의 D*, Q_CASSANDRA_* 를 모두 채운 뒤 실행한다. */
@Timeout(30)
class CassandraPredictionTest {

    @Test
    void d1_naiveRecursivePaging() throws Exception {
        String immediate = CassandraScenarios.d1(null);
        String async;
        try (NamedPool io = Pools.single("s0-io")) {
            async = CassandraScenarios.d1(io);
        }
        new Grader()
                .check("m10.cassandra.d1.immediate", Predictions.D1_IMMEDIATE, immediate, "R1,R13")
                .check("m10.cassandra.d1.async", Predictions.D1_ASYNC, async, "R1,R13")
                .verify();
    }

    @Test
    void quiz_sourceReading() {
        new Grader()
                .checkHashed("m10.cassandra.q.ioThread", Predictions.Q_CASSANDRA_IO_THREAD,
                        "ceafc4308f063ca6b299b32edc817a83af34d16fa9a8e6105571e0745b7d31b0", "O1")
                .checkHashed("m10.cassandra.q.syncOnDriverThread", Predictions.Q_CASSANDRA_SYNC_ON_DRIVER_THREAD,
                        "46177166f00543ae9fc11d68e8a03772818511c494458a4b71a2daa9862651ee", "O4")
                .checkHashed("m10.cassandra.q.defaultThrottler", Predictions.Q_CASSANDRA_DEFAULT_THROTTLER,
                        "1822b79c52d782e515c9c575a49138f1871a6c0bd7dac135889b64e977d1a4d6", "O4")
                .checkHashed("m10.cassandra.q.throttled", Predictions.Q_CASSANDRA_THROTTLED,
                        "7920233ae54b0a9c40e4321c73519a5c5490d1f7f6b540939ee36ffe8878b151", "O4")
                .verify();
    }
}
