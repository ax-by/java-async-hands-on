package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import async.harness.BlockHoundSupport;
import async.harness.FakeEventLoopClient;

/** C07 관측: 상품 30개 동시, inventoryClient 50ms. */
public final class C07Probe implements AutoCloseable {

    public final FakeEventLoopClient inventoryClient = new FakeEventLoopClient("inventory", 50);
    public List<Outcome<String>> outcomes;

    public Diagnosis run(Function<String, CompletableFuture<String>> badge) {
        BlockHoundSupport.reset();
        List<CompletableFuture<String>> fs = IntStream.range(0, 30)
                .mapToObj(i -> badge.apply("sku-" + i)).collect(Collectors.toList());
        outcomes = Probes.awaitAll(fs, 2000);
        return new Diagnosis("C07")
                .measured("sku-0: %s, sku-1: %s", outcomes.get(0), outcomes.get(1))
                .eventLoop(BlockHoundSupport.violations(), inventoryClient.maxLagMillis())
                .completed(Probes.completedCount(outcomes), 30, 2000);
    }

    @Override
    public void close() {
        inventoryClient.close();
    }
}
