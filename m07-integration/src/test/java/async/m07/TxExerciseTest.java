package async.m07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import async.harness.FakeBlockingService;
import async.harness.Timed;
import async.m07.tx.OrderPlacementFacade;
import async.m07.tx.PlacementExercise;
import async.m07.tx.TxScenario;

/** m07 구현 채점 E4. */
@Timeout(30)
@SpringBootTest(classes = PlacementExercise.Config.class,
        properties = "spring.datasource.url=jdbc:h2:mem:m07e4;DB_CLOSE_DELAY=-1")
class TxExerciseTest {

    @Autowired OrderPlacementFacade facade;
    @Autowired PlacementExercise.OrderWriter writer;
    @Autowired TxScenario.Schema schema;
    @Autowired @Qualifier("stockApi") FakeBlockingService stockApi;
    @Autowired @Qualifier("priceApi") FakeBlockingService priceApi;

    @BeforeEach
    void setUp() {
        schema.reset();
    }

    @Test
    void e4_success_parallelLookups_singleTransactionOnCaller() {
        Timed<Void> t = Timed.of(() -> {
            facade.place(1, false);
            return null;
        });
        assertThat(schema.count("orders")).isEqualTo(1);
        assertThat(schema.count("audit")).isEqualTo(1);
        assertThat(t.millis()).as("재고·가격 조회(각 200ms)는 독립이므로 병렬이어야 한다: ≈200ms, 400ms 아님 (R7)").isLessThan(350);
        assertThat(stockApi.callerThreads()).as("조회는 lookupPool 에서").allMatch(n -> n.startsWith("lookup-"));
        assertThat(priceApi.callerThreads()).as("조회는 lookupPool 에서").allMatch(n -> n.startsWith("lookup-"));
        assertThat(writer.lastThread()).as("쓰기는 호출 스레드에서 해야 트랜잭션이 하나로 묶인다 (R12)")
                .isEqualTo(Thread.currentThread().getName());
        assertThat(writer.lastInTx()).isTrue();
    }

    @Test
    void e4_failure_rollsBackBothTables() {
        assertThatThrownBy(() -> facade.place(2, true)).as("writer 의 예외가 호출자에게 그대로 전파돼야 한다").isInstanceOf(IllegalStateException.class);
        assertThat(schema.count("orders")).as("orders 롤백").isZero();
        assertThat(schema.count("audit"))
                .as("audit 도 롤백돼야 한다. 다른 스레드에서 쓰면 그 쓰기는 이 트랜잭션 밖이다 (R12)").isZero();
    }

    @Test
    void e4_facadeIsNotTransactional() {
        assertThat(AopUtils.isAopProxy(facade))
                .as("외부 API 를 기다리는 200ms 동안 DB 커넥션을 잡지 않도록 facade 에는 @Transactional 을 붙이지 않는다")
                .isFalse();
    }
}
