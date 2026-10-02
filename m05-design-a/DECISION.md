# DECISION: 상품 상세 집계 API

> **코드를 쓰기 전에** 채운다. 양식은 [decision-guide.md](../docs/decision-guide.md)를 따른다. 근거에는 반드시 숫자를 넣는다.
> 채운 뒤 `ProductDetailAggregator`(E1, E2)와 `ProductDetailPool`(E3)을 구현하고, 실측 결과와 다르면 이 문서를 고친다.

## 주어진 조건

| 항목 | 값 |
|---|---|
| 서비스 | Java 17, Spring MVC(Tomcat), `GET /products/{id}` |
| 다운스트림 | product(JDBC, 80ms, **필수**), price(RestTemplate, 120ms, **필수**), stock(RestTemplate, 100ms, 선택 → "UNKNOWN"), reviews(JDBC, 150ms, 선택 → 빈 목록) |
| 현재 구현 | 순차 호출 `Scenarios.naiveSequential` |
| SLO | 평소 p99 < 200ms, 전체 하드 타임아웃 300ms |
| 실패 정책 | 필수 소스 실패 → 요청 실패(도메인 예외 `ProductDetailException`, 원래 예외를 cause로). 선택 소스 실패/지연 → 폴백 |
| 트래픽 | 피크 100 RPS |
| 그 밖에 | HikariCP 커넥션 풀은 기본값(`maximumPoolSize` 10)으로 운영 중 |

---

## 결정
- 목적: A / B / 쓰지 않음
- 한 줄 근거:

## 근거
- 독립적인 호출:
- 클라이언트 특성:
- 기대 효과 (숫자로):

## 실행 모델
- 각 단계의 실행 스레드:
- executor (이름, 크기, 큐, 거절 정책, 크기의 근거):
- blocking 지점과 그 지점이 실행되는 스레드:
- 다운스트림 한도 확인 (DB 커넥션):

## 실패 모델
- 전체 타임아웃:            부분 실패 정책:
- 소스별 타임아웃:          예외 unwrap 위치:
- 타임아웃 후 남은 작업 처리 (R10):

## 이 설계가 틀렸다면 어떤 증상이 보일까
-

## (실측 후) 예측과 달랐던 점
-

(여기에 작성)
