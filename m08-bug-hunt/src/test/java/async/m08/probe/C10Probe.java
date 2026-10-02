package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import async.harness.BlockHoundSupport;
import async.harness.FakeEventLoopClient;

/** C10 관측: 장바구니 5개 동시, cartStore 20ms, pricing 100ms. */
public final class C10Probe implements AutoCloseable {

    public final FakeEventLoopClient cartStore = new FakeEventLoopClient("cart", 20);
    public final FakeEventLoopClient pricingClient = new FakeEventLoopClient("pricing", 100);
    public List<Outcome<String>> outcomes;
    public long totalMillis;

    public Diagnosis run(Function<String, CompletableFuture<String>> priceCart) {
        BlockHoundSupport.reset();
        long start = System.nanoTime();
        List<CompletableFuture<String>> fs = IntStream.range(0, 5)
                .mapToObj(i -> priceCart.apply("c-" + i)).collect(Collectors.toList());
        outcomes = Probes.awaitAll(fs, 3000);
        totalMillis = Probes.since(start);
        return new Diagnosis("C10")
                .measured("5건 모두 끝나기까지 %dms, 첫 결과 %s", totalMillis, outcomes.get(0))
                .eventLoop(BlockHoundSupport.violations(), Math.max(cartStore.maxLagMillis(), pricingClient.maxLagMillis()))
                .completed(Probes.completedCount(outcomes), 5, 3000);
    }

    @Override
    public void close() {
        cartStore.close();
        pricingClient.close();
    }
}
