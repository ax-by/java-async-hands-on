package async.m05;

/**
 * E3. 상품 상세 집계용 io 풀의 크기. DECISION.md 의 "executor" 칸에서 계산한 근거를 코드로 옮긴다.
 */
public final class ProductDetailPool {

    private ProductDetailPool() {
    }

    /**
     * E3. 피크 초당 요청 수가 {@code peakRps} 일 때 io 풀의 스레드 수.
     *
     * <ul>
     *   <li>요청 하나가 io 스레드를 점유하는 시간의 합은 {@link ProductBackends} 의 네 지연시간의 합이다(Little 의 법칙, R11).</li>
     *   <li>평소 지연의 변동, 그리고 타임아웃 후에도 계속 실행되는 호출(R10)을 위해 여유분을 둔다.
     *       단, 여유분은 계산값의 2배를 넘기지 않는다(다운스트림 DB 커넥션 한도를 생각하라).</li>
     *   <li>RPS 가 두 배면 스레드도 (거의) 두 배다.</li>
     * </ul>
     * 채점: {@code threadsFor(100)} 은 45 이상 90 이하, {@code threadsFor(200)} 은 90 이상 180 이하,
     * 그리고 그 크기의 풀로 100 RPS 부하를 받았을 때 모든 요청이 SLO 안에 성공해야 한다.
     */
    public static int threadsFor(int peakRps) {
        // TODO(E3): 필요 스레드 ≈ RPS × (요청당 blocking 시간의 합, 초) × 여유율 (R11)
        throw new UnsupportedOperationException("TODO");
    }
}
