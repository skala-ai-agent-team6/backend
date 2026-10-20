# REST API v2

[OpenAPI 3.1 — 49개 연산](openapi.json), [모든 API/DB 대조표](db/API_AUDIT.md), [기준 스키마](db/schema.sql). DTO 요청은 camelCase이며 DB 조회 응답은 기준 DB의 snake_case다. 업무 ID는 bigint의 십진수 문자열(예: `"id":"1"`), 메시지/멱등 키는 UUID, 시각은 ISO 8601, revision/version/수량은 정수다. 필수·nullable·타입은 OpenAPI schema를 따른다. 이전 UUID 업무 ID를 새 URL에 사용할 수 없으며 새 조회 결과로 교체해야 한다.

## 인증·권한

`GET /api/auth/csrf` → 세션 쿠키+`{headerName,token}`. `POST /api/auth/login`은 form(username,password)과 X-CSRF-TOKEN을 받는다. username 값은 app_user.email 식별자다. 로그인 성공 후 CSRF를 다시 조회한다. 모든 POST/PUT와 logout에 토큰이 필요하다. `/api/auth/me`는 id/email/name/active/role/centerId와 이전 username alias를 반환한다. BCrypt 해시만 저장하고 active=false 계정을 로그인과 API 접근에서 차단한다.

fetch는 credentials:'include', SSE는 EventSource(url,{withCredentials:true}). 쿠키는 HttpOnly/SameSite=Lax, 운영 기본 Secure이며 local dev만 Secure를 해제한다. CORS는 FRONTEND_ORIGIN 하나만 허용한다. 개발 시 localhost와 127.0.0.1을 혼용하지 않는다. 일반 사용자 세션으로 `/internal/**`에 접근할 수 없고 별도 서비스 Bearer 인증이 필요하다. 내부 토큰을 브라우저·URL query에 넣지 않는다.

기사는 본인 센터 접수·첨부·리포트·발주만 접근한다. 본사는 전체 조회·코멘트 작성·승인/거절을 담당하고 기사 본문을 변경하지 않는다. 사용자/센터/역할은 인증 주체에서 가져온다.

## 제품·접수·진단

`GET /api/products?serialNumber=ROBOT-SEOUL-001`(serial alias 지원): product_model, 장착 product_component/part_lot/factory, model_part 호환 부품을 조회한다. 공급 공장은 part_supplier로 복수 지원하며 실제 생산지는 장착 lot에서만 추적한다.

`POST /api/repairs` multipart data(application/json)+files(1..10개):

```json
{"productId":"1","symptoms":"바퀴가 움직이지 않음","errorCodes":["E01","E02"],"firmware":"1.2.3","userInput":{"customerDescription":"기사 원문","scenario":"photo"}}
```

서버는 repair_case의 symptom/error_codes/firmware_version, user_input, product_snapshot을 분리 저장하고 첨부·diagnosis_job·outbox_event와 같은 transaction으로 커밋한다. 성공 후 202 `{repairId:"...",taskId:"...",status:"QUEUED"}`. 파일당 10MiB, 전체 50MiB, PNG/JPEG/PDF/UTF-8 text 지원. UUID object key에 저장하고 원본 파일명은 경로로 사용하지 않는다. MIME/magic을 확인하고 kind=IMAGE/LOG/DOCUMENT, target=GENERAL을 기록한다.

`GET /api/repairs`: page(0..100000), size(1..100), status/serial 필터, items/total/page/size. 상세 `/api/repairs/{id}`는 attachments/tasks/photoRequests/reports/orders를 포함한다. 접수 상태는 RECEIVED/DIAGNOSING/WAITING_INPUT/DIAGNOSED/MANUAL_REVIEW/REPAIRING/CLOSED/CANCELLED, 조달 상태는 별도 procurement_status다.

진단 요청 `POST /api/repairs/{id}/diagnosis` 또는 `/diagnoses`, 선택 Idempotency-Key(UUID). 같은 접수는 기존 job을 반환한다. 키가 다른 접수에 사용됐으면 409. 한 접수의 종료 후 재진단 API는 아직 제공하지 않는다. `GET /api/tasks/{id}`는 센터 검사 후 작업과 사진 요청을 반환한다. `GET /api/repairs/{id}/diagnosis`는 task 원본 결과와 정규화 candidates/evidence/recommendedParts를 반환한다. `/diagnosis/tools`는 agent_execution의 도구·모델·입출력·오류 이력을 반환한다.

