package async.m08.bugs;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;

/**
 * C01. 마이페이지 "최근 주문" 위젯 (Spring MVC 컨트롤러가 호출, 동기 메서드).
 *
 * <p>작성자 메모: "주문 하나 읽는 데 100ms 라 5건이면 500ms 가 나왔다. 주문마다 io 풀에서 읽도록 바꿔서 병렬화했다.
 * 컨트롤러는 동기 방식이라 결과를 기다리는 건 문제없다."
 *
 * <p><b>관측 조건</b>: 주문 5건, orderDb 지연 100ms, io 풀 스레드 8개. 호출 스레드는 서블릿 요청 스레드라 blocking 해도 된다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C01OrderHistory {

    private final FakeBlockingService orderDb;
    private final Executor io;

    public C01OrderHistory(FakeBlockingService orderDb, Executor io) {
        this.orderDb = orderDb;
        this.io = io;
    }

    public List<String> recentOrders(List<String> orderIds) {
        return orderIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> orderDb.call(id), io))
                .map(CompletableFuture::join)
                .map(row -> "summary(" + row + ")")
                .collect(Collectors.toList());
    }
}
