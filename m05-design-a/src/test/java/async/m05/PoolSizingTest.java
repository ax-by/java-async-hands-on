package async.m05;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Pools;
import async.harness.Pools.NamedPool;

/** E3 채점: Little 의 법칙으로 정한 풀 크기가 실제 부하를 견디는가. (E1 이 먼저 통과해야 한다) */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PoolSizingTest {

    @Test
    void e3_threadsFor_isLittlesLawWithBoundedHeadroom() {
        int t100 = ProductDetailPool.threadsFor(100);
        int t200 = ProductDetailPool.threadsFor(200);
        assertThat(t100)
                .as("100 RPS × (80+120+100+150)ms = 45 스레드가 최소다 (R11 Little). 여유는 2배(90)까지")
                .isBetween(45, 90);
        assertThat(t200).as("200 RPS → 90 이상 180 이하").isBetween(90, 180);
        assertThat(Math.abs(t200 - 2 * t100)).as("RPS 가 두 배면 스레드도 (거의) 두 배").isLessThanOrEqualTo(2);
    }

    @Test
    void e3_poolSizedByLittlesLaw_meetsSloAt100Rps() {
        ProductBackends backends = new ProductBackends();
        try (NamedPool io = Pools.fixed("io", ProductDetailPool.threadsFor(100))) {
            ProductDetailAggregator aggregator = new ProductDetailAggregator(backends, io);
            LoadDriver.LoadReport report = LoadDriver.run(40, 10, aggregator::load);
            System.out.println("[E3] 풀 " + ProductDetailPool.threadsFor(100) + ": " + report);
            assertThat(report.maxSubmitMillis())
                    .as("load 가 요청 스레드를 막으면 부하 발생기 자체가 밀린다 (R3)").isLessThan(20);
            assertThat(report.failed()).as("100 RPS 에서 실패가 없어야 한다. 풀이 작으면 큐 대기 → 타임아웃 (R11)").isZero();
            assertThat(report.maxSuccessLatencyMillis())
                    .as("가장 느린 요청도 SLO(p99 < 200ms) 근처여야 한다 (여유 포함 250ms)").isLessThan(250);
            assertThat(backends.maxTotalConcurrency())
                    .as("동시 호출 수는 풀 크기를 넘을 수 없다").isLessThanOrEqualTo(ProductDetailPool.threadsFor(100));
        }
    }
}
