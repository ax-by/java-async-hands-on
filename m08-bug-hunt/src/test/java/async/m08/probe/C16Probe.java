package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;
import async.m08.support.Log;
import async.m08.support.Mdc;
import async.m08.support.MdcExecutor;

/** C16 관측: 요청 스레드 MDC traceId=t-42, orderDb 50ms, delivery 50ms, io 4 (MDC 데코레이터). */
public final class C16Probe implements AutoCloseable {

    public final FakeBlockingService orderDb = new FakeBlockingService("order-db", 50);
    public final FakeEventLoopClient deliveryClient = new FakeEventLoopClient("delivery", 50);
    public final NamedPool pool = Pools.fixed("io", 4);
    public final Executor mdcAwareIo = MdcExecutor.wrap(pool);
    public Outcome<String> outcome;
    public List<Log.Entry> lines;

    public Diagnosis run(Function<String, CompletableFuture<String>> status) {
        Log.reset();
        CompletableFuture<String> f;
        Mdc.put("traceId", "t-42");     // 서블릿 필터가 넣어 준 값
        try {
            f = status.apply("o-1");
        } finally {
            Mdc.clear();
        }
        outcome = Outcome.await(f, 2000);
        lines = Log.entries();
        List<Log.Entry> missing = lines.stream().filter(e -> !"t-42".equals(e.traceId())).collect(Collectors.toList());
        return new Diagnosis("C16")
                .measured("결과: %s", outcome)
                .completed(outcome.completed() ? 1 : 0, 1, 2000)
                .flagIf(!missing.isEmpty(), Symptom.CONTEXT_LOST,
                        "traceId 가 없는 로그 %d/%d줄: %s", missing.size(), lines.size(), missing);
    }

    @Override
    public void close() {
        deliveryClient.close();
        pool.close();
    }
}
