package async.m10;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;

import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.m10.kafka.KafkaScenarios;
import async.m10.netty.NettyFutures;

/** E5 채점. 진짜 Netty 이벤트 루프와 loopback 소켓을 쓴다. */
@Timeout(20)
class NettyExercisesTest {

    MultiThreadIoEventLoopGroup group;
    EventLoop loop;

    @BeforeEach
    void setUp() {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        loop = group.next();
    }

    @AfterEach
    void tearDown() throws Exception {
        group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).await(2, TimeUnit.SECONDS);
    }

    private ChannelFuture connect(int port) {
        return new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    @Test
    void e5_realConnect_success() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            ChannelFuture connect = connect(server.getLocalPort());
            CompletableFuture<Void> cf = NettyFutures.toCompletableFuture(connect);
            cf.get(2, TimeUnit.SECONDS);
            assertThat(connect.channel().isActive()).isTrue();
            connect.channel().close().await(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void e5_realConnect_failure_isDeliveredUnwrapped() throws Exception {
        CompletableFuture<Void> cf = NettyFutures.toCompletableFuture(connect(KafkaScenarios.unusedLocalPort()));
        Throwable seen = cf.handle((v, ex) -> ex).get(2, TimeUnit.SECONDS);
        assertThat(seen).as("연결 거절은 cause() 그대로(ConnectException 계열) 전달돼야 한다. CompletionException 으로 감싸지 않는다 (R8)")
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void e5_dependentsRunOnTheEventLoop() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        String loopThread = loop.submit(() -> Thread.currentThread().getName()).get(2, TimeUnit.SECONDS);
        try (NamedPool completer = Pools.single("completer")) {
            Promise<String> promise = loop.newPromise();
            CompletableFuture<String> after = NettyFutures.toCompletableFuture(promise).thenApply(v -> {
                rec.mark("dependent");
                return v;
            });
            completer.execute(() -> promise.setSuccess("pong"));
            assertThat(after.get(2, TimeUnit.SECONDS)).isEqualTo("pong");
        }
        assertThat(rec.threadName("dependent"))
                .as("어댑터가 Netty 리스너 안에서 CF 를 완료시키면, non-async 의존 단계는 리스너가 실행되는 스레드에서 실행된다. 그 스레드를 Javadoc 에 적어 두라 (O1, R1)")
                .isEqualTo(loopThread);
    }

    @Test
    void e5_alreadyCompletedFuture() throws Exception {
        Promise<String> promise = loop.newPromise();
        promise.setSuccess("cached");
        assertThat(NettyFutures.toCompletableFuture(promise).get(2, TimeUnit.SECONDS)).isEqualTo("cached");
    }

    @Test
    void e5_nettyCancellation_becomesCfCancellation() throws Exception {
        Promise<String> promise = loop.newPromise();
        CompletableFuture<String> cf = NettyFutures.toCompletableFuture(promise);
        promise.cancel(false);
        cf.handle((v, ex) -> null).get(2, TimeUnit.SECONDS);
        assertThat(cf.isCancelled()).as("Netty 쪽이 취소되면 CF 도 isCancelled() 여야 한다 (Netty 의 cause() 는 CancellationException 계열이다)").isTrue();
    }

    @Test
    void e5_cfCancellation_cancelsNettyFuture() {
        Promise<String> promise = loop.newPromise();
        CompletableFuture<String> cf = NettyFutures.toCompletableFuture(promise);
        cf.cancel(true);
        assertThat(promise.isCancelled()).as("CF 를 cancel 하면 Netty future 도 cancel 해야 한다 (R10: 직접 연결하지 않으면 전파되지 않는다)").isTrue();
    }
}
