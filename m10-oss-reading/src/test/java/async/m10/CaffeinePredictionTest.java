package async.m10;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.m10.caffeine.CaffeineScenarios;

/** 1장 Caffeine 예측 채점. Predictions 의 C*, Q_CAFFEINE_* 를 모두 채운 뒤 실행한다. */
@Timeout(20)
class CaffeinePredictionTest {

    @Test
    void c1_sameKeyConcurrentGets() {
        try (NamedPool loader = Pools.fixed("loader", 2)) {
            CaffeineScenarios.DedupResult r = CaffeineScenarios.c1(loader);
            new Grader()
                    .check("m10.caffeine.c1.loaderCalls", Predictions.C1_LOADER_CALLS, r.loaderCalls(), "O2")
                    .check("m10.caffeine.c1.sameInstance", Predictions.C1_SAME_INSTANCE, r.sameInstance(), "O2")
                    .verify();
        }
    }

    @Test
    void c2_defaultExecutor() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        CaffeineScenarios.c2(rec).get(2, TimeUnit.SECONDS);
        new Grader()
                .check("m10.caffeine.c2.loader", Predictions.C2_LOADER, Threads.on(rec, "loader"), "R2,O1")
                .check("m10.caffeine.c2.whileLoading", Predictions.C2_WHILE_LOADING, Threads.on(rec, "whileLoading"), "R1,O1")
                .verify();
    }

    @Test
    void c3_asyncMappingFunction() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        try (NamedPool loader = Pools.fixed("loader", 2)) {
            CaffeineScenarios.c3(rec, loader).get(2, TimeUnit.SECONDS);
        }
        new Grader().check("m10.caffeine.c3.mappingFn", Predictions.C3_MAPPING_FN, Threads.on(rec, "mappingFn"), "O1").verify();
    }

    @Test
    void c4_failedLoad() {
        try (NamedPool loader = Pools.fixed("loader", 2)) {
            CaffeineScenarios.FailureResult r = CaffeineScenarios.c4(loader);
            new Grader()
                    .check("m10.caffeine.c4.presentAfterFailure", Predictions.C4_PRESENT_AFTER_FAILURE, r.presentAfterFailure(), "O2")
                    .check("m10.caffeine.c4.loaderCalls", Predictions.C4_LOADER_CALLS, r.loaderCalls(), "O2")
                    .verify();
        }
    }

    @Test
    void c5_oneCallersOrTimeout() {
        try (NamedPool loader = Pools.fixed("loader", 2)) {
            new Grader().check("m10.caffeine.c5.otherCaller", Predictions.C5_OTHER_CALLER, CaffeineScenarios.c5(loader), "O2,R10").verify();
        }
    }

    @Test
    void c6_synchronousView() {
        try (NamedPool loader = Pools.fixed("loader", 2)) {
            CaffeineScenarios.SyncViewResult r = CaffeineScenarios.c6(loader);
            new Grader()
                    .checkMillis("m10.caffeine.c6.getIfPresentMs", Predictions.C6_GET_IF_PRESENT_MS, r.getIfPresentMs(), "R5")
                    .checkMillis("m10.caffeine.c6.getMs", Predictions.C6_GET_MS, r.getMs(), "R5")
                    .verify();
        }
    }

    @Test
    void quiz_sourceReading() {
        new Grader()
                .checkHashed("m10.caffeine.q.removalMethod", Predictions.Q_CAFFEINE_REMOVAL_METHOD,
                        "ad1becfc93e656b32808b5893c46fcebb59b36b59e3868f6f8fa943f95ff8402", "O2")
                .checkHashed("m10.caffeine.q.listenerOnFailure", Predictions.Q_CAFFEINE_LISTENER_ON_FAILURE,
                        "9390298f3fb0c5b160498935d79cb139aef28e1c47358b4bbba61862b9c26e59", "O2")
                .verify();
    }
}
