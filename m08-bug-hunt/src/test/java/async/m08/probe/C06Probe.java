package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeEventLoopClient;
import async.m08.Symptom;
import async.m08.support.Log;

/** C06 관측: 추천 서버가 30ms 후 실패. */
public final class C06Probe implements AutoCloseable {

    public final IllegalStateException recoFailure = new IllegalStateException("reco 503");
    public final FakeEventLoopClient recoClient = new FakeEventLoopClient("reco", 30).failOn("sku-1", recoFailure);
    public Outcome<String> outcome;

    public Diagnosis run(Function<String, CompletableFuture<String>> forProduct) {
        Log.reset();
        outcome = Outcome.await(forProduct.apply("sku-1"), 2000);
        return new Diagnosis("C06")
                .measured("로그: %s", Log.entries())
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(outcome.failed(), Symptom.FALLBACK_NOT_APPLIED,
                        "폴백 대상 실패를 주입했을 때 호출자가 받은 결과: %s", outcome);
    }

    @Override
    public void close() {
        recoClient.close();
    }
}
