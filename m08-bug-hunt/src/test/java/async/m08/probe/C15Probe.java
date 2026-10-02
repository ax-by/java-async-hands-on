package async.m08.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;

/** C15 관측: 검색 6건 연달아, searchDb 100ms, io 2(큐 무제한). 첫 요청의 응답시간. */
public final class C15Probe implements AutoCloseable {

    public final FakeBlockingService searchDb = new FakeBlockingService("search-db", 100);
    public final NamedPool io = Pools.fixed("io", 2);
    public Outcome<String> first;
    public List<Outcome<String>> all;
    public long maxReturnMillis;

    public Diagnosis run(Function<String, CompletableFuture<String>> search) {
        long start = System.nanoTime();
        List<CompletableFuture<String>> fs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            long t = System.nanoTime();
            fs.add(search.apply("q" + i));
            maxReturnMillis = Math.max(maxReturnMillis, Probes.since(t));
        }
        first = Outcome.await(fs.get(0), 2000);
        long firstLatency = Probes.since(start);
        all = Probes.awaitAll(fs, 2000);
        long extra = firstLatency - 100;
        return new Diagnosis("C15")
                .measured("첫 요청 결과: %s", first)
                .callerBlocked(maxReturnMillis)
                .completed(Probes.completedCount(all), 6, 2000)
                .flagIf(first.completed() && extra >= Diagnosis.HOP_DELAY_MS, Symptom.HOP_QUEUEING_DELAY,
                        "첫 요청 응답 %dms = blocking 100ms + 추가 %dms (기준: 추가 ≥ %dms)", firstLatency, extra, Diagnosis.HOP_DELAY_MS);
    }

    @Override
    public void close() {
        io.close();
    }
}
