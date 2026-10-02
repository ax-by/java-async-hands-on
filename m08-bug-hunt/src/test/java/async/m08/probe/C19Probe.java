package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/** C19 관측: catalog 50ms, priceDb 100ms, enrich 풀은 사례 클래스의 설정. 동시 3건(평시) 후 20건(피크). */
public final class C19Probe implements AutoCloseable {

    public final FakeEventLoopClient catalogClient = new FakeEventLoopClient("catalog", 50);
    public final FakeBlockingService priceDb = new FakeBlockingService("price-db", 100);
    public List<Outcome<String>> normal;
    public List<Outcome<String>> peak;
    public int normalViolations;

    public Diagnosis run(Function<String, CompletableFuture<String>> card) {
        BlockHoundSupport.reset();
        normal = Probes.awaitAll(IntStream.range(0, 3).mapToObj(i -> card.apply("sku-" + i)).collect(Collectors.toList()), 2000);
        normalViolations = BlockHoundSupport.violations().size();
        long normalLag = catalogClient.maxLagMillis();

        BlockHoundSupport.reset();
        peak = Probes.awaitAll(IntStream.range(0, 20).mapToObj(i -> card.apply("sku-" + i)).collect(Collectors.toList()), 4000);
        return new Diagnosis("C19")
                .measured("평시 3건: blocking 위반 %d건, lag %dms, 결과 예 %s", normalViolations, normalLag, normal.get(0))
                .measured("피크 20건: priceDb 호출 스레드 %s", priceDb.callerThreads())
                .eventLoop(BlockHoundSupport.violations(), catalogClient.maxLagMillis())
                .completed(Probes.completedCount(normal) + Probes.completedCount(peak), 23, 4000);
    }

    @Override
    public void close() {
        catalogClient.close();
    }
}
