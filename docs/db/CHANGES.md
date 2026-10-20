# DB 추가·변경 내역

## 기존 구현에서 기준 설계로 수정한 부분

| 항목 | 이전 구현 | 현재 기준 |
|---|---|---|
| 업무 ID | UUID PK/FK | bigint GENERATED ALWAYS AS IDENTITY, API는 십진수 문자열 |
| 사용자 | username·센터·역할 | email/name/active/password_hash·센터·역할 |
| 제품 | model 문자열·serial | product_model/model_part, product.model_id/serial_number/manufactured_on |
| 부품·공급 공장 | part.factory_id 단일 관계 | part_number/category, part_supplier N:M·lead_days |
| 생산 lot·장착 | code·제품/부품·기간 | lot_number/manufactured_on, slot_code/component_serial, 활성 슬롯 UNIQUE·기간 CHECK·lot/part 복합 FK |
| 접수 | repair | repair_case: case_number/technician_id/symptom/firmware_version/version/closed_at |
| 진단 | diagnosis_task | diagnosis_job: case_id/request_key/workflow_version/checkpoint_ref/retry_count/photo_request_count/finished_at/version |
| 추가 사진 | UUID 요청·fulfilled_at | photo_request: bigint ID/job_id/round_no/instruction/target/status/submitted_at |
| 첨부 | repair_id/size/created_by | case_id/byte_size/uploader_id/kind/target 및 문서의 복합 UNIQUE |
| AI 실행·결과 | tool_execution + 통합 JSON만 | agent_execution + diagnosis_candidate + diagnosis_evidence + 별도 권장 부품 |
| 리포트·고장 | report/report_fault | repair_report/confirmed_fault, product/diagnosis 소속 복합 FK, outcome/ai_assessment |
| 발주 | PROPOSED/SHIPPED, order_item | PENDING/ORDERED/RECEIVED, purchase_order_item, 요청 키·결정 정보·version·공급처 복합 FK |
| 이벤트 | outbox와 sse_event 분리 | outbox_event가 Kafka 발행과 SSE 재생을 구분해 저장 |
| 감사 | actor/resource_id/details | actor_user_id/actor_service/entity_type/entity_id/request_key/detail |
| 통계 | 코드 내부 집계 | 문서의 fault_dashboard 뷰 복원, 최신 확정 revision 반영 |

## 원본 docs/db v1에 추가한 테이블: 7개

원본 25개 테이블은 유지하며 총 32개 업무/보조 테이블이다. Flyway 이력과 legacy_v3 백업 테이블은 이 수에 포함하지 않는다.

| 추가 테이블 | 추가 이유 |
|---|---|
| repair_report_revision | 기사 수정 이력, 초안/확정 분리, optimistic version과 최신 확정 포인터 |
| report_comment | 본사 코멘트를 기사 본문/수정과 독립 보존, revision FK·멱등 키 |
| received_event | Kafka eventId 중복 제거와 수신 처리 이력 |
| diagnosis_recommended_part | 최종 통합 진단의 복수 권장 부품을 후보 단일 part_id와 분리 |
| procurement_job | 진단 종료 후 Spring 조달 작업의 durable claim·재시도 |
| stock_snapshot | 부품별 재고·창고·공장 조회 결과 원본 보존 |
| legacy_id_map | 기존 UUID → bigint 대응표, 데이터 이관 검증·클라이언트 전환 지원 |

## 원본 docs/db v1에 추가·변경한 컬럼/제약

- product.information JSONB: 기존 구조화 제품 원본 보존.
- repair_case.user_input/product_snapshot/procurement_status/correlation_id: 기사 JSON·제품 snapshot·조달 상태·추적 키 분리.
- diagnosis_job.input_revision/execution_id/last_sequence/stage/result/updated_at: Kafka 실행 fencing·순서·통합 원본 보존.
- photo_request.request_key(UUID UNIQUE)/submission_key: AI 요청과 DB 요청 ID 구분, 사진 제출 멱등성.
- attachment.original_name 및 byte_size 최대 10MiB CHECK: 안전한 다운로드 표시·크기 제한.
- agent_execution.event_id/execution_ref/tool_version: Kafka 수신 이력 FK·실행 ID·도구 버전.
- diagnosis_evidence.details JSONB: annotation/매뉴얼 인용 등 도구 근거 원문 보존.
- repair_report.version/latest_revision/confirmed_revision/created_at. confirmed_at의 NOT NULL·자동 확정 default를 해제해 초안 허용. 확정 revision 복합 FK 추가. 기존 outcome/ai_assessment/comment/action_taken은 현재 확정 본문(최초 확정 전에는 최초 초안)을 반영하는 header다.
- confirmed_fault.revision/ordinal/part_id/factory_id/factory_name/production_region: revision별 고장·실제 lot 생산지 snapshot. revision 복합 FK와 UNIQUE(report_id,revision,ordinal) 추가.
- purchase_order_item.source: warehouse_id에서 계산하는 generated column, 외부 입력으로 저장하지 않음.
- outbox_event.job_id/topic/next_attempt_at/lease_until: Kafka 작업 연결·SSE 구분·발행 claim/backoff.
- fault_dashboard는 report.confirmed_revision에 연결한 고장만 집계하며 Asia/Seoul 월·UNKNOWN 생산지를 유지. 지역은 고장 기록 시 snapshot으로 보존한다.
- 추가 인덱스: 본사 코멘트 조회, 미완료 조달, 권장 부품 조회.

## 마이그레이션과 보존

[V4](../../src/main/resources/db/migration/V4__align_documented_schema.sql): V1~V3의 모든 테이블을 legacy_v3로 이동 후 기준 32개 테이블·뷰·제약 생성. [V5](../../src/main/resources/db/migration/V5__preserve_legacy_data.sql): FK 관계를 대응표로 옮기고 원문 입력·제품 정보·AI 결과·파일 object_key·리포트 revision·코멘트·재고/예약·발주·이력을 복사한다. 이전 UUID는 legacy_v3와 legacy_id_map에 보존한다. 원본 테이블/파일을 삭제하지 않는다.

기존 자료에 없는 공장 주소·국가·센터 코드·부품 코드 등은 UNKNOWN/LEGACY-*로 표시하며 실제 값으로 추정하지 않는다. 예전 고장 영역 HW는 확정 고장 테이블에서 OTHER로 옮기며 원래 리포트 JSON은 보존한다. 이전 REPAIRED는 CLOSED, PROPOSED는 PENDING, SHIPPED는 ORDERED로 매핑한다. 원래 SSE id는 유지한다.

API 업무 ID가 UUID → 문자열 bigint로 바뀌었으므로 이전 캐시/라우트 ID는 새 조회 결과로 갱신해야 한다. Kafka는 혼합 소비를 피하려고 schemaVersion=2, diagnosis.commands.v2/diagnosis.events.v2로 분리했다. 기존 v1 토픽은 삭제하지 않는다. 미발행 명령은 이관 시 v2 envelope로 옮기며 실행 중 AI는 전환 전에 중지하고 체크포인트의 업무 ID 대응을 확인해야 한다. executionId·requestKey·correlationId는 UUID를 유지한다.

DB 권한 계정과 외부 연동은 자동 변경하지 않는다. FastAPI/MCP에는 Spring 내부 조회 계약을 제공하며 업무 테이블 직접 쓰기는 허용하지 않는 구조다. 운영 전 마이그레이션 백업과 전환 시간 계획이 필요하다.
