# Kafka·AI·업무 MCP 계약 v2

## 소유자와 호출 방향

Spring PostgreSQL이 접수·제품 모델/장착 lot·첨부 metadata·재고·발주·리포트·코멘트의 원본이다. 분석 모델·도구·LangGraph 체크포인트·RAG와 업무 MCP 서버는 다른 팀 담당이다.

- Vue → Spring 원본 저장 → diagnosis_job+outbox_event 원자적 커밋 → Kafka → LangGraph.
- LangGraph → Kafka 결과 이벤트 → Spring 상태+received_event+SSE outbox_event 커밋 → offset 처리 → Vue SSE.
- AI는 명령의 원본 또는 서비스 인증 `/internal/tasks/{id}/input`을 사용하고 `/internal/attachments/{id}`에서 파일을 조회한다.
- 진단 완료 후 Spring procurement worker → BusinessMcp adapter → 업무 MCP inventory.lookup. MCP는 Spring `/internal/parts/{id}/availability` 등의 조회를 사용할 수 있다. 해당 API는 DB만 읽으므로 순환 호출하지 않는다.
- 본사 승인 → Spring 재고 잠금·예약·로컬 주문 상태. LangGraph가 승인 대기 interrupt를 유지하지 않는다.
- RAG는 `/internal/confirmed-cases` 또는 `/internal/fault-cases`에서 최신 확정 리포트를 읽고 (report_id,confirmed_revision)으로 자체 인덱스를 갱신한다.

## v1에서 변경된 ID 계약

DB 문서 기준 bigint identity로 정렬했다. repairId/taskId/attachmentId/partId/requestId 등 업무 ID는 **십진수 문자열**이다. eventId/executionId/correlationId/requestKey/submissionKey는 UUID다. AI는 새 제품 조회 결과의 part.id를 사용해야 하며 예전 UUID partId를 결과로 반환할 수 없다.

AI PHOTO_REQUESTED는 UUID **requestKey**를 발행한다. Spring은 photo_request.request_key로 보존하고 별도의 bigint DB id를 생성한다. 프런트의 requestId는 이 DB id다. resume 명령은 requestId와 requestKey를 함께 보내므로 AI가 자기 checkpoint와 대응할 수 있다. Kafka envelope 버전은 2이고 토픽도 v2로 분리해 이전 UUID 메시지와 혼합 소비하지 않는다. 이전 v1 토픽을 자동 삭제하지 않는다.

[JSON Schema](kafka-event.schema.json), [메시지 예시](kafka-examples.json).

## 토픽·그룹

| 용도 | 토픽 / group |
|---|---|
| 명령 | diagnosis.commands.v2 / 실제 AI 권장 group langgraph-diagnosis-v2 |
| 결과 | diagnosis.events.v2 / Spring group repair-results-v2 |
| Spring 결과 재시도 | diagnosis.events.v2.retry-1000 / .retry-2000 / .retry-4000 |
| Spring 결과 DLT | diagnosis.events.v2.dlt |
| dev AI | development-mock-ai-v2, commands.v2.retry-1000/2000/4000 및 .dlt |

key는 언제나 taskId 문자열, 로컬 3 partitions/replication=1. 같은 task의 producer는 순서대로 발행한다. group 안에서 병렬화하고 유효 consumer 수는 partitions를 넘지 않는다. mock과 실제 AI를 다른 group으로 동시에 켜면 같은 명령을 모두 받으므로 실제 AI 연결 시 `app.mock-ai.enabled=false`로 mock을 끈다. 운영 토픽·ACL·SASL/SSL·복제 계수는 별도 환경 설정이다.

## envelope

모든 필드는 필수다.

| 필드 | 타입 | 설명 |
|---|---|---|
| eventId | UUID string | 재발행 시 유지하는 멱등 ID |
| eventType | string | 아래 정의된 명령/결과 |
| schemaVersion | integer=2 | ID 변경을 반영한 계약 |
| occurredAt | ISO 8601 UTC instant | 발행 시각, 순서 판단에는 사용하지 않음 |
| repairId/taskId | decimal string | 기존 접수·작업 |
| correlationId | UUID string | 접수 생성 시 Spring이 부여, AI는 echo |
| inputRevision | integer>=1 | 최초1, 사진 제출마다+1 |
| executionId | UUID string | 입력 revision의 실행 구분, Spring이 지정 |
| sequence | integer | 명령0, 결과 실행별1부터 엄격 증가 |
| payload | object | 타입별 원문 |

