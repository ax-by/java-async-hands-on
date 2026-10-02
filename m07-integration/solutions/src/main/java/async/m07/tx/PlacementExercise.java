package async.m07.tx;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * E4 의 주어진 부품 (수정하지 않는다). 구현 대상은 {@link OrderPlacementFacade}.
 *
 * <ul>
 *   <li>{@code stockApi}, {@code priceApi}: 외부 재고/가격 API (blocking, 각 200ms)</li>
 *   <li>{@code lookupPool}: 조회 전용 풀 "lookup" (4 스레드)</li>
 *   <li>{@link OrderWriter}: orders 와 audit 를 <b>한 트랜잭션</b>으로 저장한다</li>
 * </ul>
 */
public final class PlacementExercise {

    private PlacementExercise() {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OrderWriter.class, OrderPlacementFacade.class, TxScenario.Schema.class})
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class})
    public static class Config {
        @Bean
        FakeBlockingService stockApi() {
            return new FakeBlockingService("stock", 200);
        }

        @Bean
        FakeBlockingService priceApi() {
            return new FakeBlockingService("price", 200);
        }

        @Bean(destroyMethod = "close")
        NamedPool lookupPool() {
            return Pools.fixed("lookup", 4);
        }
    }

    @Service
    public static class OrderWriter {
        private final JdbcTemplate jdbc;
        private final AtomicReference<String> lastThread = new AtomicReference<>();
        private final AtomicReference<Boolean> lastInTx = new AtomicReference<>();

        public OrderWriter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** orders 와 audit 를 저장한다. failAfterInsert 면 저장 후 예외를 던져 둘 다 롤백시킨다. */
        @Transactional
        public void write(int id, String stock, String price, boolean failAfterInsert) {
            lastThread.set(Thread.currentThread().getName());
            lastInTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("insert into orders(id, note) values (?, ?)", id, stock + "," + price);
            jdbc.update("insert into audit(id, note) values (?, 'order placed')", id);
            if (failAfterInsert) {
                throw new IllegalStateException("결제 검증 실패 → 롤백");
            }
        }

        public String lastThread() {
            return lastThread.get();
        }

        public Boolean lastInTx() {
            return lastInTx.get();
        }
    }
}
