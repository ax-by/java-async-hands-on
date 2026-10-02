package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/** C17 관측: 주문 20건 동시, orderClient 50ms, legacyDb 80ms, jdbc 20. */
public final class C17Probe implements AutoCloseable {

    public final FakeEventLoopClient orderClient = new FakeEventLoopClient("order", 50);
    public final FakeBlockingService legacyDb = new FakeBlockingService("legacy-db", 80);
    public final NamedPool jdbcPool = Pools.fixed("jdbc", 20);
    public List<Outcome<String>> outcomes;
    public long totalMillis;

    public Diagnosis run(Function<String, CompletableFuture<String>> detail) {
        BlockHoundSupport.reset();
        long start = System.nanoTime();
        List<CompletableFuture<String>> fs = IntStream.range(0, 20)
                .mapToObj(i -> detail.apply("o-" + i)).collect(Collectors.toList());
        outcomes = Probes.awaitAll(fs, 3000);
        totalMillis = Probes.since(start);
        return new Diagnosis("C17")
                .measured("20건 완료까지 %dms, legacyDb 호출 스레드 예: %s", totalMillis,
                        legacyDb.callerThreads().stream().sorted().limit(3).collect(Collectors.toList()))
                .eventLoop(BlockHoundSupport.violations(), orderClient.maxLagMillis())
                .completed(Probes.completedCount(outcomes), 20, 3000);
    }

    @Override
    public void close() {
        orderClient.close();
        jdbcPool.close();
    }
}