## 추가 사진

AI가 보낸 UUID requestKey를 photo_request.request_key에 저장하고 Spring이 bigint 요청 id를 배정한다. 접수 상세 photoRequests와 SSE의 requestId는 DB bigint 문자열이다. 상태 OPEN→SUBMITTED, round_no는 1..3. 최초 사진은 횟수에서 제외한다.

1. `POST /api/repairs/{id}/attachments?requestId={id}` multipart file. 해당 접수의 OPEN 요청만 가능하다.
2. `POST /api/repairs/{id}/diagnosis/resume`:

```json
{"requestId":"1","submissionKey":"11111111-1111-4111-8111-111111111111","attachmentIds":["2"]}
```

또는 `POST /api/photo-requests/{id}/submit`에서 body의 requestId만 생략한다. 저장된 IMAGE 첨부가 같은 접수·같은 요청 소속인지 확인한다. 같은 키+같은 attachmentIds는 동일 revision을 반환하며 추가 outbox가 없다. 입력이 다른 키 재사용은 409다. 같은 job의 input_revision/execution_id를 갱신하고 누적 첨부와 이번 제출 ID를 구분해 Kafka resume 명령을 저장한다. 3차 추가 분석 후에도 불명확하면 FAILED/MANUAL_REVIEW이며 4차 요청을 생성하지 않는다.

`GET /api/attachments/{id}`는 센터 검사를 수행하고 다운로드를 강제한다. 내부 AI는 서비스 인증으로 `/internal/attachments/{id}`를 사용한다. 바이너리·장기 인증 토큰은 Kafka에 넣지 않는다.

## 리포트·코멘트

초안 `PUT /api/repairs/{id}/report/draft`:

```json
{"version":0,"content":{"actualCause":"모터 고장","faults":[{"area":"MOTOR","componentId":"1","partId":"1"}],"actualAction":"모터 교체","technicianOpinion":"재현 완료","repairSuccessful":true,"aiAcceptance":"PARTIAL","outcome":"FIXED"}}
```

faults.area는 PCB/MOTOR/SW/OTHER. 부품은 선택이며 지정하면 모델 호환성과 실제 장착 기간, PCB/MOTOR 분류를 검증한다. SW에는 componentId를 연결하지 않는다. 고장 없음은 faults=[], outcome=NO_FAULT_FOUND로 기록할 수 있다. aiAcceptance는 ACCEPTED/PARTIAL/REJECTED/NOT_AVAILABLE. outcome은 선택 FIXED/NOT_FIXED/NO_FAULT_FOUND이며 생략하면 repairSuccessful로 FIXED/NOT_FIXED를 결정한다.

최초 초안 version=0→1/revision=1. 확정 `POST /api/reports/{id}/confirm` `{version:1,revision:1}` → version=2. 수정 초안 version=2→3/revision=2에서도 confirmed_revision=1을 유지한다. 다음 확정 version=3/revision=2 → version=4/confirmed_revision=2. version 불일치·최신 이외 revision 확정은 409. `POST /api/repairs/{id}/report`는 Draft body로 저장+확정을 한 transaction에서 수행하는 기준 문서 경로다.

`repair_report`는 header와 현재 확정 결과, `repair_report_revision`은 작성자·내용·시각 이력이다. `confirmed_fault`도 revision별로 보존한다. GET `/api/repairs/{id}/report` 또는 `/api/reports/{id}`는 header/revisions/comments, `/history`는 revision 역순이다. 실제 원인·AI 원본·본사 코멘트는 별도다. 리포트 변경은 기존 발주를 바꾸지 않는다.

본사 전용 POST `/api/reports/{id}/comments` `{revision:1,body:"동일 lot 확인",requestKey:"UUID"}`. 내용 1..4000자, 공백만 거절. 리포트 내 키 재사용의 내용/작성자/revision이 다르면 409. 저장 성공을 기준으로 200이며 AI 호출은 없다. GET 같은 경로는 작성 시간순 누적 코멘트이고 기사도 본인 센터 내용을 조회한다.

## 재고·발주

GET `/api/parts`, `/api/parts/{id}/inventory`, `/factories`. 재고는 on_hand/reserved/available, 창고 위치·연락처를 반환한다. 공장은 part_supplier의 lead_days와 위치·phone을 포함한다. 내부 `/internal/parts/{id}/availability`는 part/inventory/factories를 함께 제공하며 outbound MCP 호출을 하지 않는다.

