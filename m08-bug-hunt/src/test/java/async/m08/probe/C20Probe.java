package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;
import async.m08.support.AuthContext;

/** C20 관측: export 풀 1, exportDb 30ms. alice 요청 후 로그인 정보 없는 야간 배치. */
public final class C20Probe implements AutoCloseable {

    public final FakeBlockingService exportDb = new FakeBlockingService("export-db", 30);
    public final NamedPool pool = Pools.fixed("export", 1);
    public Outcome<String> aliceRequest;
    public Outcome<String> nightlyBatch;
    public Outcome<String> leftover;

    public Diagnosis run(Function<String, CompletableFuture<String>> export) {
        AuthContext.set("alice");
        try {
            aliceRequest = Outcome.await(export.apply("q1"), 2000);
        } finally {
            AuthContext.clear();
        }
        nightlyBatch = Outcome.await(export.apply("nightly"), 2000);
        // 풀 스레드에 남은 값을 직접 본다 (데코레이터를 거치지 않는 작업)
        leftover = Outcome.await(CompletableFuture.supplyAsync(AuthContext::get, pool), 2000);
        boolean leaked = (nightlyBatch.succeeded() && nightlyBatch.value().contains("alice"))
                || (leftover.succeeded() && leftover.value() != null);
        return new Diagnosis("C20")
                .measured("alice 요청 결과: %s", aliceRequest)
                .completed((aliceRequest.completed() ? 1 : 0) + (nightlyBatch.completed() ? 1 : 0), 2, 2000)
                .flagIf(leaked, Symptom.CONTEXT_LEAK,
                        "로그인 정보 없는 배치 결과: %s / 작업이 끝난 풀 스레드에 남은 사용자: %s",
                        nightlyBatch, leftover.value());
    }

    @Override
    public void close() {
        pool.close();
        AuthContext.clear();
    }
}
