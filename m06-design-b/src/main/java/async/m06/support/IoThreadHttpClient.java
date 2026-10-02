package async.m06.support;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import async.harness.BlockHoundSupport;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * 채점용 HttpClient. <b>학습자가 수정하지 않는다.</b>
 *
 * <p>JDK {@code HttpClient} 를 감싸서 Netty 기반 클라이언트(Reactor Netty, Lettuce, AWS SDK v2 async)처럼 동작하게 만든다.
 * <ul>
 *   <li>진짜 JDK HttpClient 로 실제 소켓 I/O 를 한다. 내부 실행기는 {@code http-worker} 풀(2개)이다.</li>
 *   <li>{@link #sendAsync} 가 준 future 는 <b>I/O 스레드 {@code nonblocking-io-1}</b> 이 완료시킨다.
 *       그래서 그 future 에 미완료 상태에서 붙인 non-async 콜백은 이 스레드에서 실행된다(R1).
 *       이 스레드는 1개뿐이고 BlockHound 감시 대상이다(이름이 {@code nonblocking-} 으로 시작).
 *       여기서 blocking 하면 위반이 기록되고, 그동안 다른 모든 응답 전달이 멈춘다.</li>
 *   <li>순수 JDK HttpClient 는 future 를 이와 다른 곳에서 완료시킨다(어디인지는 S01, S02 에서 예측한다).
 *       이 클래스는 "콜백이 I/O 스레드에서 실행되는 더 엄격한 클라이언트"를 재현하기 위한 것이다.</li>
 *   <li>{@link #send} (blocking) 호출 횟수, {@link #sendAsync} 호출 횟수, BodyHandler 가 호출된 횟수(= 응답 헤더를 실제로 받은 횟수)를 센다.</li>
 *   <li>반환한 future 의 취소 동작(cancel 으로 HTTP 교환이 중단되는지)은 내부 JDK HttpClient future 와 같다.</li>
 * </ul>
 */
public final class IoThreadHttpClient extends HttpClient implements AutoCloseable {

    /** sendAsync future 를 완료시키는 I/O 스레드 풀 이름. 스레드 이름은 {@code nonblocking-io-1}. */
    public static final String IO_THREAD_POOL = "nonblocking-io";

    private final NamedPool internal;
    private final NamedPool io;
    private final HttpClient delegate;
    private final AtomicInteger sendCalls = new AtomicInteger();
    private final AtomicInteger sendAsyncCalls = new AtomicInteger();
    private final AtomicInteger bodyHandlerCalls = new AtomicInteger();

    private IoThreadHttpClient() {
        BlockHoundSupport.installIfAvailable();
        this.internal = Pools.fixed("http-worker", 2);
        this.io = Pools.single(IO_THREAD_POOL);
        this.delegate = HttpClient.newBuilder()
                .executor(internal)
                .version(Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public static IoThreadHttpClient create() {
        return new IoThreadHttpClient();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler) {
        sendAsyncCalls.incrementAndGet();
        // whenCompleteAsync 는 성공/실패 모두 io 스레드에서 결과를 전달한다.
        return delegate.sendAsync(request, counting(handler)).whenCompleteAsync((r, t) -> { }, io);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler,
                                                            PushPromiseHandler<T> pushPromiseHandler) {
        sendAsyncCalls.incrementAndGet();
        return delegate.sendAsync(request, counting(handler), pushPromiseHandler).whenCompleteAsync((r, t) -> { }, io);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException, InterruptedException {
        sendCalls.incrementAndGet();
        return delegate.send(request, counting(handler));
    }

    private <T> BodyHandler<T> counting(BodyHandler<T> handler) {
        return info -> {
            bodyHandlerCalls.incrementAndGet();
            return handler.apply(info);
        };
    }

    /** blocking {@code send()} 가 호출된 횟수. B 설계라면 0 이어야 한다. */
    public int sendCalls() {
        return sendCalls.get();
    }

    public int sendAsyncCalls() {
        return sendAsyncCalls.get();
    }

    /**
     * BodyHandler 가 호출된 횟수 = 응답 헤더를 실제로 받아 본문 처리를 시작한 교환의 수.
     * 타임아웃/취소 후에도 이 값이 오르면 HTTP 교환이 중단되지 않고 끝까지 진행된 것이다.
     */
    public int bodyHandlerCalls() {
        return bodyHandlerCalls.get();
    }

    /** HttpClient 내부 실행기(http-worker). */
    public NamedPool internalPool() {
        return internal;
    }

    @Override
    public void close() {
        io.shutdownNow();
        internal.shutdownNow();
    }

    // ---- 나머지는 위임 ----

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }
}