GET `/api/repairs/{id}/procurement`: 상태·snapshot·제안. 실패 시 기사 POST `/procurement/retry`. 진단 성공은 조달 실패로 바뀌지 않는다.

기사 제안 POST `/api/repairs/{id}/orders`:

```json
{"items":[{"partId":"1","quantity":2,"source":"WAREHOUSE","warehouseId":"1"}]}
```

FACTORY이면 factoryId 필수, warehouseId는 생략한다. part_supplier 관계를 검증하고 warehouse 품목에는 inventory 복합 FK가 있다. AI 권장 부품과 실제 발주 품목은 별도다. 품목 수정 API는 없고 잘못된 제안은 취소 후 새로 만든다.

POST `/api/orders/{id}/{approve|reject|cancel|ship|receive}` body `{reason:"사유"}`. approve/reject=본사, 나머지=센터 기사. DB 상태는 PENDING→APPROVED→ORDERED→RECEIVED, PENDING→REJECTED/CANCELLED, APPROVED→CANCELLED. approve 예약, ship 현재고·예약 차감, cancel 미출고 예약 복원, receive 로컬 수령 확인. ORDERED 이후 취소 불가. 같은 상태 재요청은 효과 중복 없음. version·결정자·결정 시각·decision_note·audit_log를 보존한다. 외부 주문/결제는 수행하지 않는다.

## 대시보드·SSE

GET `/api/dashboard` 또는 `/api/dashboard/faults`: 필수 from/to(YYYY-MM-DD), groupBy=PERIOD/PART/CENTER/FACTORY/REGION/AREA. Asia/Seoul 확정일 [from,to), PERIOD=월, 최신 확정 revision만 DISTINCT 접수 수로 집계한다. 초안 제외, 수정 확정 시 교체. 실제 장착 lot 생산지 snapshot을 사용하고 SW/미상은 UNKNOWN. 분모가 없어 고장률 없음.

GET `/api/repairs/{id}/events`: DB 커밋 후 outbox_event(topic IS NULL)의 이벤트를 1초 polling으로 전달한다. Kafka 명령은 SSE에 노출하지 않는다. bigint event ID, Last-Event-ID 재연결 재생, 15초 heartbeat, 30분 timeout, disconnect cleanup. 접수 내 커서만 허용하며 과거 이벤트를 자동 삭제하지 않는다.

```text
id: 42
event: PHOTO_REQUESTED
data: {"taskId":"1","status":"WAITING_INPUT","inputRevision":1,"requestId":"1","requestKey":"...UUID...","round":1,"bodyPart":"wheel","description":"하부 사진 요청"}

: heartbeat
```

각 Spring 인스턴스는 공통 DB를 polling한다. 세션은 인메모리이므로 다중 인스턴스에는 sticky session과 공유 파일 저장소가 필요하다. tool/model raw payload는 SSE로 보내지 않는다. UI는 named event 수신 후 GET으로 상세를 갱신한다.

## 오류·내부 조회

오류 `{code:"REPORT_VERSION",status:409}`. 400 INVALID_INPUT/INVALID_ID/FILE_TYPE/SERIAL_REQUIRED/SSE_CURSOR/FAULT_PART_CATEGORY, 401 UNAUTHENTICATED/INVALID_CREDENTIALS, 403 FORBIDDEN/FORBIDDEN_OR_CSRF, 404 NOT_FOUND, 409 REPORT_VERSION/ORDER_STATE/INSUFFICIENT_STOCK/IDEMPOTENCY_KEY_REUSED 등. DB/토큰/비밀번호 원문은 응답에 포함하지 않는다. 서비스 Bearer 실패는 body 없는 401이다.

내부 서비스 API는 `/internal/tasks/{id}/input`, `/internal/attachments/{id}`, `/internal/products?serialNumber=...`, `/internal/parts`, `/inventory`, `/factories`, `/availability`, `/internal/confirmed-cases`와 `/internal/fault-cases`다. 확정 사례는 page/size로 최신 확정 원본만 제공한다. 진단 HTTP callback이나 AI 발주 변경 API는 제공하지 않는다. Kafka가 유일한 진단 결과 쓰기 경로다.
