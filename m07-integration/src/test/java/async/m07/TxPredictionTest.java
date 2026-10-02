package async.m07;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import async.harness.Grader;
import async.m07.tx.TxScenario;

/** m07 예측 채점 (3. @Transactional 은 스레드를 넘어가지 않는다). */
@Timeout(30)
class TxPredictionTest {

    @Nested
    @SpringBootTest(classes = TxScenario.Config.class,
            properties = "spring.datasource.url=jdbc:h2:mem:m07tx;DB_CLOSE_DELAY=-1")
    class S11 {
        @Autowired TxScenario.OrderService orders;
        @Autowired TxScenario.Schema schema;

        @Test
        void s11_asyncWriteInsideTransaction() {
            schema.reset();
            try {
                orders.placeOrder(1);
            } catch (IllegalStateException expected) {
                // 결제 검증 실패 → 롤백
            }
            new Grader()
                    .check("m07.s11.ordersRows", Predictions.S11_ORDERS_ROWS, schema.count("orders"), "R12")
                    .check("m07.s11.auditRows", Predictions.S11_AUDIT_ROWS, schema.count("audit"), "R12")
                    .check("m07.s11.asyncInTx", Predictions.S11_ASYNC_IN_TX, orders.asyncSawTransaction(), "R12")
                    .verify();
        }
    }

    @Nested
    @SpringBootTest(classes = TxScenario.Config.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:m07tx1;DB_CLOSE_DELAY=-1",
            "spring.datasource.hikari.maximum-pool-size=1",
            "spring.datasource.hikari.connection-timeout=250"})
    class S12 {
        @Autowired TxScenario.OrderService orders;
        @Autowired TxScenario.Schema schema;

        @Test
        void s12_singleConnectionPool() {
            schema.reset();
            long start = System.nanoTime();
            try {
                orders.placeOrderAndCommit(1);
            } catch (RuntimeException e) {
                System.out.println("S12: " + e);
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            new Grader()
                    .checkMillis("m07.s12.elapsedMs", Predictions.S12_ELAPSED_MS, elapsed, "R11,R12")
                    .check("m07.s12.ordersRows", Predictions.S12_ORDERS_ROWS, schema.count("orders"), "R8,R12")
                    .verify();
        }
    }
}
