package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m08.Symptom;

/** C14 관측: 리포트 2건 동시, reportDb 2000ms, report 풀 2. 응답을 모두 받은 뒤 100ms 시점에 본다. */
public final class C14Probe implements AutoCloseable {

    public final FakeBlockingService reportDb = new FakeBlockingService("report-db", 2000);
    public final NamedPool reportPool = Pools.fixed("report", 2);
    public List<Outcome<String>> outcomes;
    public int inFlightAfter;

    public Diagnosis run(Function<String, CompletableFuture<String>> salesReport) {
        List<CompletableFuture<String>> fs = List.of(salesReport.apply("q1"), salesReport.apply("q2"));
        outcomes = Probes.awaitAll(fs, 2500);
        Sleep.ms(100);
        inFlightAfter = reportDb.inFlight();
        return new Diagnosis("C14")
                .measured("호출자가 받은 결과: %s", outcomes)
                .completed(Probes.completedCount(outcomes), 2, 2500)
                .flagIf(inFlightAfter >= 1, Symptom.TIMEOUT_DOES_NOT_FREE_RESOURCES,
                        "응답 완료 100ms 후 reportDb 진행 중 %d건, report 풀 active %d/2, interrupt 로 중단된 호출 %d건",
                        inFlightAfter, reportPool.getActiveCount(), reportDb.interruptedCount());
    }

    @Override
    public void close() {
        reportPool.close();
    }
}
