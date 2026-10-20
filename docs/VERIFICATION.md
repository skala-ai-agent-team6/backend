# 실제 검증 기록

일자: 2026-10-20 Asia/Seoul. 환경: macOS arm64, Java 21.0.12.1, Gradle 9.8.1, Spring Boot 4.1.1, PostgreSQL 17, Kafka 4.1.1. 저장소의 기존 Java/Spring 버전을 유지했다.

## 자동 통합 테스트

실행:

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew test bootJar
```

결과: **BUILD SUCCESSFUL**, 테스트 22, 실패 0, 오류 0, 생략 0. Testcontainers의 독립 PostgreSQL과 Kafka를 실제로 사용했다. Gradle 최종 전체 빌드 약 19초. 실행 JAR `build/libs/backend-0.0.1-SNAPSHOT.jar` 생성.

| 테스트 | 검증 내용 |
|---|---|
| loginCsrfAndServiceBoundary | 실제 BCrypt 로그인, CSRF 필수, 익명 차단, 세션만으로 내부 API 접근 불가, 서비스 Bearer 접근 |
| centersAndRolesAreEnforced | 타 센터 접수·파일 차단, 기사 승인·본사 코멘트 생성 차단, 본사 기사 작업 변경 차단 |
| intakeIsAtomicAndInputRemainsUnchanged | 파일 실패 시 접수/outbox rollback, 기사 원문 보존, 중복 진단 task/outbox 추가 없음 |
| duplicatesOrderAndOldExecutionsCannotOverwrite | eventId 중복, 낮은 sequence, 재개 전 이전 실행의 늦은 완료 차단 |
| photosResumeSameTaskAndOnlyThreeRounds | WAITING_INPUT 정상 처리·서버 retry 0, 동일 task/revision 증가, 중복 제출 멱등, 4차 요청 실패·수동 검토 |
| foreignPhotosAndSubmissionKeyReuseRejected | 타 접수/다른 요청 사진 제출과 멱등 키 입력 변경 거절 |
| kafkaResultDeliveryAndDuplicateIdempotency | 실제 Kafka 결과 소비, DB commit, 중복 result event 업무 1회 |
| kafkaBoundedRetriesAndDlt | 실제 retry-1000/2000/4000·DLT 경로, receive 총 4회, server_retries=3, 실패 확정 |
| outboxReplayAndDuplicateCommandsHaveSingleBusinessEffect | outbox 재발행을 실제 Kafka에서 2회 수신, 안정 eventId의 Mock AI 결과 중복 제거, 재고 snapshot/발주 제안 1회 |
| inventoryFailurePreservesDiagnosisSuccess | MCP 호출 실패 시 procurement FAILED, AI SUCCEEDED 유지 |
| approvalReservationCancellationAndShipping | 승인 중복 예약 없음, 취소 예약 복원, 출고 현재고 차감, 출고 후 취소 거절 |
| concurrentApprovalsCannotOverReserve | 병렬 2승인(각7개/재고10개) 중 1개 성공, 예약7개 |
| concurrentReportVersionsOnlyOneWins | 동일 version 병렬 수정 중 1개만 성공 |
| draftHistoryCommentsAndConfirmedDashboard | 최초 초안 통계 제외, 기존 확정 유지, 수정 확정 교체, 중복 없는 집계, 이전 revision/본사 코멘트 유지·중복 코멘트 방지 |
| sseReplayIsCommittedAndOrdered | DB cursor 이후 재생, 이벤트 정렬 |
| sseIdsFollowCommitOrderForConcurrentWriters | 접수별 동시 작성 transaction의 커밋 순서에 따른 SSE ID 배정, 미커밋/rollback 이벤트 비노출 |

## 기준 DB 정렬 추가 검증

- `everyReadApiAndDocumentedAliasUsesCanonicalTables`: 모든 일반/내부 GET 경로, 문자열 bigint ID, 모델/lot/공급 공장/가용재고, 정규화 진단 결과 조회.
- `everyMutationApiUsesCanonicalTablesAndAuthorization`: HTTP multipart 접수·추가 사진, 진단/diagnoses·사진 submit aliases, 재개·재시도, 발주 제안·승인·거절·취소·출고·수령, 리포트 초안·확정·한 번에 저장/확정·본사 코멘트·로그아웃.
- `noFaultReportAndDisabledAccountFollowDocumentedPolicy`: NO_FAULT_FOUND/faults=[], NOT_AVAILABLE, 비활성 계정 차단, 부품만 생략하고 장착 ID만 지정한 경우도 모델 호환성 검사, 고장/부품 분류 검사.
- `openApiListsEveryApplicationRoute`: Spring 등록 경로와 OpenAPI의 **49개 연산** 전체 일치. 로그인/로그아웃은 Security filter 경로로 명시 포함.
- `taskLookupReturnsExactHistoricalJob`: taskId 조회가 같은 접수의 최신 다른 작업을 반환하지 않고 요청한 과거 job을 정확히 반환. 센터 차단 유지.
- `SchemaMigrationTests`: 데이터가 있는 V3 UUID DB를 V4/V5로 이관하고 원본·리포트/코멘트·예약·AI 후보/근거/권장 부품·SSE 커서와 미발행 명령을 검증. Flyway DB와 docs/db/schema.sql fresh DB의 **전체 컬럼·nullability·identity·FK/CHECK/UNIQUE/PK·인덱스**가 동일함을 검사.
- 임의 사용자 JSON의 `id`, 중첩 `partId` 숫자가 API ID 변환 과정에서 문자열로 바뀌지 않고 원문 그대로 저장되는 것도 회귀 검증했다.

## 기존 로컬 DB 이관 검증

기존 Compose DB에 V4/V5를 실제 적용했다. 비교는 새 시연 접수를 생성하기 전에 수행했다.

| 항목 | 이관 전 | 이관 후 |
|---|---:|---:|
| 접수 | 9 | 9 |
| 리포트 | 2 | 2 |
| 본사 코멘트 | 2 | 2 |
| 현재고 합계 | 8 | 8 |
| 예약 합계 | 0 | 0 |

Flyway 1~5 success=true. 원본 UUID 테이블은 legacy_v3에 보존하고 대응표 legacy_id_map을 생성했다. 초기 seed 접수를 이관 후 중복 추가하지 않았다. 실제 이관 검증 이후 최종 HTTP 시연으로 접수 3건(10/11/12), 리포트 1건, 코멘트 1건이 추가됐고 모터 1개를 로컬 출고했다. 최종 접수 12건·리포트 3건·코멘트 3건·현재고 합계 7개·예약 0개다.

## Compose 및 실행 JAR 시연

`docker compose up -d --wait`로 PostgreSQL·Kafka healthcheck 통과. 실제 DB에 Flyway V1/V2/V3/V4/V5 적용과 Hibernate validate를 확인했다. `java -jar build/libs/backend-0.0.1-SNAPSHOT.jar` 기동 성공 후 `python3 scripts/demo.py` 실행:

```text
complete: SUCCEEDED, photo rounds=0
procurement -> approval -> shipping -> report v1 -> HQ comment -> report v2: OK
photo: SUCCEEDED, photo rounds=1
manual: FAILED, photo rounds=3
Dashboard draft/confirmation update and SSE Last-Event-ID replay: OK
All HTTP/Kafka demonstration scenarios passed.
```

시연은 실제 HTTP 세션 로그인·CSRF·multipart 파일·Kafka command/result·사진 업로드/재개·제안/승인/출고·리포트/코멘트/통계·SSE 재연결을 사용했다. 진단 모델은 dev 전용 고정 이벤트 mock, 업무 MCP는 dev fixture adapter다. 최종 앱 테스트 프로세스는 종료했고 로컬 개발용 DB/Kafka 컨테이너·볼륨·fixture는 보존했다. `.env`의 로컬 임의 생성 자격증명으로 재사용할 수 있다.

## 문서·정적 검사

OpenAPI JSON parse, 모든 내부 $ref 존재 확인, 45 paths/49 operations. Kafka JSON Schema/메시지 예시 JSON parse 및 공통 및 이벤트별 required field와 v2 업무 ID 타입 검증. 환경에 Python jsonschema/OpenAPI validator가 설치되어 있지 않아 전문 validator에 의한 전체 스키마 적합성 검증은 수행하지 않았다. `git diff --check` 통과.

## 검증 한계

실제 LangGraph checkpoint·분석 모델·RAG·외부 업무 MCP 서버·Vue 구현은 다른 팀 범위이며 종단간 연결하지 않았다. stateless JSON tool gateway 계약의 production MCP adapter와 실제 팀 서버의 호환성은 미검증이다. 제조사 주문/결제 연동은 구현 범위에서 제외했다. 다중 Spring 프로세스 SSE/로드밸런서·세션 sticky routing, Kafka broker 장애 복구/HA·SASL, 대용량 부하·악성코드 검사·프로세스 강제 종료 중 파일 orphan 정리는 실환경 검증하지 않았다. 본 문서의 통과 범위는 위 자동 테스트와 로컬 시연에 한정한다.