## 명령 payload

DIAGNOSIS_REQUESTED / DIAGNOSIS_RESUMED:

- repair: 저장한 repair_case 원본. product_snapshot에는 product/model/components/compatibleParts 구조가 있다. symptom/error_codes/firmware_version/user_input을 임의 요약하거나 변형하지 않는다.
- task: diagnosis_job metadata.
- attachments: 전체 `{id,request_id,kind,target,mime_type,byte_size,sha256,downloadPath}` 배열. id/request_id는 decimal string 또는 null이다.
- attachmentIds: 누적 decimal string[]; addedAttachmentIds: 이번 제출 decimal string[], 최초 빈 배열.
- requestId: 재개 대상 DB 요청 id 또는 최초 null.
- requestKey: AI 요청 UUID 또는 최초 null.
- round: integer 0..3, checkpoint: string|null. 기존 checkpoint로 같은 작업을 재개한다.

사진 경로는 인증된 Spring 내부 상대 경로다. AI에 Spring base URL과 INTERNAL_SERVICE_TOKEN을 별도 주입한다. 바이너리와 장기 토큰은 메시지/URL query에 넣지 않는다. 서비스 토큰은 전체 내부 조회 권한이므로 프런트에 전달하지 않는다.

## 결과 payload

| eventType | 필수 필드 |
|---|---|
| DIAGNOSIS_STARTED/DIAGNOSIS_PROGRESS | stage:string(nonblank) |
| TOOL_EXECUTED | tool:string, toolVersion:string. agentType=PCB/MOTOR/SW/RAG/BUSINESS, mcpServer/modelVersion/input/output 등 선택 metadata |
| PHOTO_REQUESTED | requestKey:UUID, round:integer1..3, bodyPart:string, description:string, checkpoint:string |
| DIAGNOSIS_COMPLETED | summary:string, modelVersion:string, repairMethod:string, candidates:Candidate[], recommendedParts:Part[], evidence:Evidence[] |
| DIAGNOSIS_FAILED | errorCode:string, 기타 상세는 선택 |

Candidate: `{rank:integer>=1,cause:string,confidence:number[0,1],explanation:string,partId?:decimal string,causeCode?:string,recommendedQuantity?:integer}`. 최소1개, rank 유일. confidence는 보정된 확률로 가정하지 않는다. Part: `{partId:decimal string,quantity:integer>=1}`, 기존 part 참조 필수. 부품이 불필요하면 빈 배열.

Evidence: `{type:string,attachmentId?:decimal string,candidateRank?:integer,annotation?:object,citation?:string,sourceUri?:string,sourceVersion?:string,excerpt?:string,...}`. 같은 접수 첨부만 가능하다. candidateRank 생략 시 첫 후보에 연결한다. 사진 좌표는 normalized x/y/width/height 0..1을 사용하도록 팀 계약을 둔다. 원문 JSON은 보존하면서 candidate/evidence/추천 부품을 DB 개별 테이블에도 저장한다. TOOL_EXECUTED는 agent_execution에 도구명·모델·버전·입출력을 기록한다.

사진은 최초 제외 최대3회. PHOTO_REQUESTED는 정상 처리 후 WAITING_INPUT이며 offset을 커밋한다. 새 resume 전까지 AI는 작업을 정지하고 추가 상태 이벤트를 보내면 안 된다. 3차 추가 사진 분석 후 불명확하면 errorCode=INCONCLUSIVE_AFTER_THIRD_PHOTO로 FAILED 이벤트를 보낸다. 4차 요청을 보내면 Spring도 FAILED/MANUAL_REVIEW로 제한한다. 사진 횟수와 서버 retry_count는 독립이다.

## 전달·재시도·SSE 보장

outbox_event.topic이 있는 행은 Kafka 명령, NULL인 행은 SSE 알림이다. publisher는 명령만 SKIP LOCKED로 claim(lease30초)하고 DB transaction 밖에서 publish한다. acks=all 확인 후 published_at을 기록한다. 실패 시 2..256초 backoff로 지속 재시도하며 broker 장애만으로 진단을 실패시키지 않는다. publish 성공 후 DB 기록 전 크래시는 중복이 가능하므로 eventId가 유지된다.

