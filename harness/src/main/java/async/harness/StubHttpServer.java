package async.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * 실제 네트워크 I/O 로 호출할 수 있는 로컬 HTTP 스텁 서버 (m06 B 설계에서 JDK HttpClient.sendAsync 의 상대).
 *
 * <pre>{@code
 * try (StubHttpServer server = StubHttpServer.start()) {
 *     server.route("/users/1", 100, 200, "{\"id\":1}");   // 100ms 후 200 응답
 *     URI uri = server.uri("/users/1");
 * }
 * }</pre>
 * 서버 쪽 지연은 "원격 서버가 느린 것"을 흉내 낸 것이므로 서버 스레드에서 sleep 해도 클라이언트 설계와 무관하다.
 */
public final class StubHttpServer implements AutoCloseable {

    private record Route(long latencyMillis, int status, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();

    private StubHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
            ExecutorService executor = Executors.newCachedThreadPool(Pools.namedFactory("stub-server"));
            StubHttpServer stub = new StubHttpServer(server, executor);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                stub.requests.incrementAndGet();
                int now = stub.inFlight.incrementAndGet();
                stub.maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    Route r = stub.routes.getOrDefault(exchange.getRequestURI().getPath(), new Route(0, 404, "not found"));
                    Sleep.ms(r.latencyMillis);
                    byte[] bytes = r.body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(r.status, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    stub.inFlight.decrementAndGet();
                    exchange.close();
                }
            });
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public StubHttpServer route(String path, long latencyMillis, int status, String body) {
        routes.put(path, new Route(latencyMillis, status, body));
        return this;
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public int requestCount() {
        return requests.get();
    }

    /** 서버가 동시에 처리한 요청의 최대 개수. 클라이언트가 실제로 병렬 요청했는지 확인한다. */
    public int maxConcurrentRequests() {
        return maxInFlight.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
