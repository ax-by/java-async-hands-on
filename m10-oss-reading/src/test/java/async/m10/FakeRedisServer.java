package async.m10;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import async.harness.Gate;
import async.harness.Sleep;

/**
 * Lettuce 실험용 최소 Redis 서버 (RESP2). 실제 Redis 가 없어도 Lettuce 가 진짜 TCP 연결로 접속한다.
 *
 * <ul>
 *   <li>지원 명령: PING, GET, SET, DEL, SELECT, CLIENT(무엇이든 +OK), QUIT. HELLO 는 "모르는 명령" 오류로 답한다(RESP2 서버 흉내).</li>
 *   <li>서버 쪽 지연: 키가 {@code slow:<ms>:} 로 시작하면 응답 전에 그만큼 sleep 한다. "원격 Redis 가 느린 것"을 흉내 낸 것이다.</li>
 *   <li>응답 보류: 키가 {@code held:} 로 시작하면 {@link #holdUntil(Gate)} 로 준 게이트가 열릴 때까지 응답하지 않는다
 *       ("응답이 오기 전에 콜백을 등록했다"를 결정적으로 보장한다).</li>
 *   <li>연결마다 스레드 하나가 명령을 <b>받은 순서대로</b> 처리한다(Redis 처럼 파이프라이닝 순서 보장).</li>
 * </ul>
 */
public final class FakeRedisServer implements AutoCloseable {

    private final ServerSocket server;
    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final Thread acceptor;
    private volatile Gate holdGate = new Gate();
    private volatile String holdPrefix = "held:";

    private FakeRedisServer(ServerSocket server) {
        this.server = server;
        this.acceptor = new Thread(this::acceptLoop, "fake-redis-acceptor");
        this.acceptor.setDaemon(true);
    }

    public static FakeRedisServer start() {
        try {
            FakeRedisServer s = new FakeRedisServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            s.acceptor.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public String uri() {
        return "redis://127.0.0.1:" + port();
    }

    public FakeRedisServer put(String key, String value) {
        data.put(key, value);
        return this;
    }

    /** {@code held:} 키의 응답을 이 게이트가 열릴 때까지 보류한다. */
    public FakeRedisServer holdUntil(Gate gate) {
        return holdUntil(gate, "held:");
    }

    /** keyPrefix 로 시작하는 키의 응답을 이 게이트가 열릴 때까지 보류한다. */
    public FakeRedisServer holdUntil(Gate gate, String keyPrefix) {
        this.holdGate = gate;
        this.holdPrefix = keyPrefix;
        return this;
    }

    /** 받은 명령 이름 목록 (대문자). 핸드셰이크 명령도 포함된다. */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    public int connectionCount() {
        return connections.get();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                clients.add(s);
                connections.incrementAndGet();
                Thread t = new Thread(() -> serve(s), "fake-redis-conn-" + connections.get());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (s; InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream()) {
            while (true) {
                List<String> cmd = readCommand(in);
                if (cmd == null) {
                    return;
                }
                String name = cmd.get(0).toUpperCase(java.util.Locale.ROOT);
                commands.add(name);
                out.write(reply(name, cmd).getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (name.equals("QUIT")) {
                    return;
                }
            }
        } catch (IOException e) {
            // 연결 종료
        }
    }

    private String reply(String name, List<String> cmd) {
        switch (name) {
            case "PING":
                return "+PONG\r\n";
            case "SELECT":
            case "CLIENT":
            case "QUIT":
                return "+OK\r\n";
            case "GET": {
                String key = cmd.get(1);
                delayFor(key);
                String v = data.get(key);
                return v == null ? "$-1\r\n" : bulk(v);
            }
            case "SET":
                delayFor(cmd.get(1));
                data.put(cmd.get(1), cmd.get(2));
                return "+OK\r\n";
            case "DEL":
                return ":" + (data.remove(cmd.get(1)) == null ? 0 : 1) + "\r\n";
            default:
                return "-ERR unknown command '" + name + "'\r\n";
        }
    }

    private void delayFor(String key) {
        if (key.startsWith(holdPrefix)) {
            holdGate.await();
        }
        if (key.startsWith("slow:")) {
            Sleep.ms(Long.parseLong(key.split(":")[1]));
        }
    }

    private static String bulk(String v) {
        byte[] b = v.getBytes(StandardCharsets.UTF_8);
        return "$" + b.length + "\r\n" + v + "\r\n";
    }

    /** RESP 배열(멀티 벌크) 하나를 읽는다. 스트림이 끝나면 null. */
    private static List<String> readCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        if (header.charAt(0) != '*') {
            return List.of(header.trim().split("\\s+")); // 인라인 명령
        }
        int n = Integer.parseInt(header.substring(1));
        List<String> parts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String len = readLine(in);
            int size = Integer.parseInt(len.substring(1));
            byte[] buf = in.readNBytes(size);
            in.readNBytes(2); // CRLF
            parts.add(new String(buf, StandardCharsets.UTF_8));
        }
        return parts;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read(); // '\n'
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 무시
        }
        for (Socket s : clients) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 무시
            }
        }
    }
}