Spring 결과 consumer는 enable-auto-commit=false, ack-mode=RECORD. 업무 상태·received_event·SSE 이벤트를 한 transaction에 저장한 후 listener가 반환한다. 실패 시 DB rollback과 명시적 retry forward를 수행한다. 일시 오류는 원본 포함 총4회, 1/2/4초 backoff. ApiError 영구 오류는 즉시 DLT. Kafka가 자동으로 메시지를 큐 끝에 넣는다고 가정하지 않는다. retry framework는 forward publish를 확인하고 원래 offset을 진행한다.

Spring은 job 행 잠금으로 eventId 중복 제거, inputRevision/executionId/sequence/current state를 검증한다. 이전 실행·낮은 sequence·종료 후 상태 이벤트는 기록하되 상태를 덮어쓰지 않는다. 현재 실행의 늦은 도구 결과는 상태 변경 없이 도구 이력만 보존한다. retry 토픽은 원래 토픽과 순서를 공유하지 않으므로 sequence gap은 허용한다. AI는 PHOTO_REQUESTED 이후 resume 전까지 추가 이벤트를 보내지 않아 대기 요청 추월을 막아야 한다.

DLT는 유효한 현재 실행의 미처리 이벤트에 한해 실패·수동검토를 확정한다. 이미 성공했거나 최신 이벤트가 적용됐으면 실패로 바꾸지 않는다. 실제 추가 retry 횟수를 기록한다. 스키마가 완전히 잘못됐거나 알 수 없는 task이면 원문은 Kafka DLT에 남고 임의 DB 작업을 만들지 않는다. DLT DB 처리도 커밋되기 전 성공으로 처리하지 않는다.

LangGraph 팀도 durable inbox·checkpoint 저장 후 offset·동일 eventId 재전송·bounded retry/backoff·영구/상한초과 DLT·실패 이벤트 발행을 구현해야 한다. Spring은 AI측 commands DLT를 대신 소비하지 않는다. WAITING_INPUT은 오류 retry가 아니고, resume도 기존 job을 사용한다. 진단 deadline/AI 서비스 장기 장애의 운영 수동 조치는 팀 계약으로 정한다.

SSE는 커밋된 DB 알림을 각 인스턴스가 polling한다. 접수 행 잠금으로 같은 접수의 이벤트 ID 배정과 커밋 순서를 직렬화하고 Last-Event-ID 이후를 재생한다. 쿠키·접근권한·다중 인스턴스 제약은 [API.md](API.md)에 있다.

## 업무 MCP adapter

운영 HttpBusinessMcp는 MCP_URL에 stateless JSON-RPC tools/call을 전송한다. stateful MCP initialize/SSE transport를 요구하는 서버에는 별도 팀 gateway 또는 adapter 확장이 필요하다. 임의 MCP 서버 자동 호환을 주장하지 않는다. LLM은 사용하지 않는다.

```json
{"jsonrpc":"2.0","id":"...UUID...","method":"tools/call","params":{"name":"inventory.lookup","arguments":{"partIds":["1"]}}}
```

```json
{"jsonrpc":"2.0","id":"...UUID...","result":{"isError":false,"structuredContent":{"stocks":[{"partId":"1","warehouseId":"1","onHand":10,"reserved":0,"available":10}],"factories":[{"partId":"1","factoryId":"1","region":"Gyeonggi","contact":"031-000-0000","lead_days":3}]}}}
```

stocks/factories는 필수 배열. UUID RPC id와 업무 decimal string ID를 구분한다. tool은 조회만 하며 승인 시 Spring inventory를 다시 잠가 가용 수량을 검증한다. 충분한 단일 창고가 있으면 창고 조달, 아니면 part_supplier의 공장으로 제안한다. 수동 제안은 여러 창고/공장 품목을 만들 수 있다.

connect3초/read10초 timeout, DB transaction 밖 HTTP 호출, 성공 시 snapshot/제안/상태 저장. JSON-RPC error/isError/HTTP 오류/잘못된 참조는 procurement FAILED이며 AI SUCCEEDED를 유지한다. 자동 총3회·30초 간격 후 기사 retry API로 재시도 가능. dev/test에서만 MockBusinessMcp가 fixture 재고와 공급 공장을 조회한다. 실제 제조사·결제·공장 주문은 수행하지 않는다.
