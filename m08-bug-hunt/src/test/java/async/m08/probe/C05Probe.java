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

/** C05 관측: 주문 10건 동시, orderClient 50ms, coupon 50ms, history 30ms, db 8. */
public final class C05Probe implements AutoCloseable {

    public final FakeEventLoopClient orderClient = new FakeEventLoopClient("order", 50);
    public final FakeBlockingService couponRepository = new FakeBlockingService("coupon", 50);
    public final FakeBlockingService historyRepository = new FakeBlockingService("history", 30);
    public final NamedPool dbPool = Pools.fixed("db", 8);
    public List<Outcome<String>> outcomes;

    public Diagnosis run(Function<String, CompletableFuture<String>> orderPage) {
        BlockHoundSupport.reset();
        List<CompletableFuture<String>> fs = IntStream.range(0, 10)
                .mapToObj(i -> orderPage.apply("o-" + i)).collect(Collectors.toList());
        outcomes = Probes.awaitAll(fs, 3000);
        return new Diagnosis("C05")
                .measured("첫 결과: %s, coupon 호출 스레드: %s", outcomes.get(0), couponRepository.callerThreads())
                .eventLoop(BlockHoundSupport.violations(), orderClient.maxLagMillis())
                .completed(Probes.completedCount(outcomes), 10, 3000);
    }

    @Override
    public void close() {
        orderClient.close();
        dbPool.close();
    }
}
