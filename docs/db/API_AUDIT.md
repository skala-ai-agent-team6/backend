# 전체 API / DB 대조 결과

기준: `docs/db/schema.sql`. 현재 등록된 **49개 연산**을 OpenAPI와 대조한다. 테스트 `openApiListsEveryApplicationRoute`가 Spring HandlerMapping(로그인/로그아웃 필터 경로 포함)과 OpenAPI의 경로·메서드가 정확히 같은지 검사한다. GET·변경 HTTP 테스트와 실제 HTTP SSE 시연을 함께 수행한다.

| 메서드 | API | 참조/변경 DB | 검증 |
|---|---|---|---|
| GET | `/api/auth/csrf` | app_user, service_center | 로그인/CSRF/비활성 계정/내 정보/로그아웃 |
| POST | `/api/auth/login` | app_user, service_center | 로그인/CSRF/비활성 계정/내 정보/로그아웃 |
| GET | `/api/auth/me` | app_user, service_center | 로그인/CSRF/비활성 계정/내 정보/로그아웃 |
| POST | `/api/auth/logout` | app_user, service_center | 로그인/CSRF/비활성 계정/내 정보/로그아웃 |
| GET | `/api/products` | product, product_model, model_part, part, product_component, part_lot, factory | serial/serialNumber·모델·실제 lot·문자열 ID |
| POST | `/api/repairs` | repair_case, app_user, service_center, product, attachment, diagnosis_job, outbox_event, audit_log | HTTP·접수/사진/outbox 원자성·센터·필터 |
| GET | `/api/repairs` | repair_case, app_user, service_center, product, attachment, diagnosis_job, outbox_event, audit_log | HTTP·접수/사진/outbox 원자성·센터·필터 |
| GET | `/api/repairs/{id}` | repair_case, app_user, service_center, product, attachment, diagnosis_job, outbox_event, audit_log | HTTP·접수/사진/outbox 원자성·센터·필터 |
| POST | `/api/repairs/{id}/diagnosis` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| GET | `/api/repairs/{id}/diagnosis` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| GET | `/api/repairs/{id}/diagnosis/tools` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| POST | `/api/repairs/{id}/attachments` | attachment, repair_case, photo_request, diagnosis_job, audit_log | HTTP 업/다운로드·크기/유형·센터/접수 소속 |
| GET | `/api/attachments/{id}` | attachment, repair_case, photo_request, diagnosis_job, audit_log | HTTP 업/다운로드·크기/유형·센터/접수 소속 |
| POST | `/api/repairs/{id}/diagnosis/resume` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| GET | `/api/repairs/{id}/events` | outbox_event(topic IS NULL), repair_case | 실제 SSE·Last-Event-ID·커밋 순서·rollback |
| GET | `/api/parts` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |
| GET | `/api/parts/{id}/inventory` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |
| GET | `/api/parts/{id}/factories` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |
| GET | `/api/repairs/{id}/procurement` | procurement_job, stock_snapshot, purchase_order, repair_case | HTTP 조회/재시도·조달 실패 분리 |
| POST | `/api/repairs/{id}/procurement/retry` | procurement_job, stock_snapshot, purchase_order, repair_case | HTTP 조회/재시도·조달 실패 분리 |
| POST | `/api/repairs/{id}/orders` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| GET | `/api/orders/{id}` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| POST | `/api/orders/{id}/approve` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| POST | `/api/orders/{id}/reject` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| POST | `/api/orders/{id}/cancel` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| POST | `/api/orders/{id}/ship` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| POST | `/api/orders/{id}/receive` | purchase_order, purchase_order_item, inventory, part_supplier, repair_case, outbox_event, audit_log | HTTP·권한·예약·취소·출고·수령·동시성 |
| GET | `/api/repairs/{id}/report` | repair_report, repair_report_revision, confirmed_fault, repair_case, product_component, model_part, part_lot, outbox_event, audit_log | HTTP·version 충돌·확정/초안·이력 |
| POST | `/api/repairs/{id}/report` | repair_report, repair_report_revision, confirmed_fault, repair_case, product_component, model_part, part_lot, outbox_event, audit_log | HTTP·version 충돌·확정/초안·이력 |
| PUT | `/api/repairs/{id}/report/draft` | repair_report, repair_report_revision, confirmed_fault, repair_case, product_component, model_part, part_lot, outbox_event, audit_log | HTTP·version 충돌·확정/초안·이력 |
| GET | `/api/reports/{id}` | repair_report, repair_report_revision, confirmed_fault, repair_case, product_component, model_part, part_lot, outbox_event, audit_log | HTTP·version 충돌·확정/초안·이력 |
| GET | `/api/reports/{id}/history` | repair_report, repair_report_revision, confirmed_fault, repair_case, product_component, model_part, part_lot, outbox_event, audit_log | HTTP·version 충돌·확정/초안·이력 |
| POST | `/api/reports/{id}/confirm` | repair_report, repair_report_revision, confirmed_fault, repair_case, product_component, model_part, part_lot, outbox_event, audit_log | HTTP·version 충돌·확정/초안·이력 |
| GET | `/api/reports/{id}/comments` | report_comment, repair_report_revision, repair_report, repair_case, outbox_event, audit_log | 역할·센터·revision·멱등 저장/조회 |
| POST | `/api/reports/{id}/comments` | report_comment, repair_report_revision, repair_report, repair_case, outbox_event, audit_log | 역할·센터·revision·멱등 저장/조회 |
| GET | `/api/dashboard` | fault_dashboard / repair_report, repair_report_revision, confirmed_fault, repair_case | 최신 확정·Asia/Seoul·DISTINCT 접수 |
| GET | `/internal/tasks/{id}/input` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| GET | `/internal/attachments/{id}` | attachment, repair_case, photo_request, diagnosis_job, audit_log | HTTP 업/다운로드·크기/유형·센터/접수 소속 |
| GET | `/internal/products` | product, product_model, model_part, part, product_component, part_lot, factory | serial/serialNumber·모델·실제 lot·문자열 ID |
| GET | `/internal/parts` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |
| GET | `/internal/parts/{id}/inventory` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |
| GET | `/internal/parts/{id}/factories` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |
| GET | `/internal/confirmed-cases` | repair_report, repair_report_revision | 서비스 인증·확정 사례 페이징 |
| POST | `/api/repairs/{id}/diagnoses` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| GET | `/api/tasks/{id}` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| POST | `/api/photo-requests/{id}/submit` | diagnosis_job, photo_request, attachment, agent_execution, diagnosis_candidate, diagnosis_evidence, diagnosis_recommended_part, received_event, outbox_event | HTTP·멱등성·3회 제한·후보/근거 정규화·Kafka |
| GET | `/api/dashboard/faults` | fault_dashboard / repair_report, repair_report_revision, confirmed_fault, repair_case | 최신 확정·Asia/Seoul·DISTINCT 접수 |
| GET | `/internal/fault-cases` | repair_report, repair_report_revision | 서비스 인증·확정 사례 페이징 |
| GET | `/internal/parts/{id}/availability` | part, part_supplier, factory, inventory, warehouse | 재고·예약·가용·공급 공장·내부 인증 |

