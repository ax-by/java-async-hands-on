# m07-vt 예측 시트

`VtScenarios.java`를 읽고 **실행하기 전에** 채운다. 근거에는 R 번호나 이 모듈의 VT 번호(README)를 쓴다.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| V01 | CF allOf: 예외가 나오기까지 (ms) | | | | |
| V01 | CF: interrupt 된 형제 작업 수 | | | | |
| V02 | StructuredTaskScope: 예외가 나오기까지 (ms) | | | | |
| V02 | StructuredTaskScope: interrupt 된 형제 작업 수 | | | | |
| V03 | 플랫폼 200개 풀, 100ms × 2,000 (ms) | | | | |
| V03 | 작업마다 가상 스레드, 100ms × 2,000 (ms) | | | | |
| V04 | VT executor 작업의 ThreadLocal | | | | |
| V04 | VT executor 작업의 InheritableThreadLocal | | | | |
| V05 | 다음 요청(t-200): 플랫폼 1개 풀의 ITL | | | | |
| V05 | 다음 요청(t-200): VT executor 의 ITL | | | | |
| V06 | ScopedValue: STS fork | | | | |
| V06 | ScopedValue: VT executor submit | | | | |
| V06 | ScopedValue: CF.supplyAsync | | | | |
| V07 | synchronized + 100ms, VT (4 × 코어)개, JDK 25 (ms) | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. V01과 V02는 같은 서비스, 같은 실패다. 운영에서 "상품 DB가 죽었을 때" 두 구현의 차이가 어떤 지표(응답시간, 스레드 점유, 다운스트림 부하)로 나타나는가? Java 17에서 CF로 V02에 가깝게 만들려면 무엇을 직접 해야 하는가? (R10)
2. V03를 보고 "VT를 쓰면 DB 커넥션 풀 크기도 늘려야 한다"는 주장을 평가하라. (VT1, R11)
3. V04·V05의 결과로, 가상 스레드 환경에서 MDC(traceId)를 어떻게 전달해야 하는지 두 가지 방법을 비교하라. (VT3, VT4, R12)
4. V07을 JDK 21에서 돌리면 결과가 어떻게 달라지는가? 그것을 근거로, Java 21 서비스에 VT를 켜기 전에 코드베이스에서 무엇을 찾아봐야 하는가? (VT5)

(여기에 작성)
