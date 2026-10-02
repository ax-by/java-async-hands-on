# m07-vt 판단 실습: 환경 제약에 맞는 도구 고르기

[판단 가이드](../docs/decision-guide.md)의 질문 흐름(R0)을 먼저 적용하고, 그다음 **사용할 수 있는 도구**를 환경으로 거른다.
같은 기능을 세 환경에서 만든다고 하자.

> **기능**: 주문 상세 조회. 주문(JDBC, 80ms), 배송 상태(사내 HTTP API, 120ms), 쿠폰 적용 내역(JDBC, 60ms)은 서로 독립이다.
> 응답 SLO p99 300ms. 피크 400 RPS. DB 커넥션 풀 30개.

## 환경 1. Java 8을 지원해야 하는 사내 공통 라이브러리 (`order-client` SDK)

이 SDK의 공개 메서드 `OrderDetail getDetail(long id)`를 다른 팀이 호출한다. 호출하는 쪽은 Java 8 Spring MVC부터 Java 25 서비스까지 다양하다.

```markdown
## 결정
- 도구: CF / VT / StructuredTaskScope / 동기 순차
- 공개 API 모양:
- 한 줄 근거:

## 근거
- 사용할 수 없는 도구와 이유:
- executor 를 누가 소유하는가:
- 실패·타임아웃 정책:
```

## 환경 2. Java 17 Spring Boot 3 서비스 (Tomcat, 운영 중)

```markdown
## 결정
- 도구:
- 한 줄 근거:

## 실행 모델
- executor: 이름, 크기, 큐, 거절 정책, 크기 근거 (R11, Little 의 법칙)
- 컨트롤러: join / CF 반환 (근거: m07 S17, R0)
- 컨텍스트 전파 (R12):

## 실패 모델
- 전체 타임아웃:            부분 실패 정책:
- 타임아웃 후 남은 작업 (R10):
```

## 환경 3. Java 25 신규 서비스 (Spring Boot, VT 활성화 가능)

```markdown
## 결정
- 도구:
- 한 줄 근거:

## 실행 모델
- 요청 스레드:
- fan-out:
- 동시성 상한 (VT1):
- 컨텍스트 (VT3, VT4):

## 이 결정을 다시 볼 조건
- 예) preview API 변경, 라이브러리의 pinning (VT5), ...
```

(여기에 작성)
