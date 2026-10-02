package async.m10;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.m10.netty.NettyScenarios;

/** 3장 Netty 프렐류드 예측 채점. Predictions 의 N*, Q_NETTY_* 를 모두 채운 뒤 실행한다. */
@Timeout(20)
class NettyPredictionTest {

    MultiThreadIoEventLoopGroup group;
    EventLoop loop;
    NamedPool completer;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        loop = group.next();
        completer = Pools.single("completer");
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        completer.close();
        group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).await(2, TimeUnit.SECONDS);
    }

    @Test
    void n1_listenerOfLoopPromise_completedByAnotherThread() throws Exception {
        NettyScenarios.n1(rec, loop, completer).await(2, TimeUnit.SECONDS);
        awaitMark("listener");
        new Grader().check("m10.netty.n1.listener", Predictions.N1_LISTENER, Threads.on(rec, "listener"), "O3").verify();
    }

    @Test
    void n2_listenerAddedToCompletedPromise() throws Exception {
        NettyScenarios.n2(rec, loop).await(2, TimeUnit.SECONDS);
        awaitMark("listener");
        new Grader().check("m10.netty.n2.listener", Predictions.N2_LISTENER, Threads.on(rec, "listener"), "O3").verify();
    }

    @Test
    void n3_immediateExecutorPromise() throws Exception {
        NettyScenarios.n3(rec, completer).await(2, TimeUnit.SECONDS);
        awaitMark("listener");
        new Grader().check("m10.netty.n3.listener", Predictions.N3_LISTENER, Threads.on(rec, "listener"), "O3,R1").verify();
    }

    @Test
    void n4_syncInsideEventLoop() {
        new Grader().check("m10.netty.n4.syncInLoop", Predictions.N4_SYNC_IN_LOOP, NettyScenarios.n4(loop), "O4,R5").verify();
    }

    @Test
    void quiz_sourceReading() {
        new Grader()
                .checkHashed("m10.netty.q.deadlockCheck", Predictions.Q_NETTY_DEADLOCK_CHECK,
                        "7c5cc3c8fe093bd73642d32e19f4addd35c9672f840bf12e2a926417ca7dee09", "O4")
                .checkHashed("m10.netty.q.mayInterrupt", Predictions.Q_NETTY_MAY_INTERRUPT,
                        "9390298f3fb0c5b160498935d79cb139aef28e1c47358b4bbba61862b9c26e59", "R10,O3")
                .verify();
    }

    /** 리스너가 언제·어느 스레드에서 실행되든 기록될 때까지 잠깐 기다린다. */
    private void awaitMark(String step) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!rec.has(step) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
