# m11. 졸업 과제: 도구 없이 설계하고, 쓰고, 리뷰한다

> 난이도 ★★★★★ · 관련 규칙 **R0~R13 전체** · [리뷰 체크리스트](../docs/review-checklist.md) 전체 · 문제 4개 · 채점 테스트 30개(`-Pgrade` 에서만)

## 이 과제가 확인하는 것

이 repo 의 목표 네 가지를 **도구 피드백 없이** 해낼 수 있는지 확인한다.

| 목표 | 문제 |
|---|---|
| 1. 상황을 보고 A / B / 쓰지 않음을 판단하고 숫자로 근거를 댄다 | P1, P2 의 DECISION.md |
| 2. 의도대로 동작하는 파이프라인을 처음부터 작성한다(90% 이상의 확신) | P1, P2 (설계 방식은 직접 판단한다) |
| 3. 남의 파이프라인의 잘못을 코드만 보고 찾고 증상을 말한다 | P3 (결함 여러 개 + 함정) |
| 4. CF 를 쓰는 오픈소스 코드를 읽고 올바르게 기여한다 | P4 (라이브러리 공개 API 계약) |

도메인은 앞 모듈과 다르다(여행 검색, 알림 게이트웨이, 해외 송금, 라이브러리). 익숙한 모양을 외워서 푸는 것이 아니라 규칙으로 푸는지 본다.

| 문제 | 폴더 | 작성할 것 |
|---|---|---|
| P1 여행 통합 검색 | [p1-travel-search/](p1-travel-search/PROBLEM.md) | DECISION.md, `p1/TravelSearchService.java` |
| P2 알림 발송 게이트웨이 | [p2-notification-gateway/](p2-notification-gateway/PROBLEM.md) | DECISION.md, `p2/NotificationGateway.java` (+ `send` javadoc) |
| P3 송금 견적 PR 리뷰 | [p3-code-review/](p3-code-review/PROBLEM.md) | REVIEW.md, `p3/ReviewFindings.java`, `p3/RemittanceQuoteService.java` |
| P4 geo-lookup 공개 API | [p4-library-api/](p4-library-api/PROBLEM.md) | DECISION.md, `p4/GeoClient.java` (+ 계약 javadoc) |

---

## 진행 규칙 (중요)

**도구 없이 쓴다.** 이 모듈의 평소 빌드는 스켈레톤만 컴파일하고 아무것도 실행하지 않는다.

```bash
mvn -pl m11-capstone -am test        # 컴파일만 된다. 채점 테스트는 포함되지 않는다
```

- 허용: 이 repo 의 `docs/`(추론 규칙, 판단 가이드, 체크리스트, 버전표), JDK javadoc, 컴파일러의 타입 오류.
- 금지: 작성 중인 코드를 실행·디버깅하기, `src/grading` 읽기, 앞 모듈의 `solutions/` 폴더 보기, main 메서드나 임시 테스트로 동작 확인하기.
- 시간 기준(권장): 문제당 DECISION 30분 + 코드 60분 + 자기 리뷰 20분.

### 문제마다 순서

1. **PROBLEM.md** 를 읽고 숫자(지연, SLO, 용량, 실패 정책)에 밑줄을 긋는다.
2. **DECISION.md**(P3 는 REVIEW.md)를 먼저 채운다. 코드를 쓰기 전에 executor 크기와 타임아웃 값이 정해져 있어야 한다.
   근거 품질은 [판단 가이드 3단계](../docs/decision-guide.md#3단계-근거의-품질-기준-자기-점검)의 "합격" 등급이어야 한다.
3. **코드**를 쓴다. 모든 람다 옆에 `// [실행 스레드] / [blocking 여부]` 를 단다.
4. **자기 리뷰**: 체크리스트를 위에서부터 훑고, [추적 표](../docs/reasoning-rules.md#파이프라인-손으로-추적하기-최종-훈련-형식)를 단계마다 채운다.
   "이 테스트가 무엇을 잴까"를 상상해 PROBLEM.md 의 요구사항 번호마다 "내 코드의 어느 줄이 이것을 보장하는가"를 적는다.

### 채점 (네 문제를 모두 끝낸 뒤 한 번)

```bash
mvn -pl m11-capstone -am verify -Pgrade
```

- 첫 채점 결과(통과/실패 테스트 목록)를 그대로 [journal](../journal/README.md) 에 옮긴다. 이것이 졸업 판정의 기준이다.
- 실패한 테스트마다 "어느 규칙(R 번호)을 어떻게 잘못 적용했는가"를 적은 뒤 고치고 다시 채점한다.
- 테스트 실패 메시지는 "무엇이 왜 틀렸는지와 관련 규칙"을 알려 준다. 테스트 코드를 읽지 말고 메시지와 규칙으로 고친다.
- 마지막으로 각 문제의 DECISION.md / REVIEW.md 를 `solutions/` 폴더의 같은 경로에 있는 모범 답안과 비교한다.

## 졸업 기준

| 항목 | 기준 |
|---|---|
| 첫 채점 | 채점 테스트 30개 중 **27개 이상 통과**(90%). P3 리뷰 요약(`p3_0`)은 결함 누락 0, 오탐 0 |
| 재채점 | journal 에 원인 규칙을 적은 뒤 **전부 통과** |
| 판단 근거 | P1·P2 DECISION.md 가 모범 답안의 결정과 같고, executor 크기·타임아웃·실패 정책을 숫자로 설명함 |
| 리뷰 | P3 REVIEW.md 의 각 코멘트에 원인 규칙·조건·증상·수정안이 모두 있음. 올바른 코드에 대한 오탐 없음 |
| 공개 계약 | P4 javadoc 만 읽고 사용자가 콜백 스레드·타임아웃 예외·취소·close 동작을 알 수 있음 |

기준에 못 미치면 journal 에서 가장 많이 틀린 규칙을 찾아 해당 모듈(m01~m09)을 다시 풀고, 2주 뒤 같은 과제를 백지에서 다시 한다.

## 채점이 확인하는 것 (요약)

정답 문자열이 아니라 **목적 달성 여부**를 측정한다: 소요시간(병렬이면 최댓값), 호출 스레드 반환 시간, 실행 스레드 이름(commonPool·이벤트 루프·JDK 타임아웃 스케줄러 금지 여부), BlockHound 위반, 이벤트 루프 lag, 동시 실행 수, 타임아웃 후 실제 작업의 interrupt·취소, 실패 정책별 결과, 컨텍스트 전달, 스레드 누수.
