package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;
import async.m08.support.Log;
import async.m08.support.Mdc;
import async.m08.support.MdcExecutor;

/** C22 관측: 요청 스레드 MDC traceId=t-77, pgClient 50ms, refundDb 30ms, io 4 (MDC 데코레이터). */
public final class C22Probe implements AutoCloseable {

    public final FakeEventLoopClient pgClient = new FakeEventLoopClient("pg", 50);
    public final FakeBlockingService refundDb = new FakeBlockingService("refund-db", 30);
    public final NamedPool pool = Pools.fixed("io", 4);
    public final Executor mdcAwareIo = MdcExecutor.wrap(pool);
    public Outcome<String> outcome;
    public List<Log.Entry> lines;

    public Diagnosis run(Function<String, CompletableFuture<String>> cancel) {
        Log.reset();
        BlockHoundSupport.reset();
        CompletableFuture<String> f;
        Mdc.put("traceId", "t-77");
        try {
            f = cancel.apply("p-1");
        } finally {
            Mdc.clear();
        }
        outcome = Outcome.await(f, 2000);
        lines = Log.entries();
        List<Log.Entry> missing = lines.stream().filter(e -> !"t-77".equals(e.traceId())).collect(Collectors.toList());
        return new Diagnosis("C22")
                .measured("결과: %s, refundDb 호출 스레드: %s", outcome, refundDb.callerThreads())
                .eventLoop(BlockHoundSupport.violations(), pgClient.maxLagMillis())
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(!missing.isEmpty(), Symptom.CONTEXT_LOST,
                        "traceId 가 없는 로그 %d/%d줄: %s", missing.size(), lines.size(), missing);
    }

    @Override
    public void close() {
        pgClient.close();
        pool.close();
    }
}
