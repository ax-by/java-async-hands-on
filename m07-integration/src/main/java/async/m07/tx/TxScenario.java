package async.m07.tx;

import java.util.concurrent.CompletableFuture;
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

import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * m07 트랜잭션 예측 시나리오. <b>이 파일은 수정하지 않는다.</b>
 *
 * <p>H2 인메모리 DB, HikariCP, JdbcTemplate, {@code @Transactional} (Spring Boot 자동 구성).
 * 테이블: {@code orders(id, note)}, {@code audit(id, note)}.
 */
public final class TxScenario {

    private TxScenario() {
    }

    /** 이 시나리오의 Spring 구성 (웹 없음). */
    @Configuration(proxyBeanMethods = false)
    @Import({OrderService.class, Schema.class})
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class})
    public static class Config {
        @Bean(destroyMethod = "close")
        NamedPool txScenarioIo() {
            return Pools.fixed("io", 2);
        }
    }

    /** 테이블을 다시 만든다. */
    @Service
    public static class Schema {
        private final JdbcTemplate jdbc;

        public Schema(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public void reset() {
            jdbc.execute("drop table if exists orders");
            jdbc.execute("drop table if exists audit");
            jdbc.execute("create table orders(id int primary key, note varchar(50))");
            jdbc.execute("create table audit(id int primary key, note varchar(50))");
        }

        public int count(String table) {
            return jdbc.queryForObject("select count(*) from " + table, Integer.class);
        }
    }

    @Service
    public static class OrderService {
        private final JdbcTemplate jdbc;
        private final NamedPool io;

        /** 비동기 작업 안에서 관찰한 값 (예측 대상). */
        private final AtomicReference<Boolean> asyncSawTransaction = new AtomicReference<>();

        public OrderService(JdbcTemplate jdbc, NamedPool txScenarioIo) {
            this.jdbc = jdbc;
            this.io = txScenarioIo;
        }

        /**
         * S11. 주문을 저장하고, 감사 로그(audit)는 "응답을 빨리 주려고" 다른 스레드에서 저장한다.
         * 마지막에 결제 검증이 실패해 예외가 난다 → @Transactional 이 롤백한다.
         * 롤백 후 orders 와 audit 에는 각각 몇 행이 남을까? 비동기 작업 안에서 트랜잭션이 활성 상태였을까?
         */
        @Transactional
        public void placeOrder(int id) {
            jdbc.update("insert into orders(id, note) values (?, 'placed')", id);
            CompletableFuture.runAsync(() -> {
                asyncSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                jdbc.update("insert into audit(id, note) values (?, 'order placed')", id);
            }, io).join();
            throw new IllegalStateException("결제 검증 실패 → 롤백");
        }

        /**
         * S12. 롤백 없이 정상 커밋하는 같은 모양의 메서드. 단, 이번엔 커넥션 풀 크기가 1이다
         * ({@code spring.datasource.hikari.maximum-pool-size=1}, {@code connection-timeout=250ms}).
         * 어떤 일이 일어날까?
         */
        /** S11 의 비동기 작업 안에서 트랜잭션이 활성이었는지. (프록시를 거치므로 필드 대신 메서드로 읽는다) */
        public Boolean asyncSawTransaction() {
            return asyncSawTransaction.get();
        }

        @Transactional
        public void placeOrderAndCommit(int id) {
            jdbc.update("insert into orders(id, note) values (?, 'placed')", id);
            CompletableFuture.runAsync(() ->
                    jdbc.update("insert into audit(id, note) values (?, 'order placed')", id), io).join();
        }
    }
}