## 원본 API 초안과 다르게 유지한 정책

- 로그인은 토큰 응답 대신 HttpOnly 세션+CSRF다. 브라우저 EventSource와 기존 인증 정책을 유지한다.
- `/internal/tasks/{id}/agent-results`, `/photo-requests`, `/complete` 콜백은 노출하지 않는다. Kafka eventId 멱등 처리 한 경로로 대체했다.
- `/internal/orders/proposals`는 노출하지 않는다. 진단 후 Spring worker가 제안을 저장하고 기사 수동 제안은 `/api/repairs/{id}/orders`를 사용한다.
- `/internal/confirmed-faults/publish`는 노출하지 않는다. 다른 팀 RAG 서버의 인덱싱을 대신 구현하지 않고 `/internal/confirmed-cases`와 `/internal/fault-cases`로 최신 확정 원본을 제공한다.
- 원본 `/api/repairs/{id}/report` POST는 version 있는 초안 저장+확정을 한 transaction으로 수행한다. 별도 초안/확정 API도 유지한다.
- 기준 API aliases인 diagnoses/tasks/photo submit/dashboard faults/availability/fault-cases를 추가하고 기존 현재 경로도 함께 유지했다.

실제 테스트 결과·외부 연동 미검증 범위는 [VERIFICATION.md](../VERIFICATION.md), DB 추가 내역은 [CHANGES.md](CHANGES.md)를 참고한다.
