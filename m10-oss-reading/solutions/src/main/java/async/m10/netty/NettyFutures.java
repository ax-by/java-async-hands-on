package async.m10.netty;

import java.util.concurrent.CompletableFuture;

import io.netty.util.concurrent.Future;

/**
 * E5. Netty Future → CompletableFuture 어댑터 (기여 시뮬레이션: Netty 위에 CompletionStage API 를 얹는 클라이언트 라이브러리).
 *
 * <p>Lettuce({@code io.lettuce.core.internal.Futures}), AWS SDK({@code NettyUtils}), DataStax 드라이버가 모두 이런 어댑터를 갖고 있다.
 * 가이드 3장의 "어댑터 리뷰 포인트"를 먼저 읽어라.
 */
public final class NettyFutures {

    private NettyFutures() {
    }

    /**
     * Netty {@link Future}(ChannelFuture, Promise 등)를 CompletableFuture 로 바꾼다.
     * <ol>
     *   <li>성공 → {@code getNow()} 값으로 완료. 실패 → {@code cause()} <b>그대로</b> 완료(감싸지 않는다).</li>
     *   <li>Netty 쪽이 취소되면 → 반환된 future 도 <b>취소 상태</b>({@code isCancelled() == true})가 된다.</li>
     *   <li>반환된 future 를 cancel 하면 → Netty future 도 cancel 한다(예: 아직 끝나지 않은 connect 를 포기).</li>
     *   <li>이미 완료된 Netty future 도 올바르게 변환한다.</li>
     *   <li>Javadoc 에 적을 것: 반환된 future 에 미완료 상태에서 붙인 non-async 콜백은 어느 스레드에서 실행되는가(N1~N3 근거)?
     *       그렇다면 blocking 콜백은 어떻게 붙여야 하는가?</li>
     * </ol>
     */
    public static <T> CompletableFuture<T> toCompletableFuture(Future<T> nettyFuture) {
        // TODO(E5): addListener 안에서 isSuccess / isCancelled / cause 를 구분한다. 취소는 반대 방향도 연결한다 (O3, R8, R10)
        // SOLUTION-BEGIN
        CompletableFuture<T> target = new CompletableFuture<>();
        nettyFuture.addListener(f -> {
            if (f.isSuccess()) {
                @SuppressWarnings("unchecked")
                T value = (T) f.getNow();
                target.complete(value);
            } else if (f.isCancelled()) {
                target.cancel(false);
            } else {
                target.completeExceptionally(f.cause());
            }
        });
        target.whenComplete((v, ex) -> {
            if (target.isCancelled()) {
                nettyFuture.cancel(false);
            }
        });
        return target;
        // SOLUTION-END
    }
}
