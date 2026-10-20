# 로봇청소기 수리 진단 Agent — Spring 백엔드

기준 DB는 [docs/db/schema.sql](docs/db/schema.sql)이며 전체 **49개 API**를 이에 맞췄다. 업무 ID는 bigint/JSON 문자열이고 Kafka는 schemaVersion=2 및 v2 토픽을 사용한다. DB 변경은 [추가·변경 내역](docs/db/CHANGES.md), API별 대조는 [전체 점검표](docs/db/API_AUDIT.md)에 있다. 기존 DB는 Flyway V4/V5로 원본을 보존하며 이관한다.

접수 원본 저장 → Kafka 진단 → 추가 사진/재개 → Spring 재고 조회·발주 → 기사 리포트·본사 코멘트·확정 통계를 구현한다. Java 21·Spring Boot 4.1.1·Gradle은 기존 프로젝트 버전을 유지했다. PostgreSQL 17/Flyway, Spring Security, Spring Data JPA+JDBC, Spring Kafka를 사용한다. 프런트·LangGraph·분석 모델·RAG·업무 MCP 서버는 다른 팀 담당이다.

## 실행

필수: JDK 21, Docker Desktop/Engine, Python 3(시연 스크립트만). 처음 의존성·이미지 다운로드에는 네트워크가 필요하다.

```bash
cp -n .env.example .env
# 새로 복사한 .env의 DB_PASSWORD, INTERNAL_SERVICE_TOKEN(32자 이상), DEV_PASSWORD(12자 이상)를 변경
# 기존 .env가 있으면 현재 DB와 일치하는 값을 유지
set -a
source .env
set +a
docker compose up -d --wait
./gradlew bootRun
```

이번 구현 검증에서는 임의 생성한 로컬 자격증명을 Git에서 제외된 `.env`(권한 600)에 저장했다. 해당 파일이 있으면 그대로 사용할 수 있으며 실제 값은 로그/문서에 노출하지 않는다.

Spring은 8080, PostgreSQL은 localhost:5432, Kafka는 localhost:9092. Compose 포트는 loopback에만 bind한다. DB volume은 재기동 후 유지된다. seed는 dev 프로필에서만 초기 생성하며 기존 계정 비밀번호·재고를 재설정하지 않는다. 개발 비밀번호를 바꾼 경우 기존 seed 계정 비밀번호가 자동 변경되는 것은 아니다.

다른 터미널에서도 `.env`를 읽고 전체 시연을 실행한다:

```bash
set -a
source .env
set +a
python3 scripts/demo.py
```

개발 계정은 `tech1`(Seoul), `tech2`(Busan), `quality`(QUALITY_MANAGER). 비밀번호는 모두 최초 seed 시 `DEV_PASSWORD` 값이며 저장은 BCrypt(cost=12)다. 저장소에 실제 비밀번호는 없다. 제품 ROBOT-SEOUL-001/ROBOT-BUSAN-001, wheel motor/lidar, 공장·lot·장착 이력·창고·재고와 RECEIVED/MANUAL_REVIEW/DIAGNOSED 예시 접수를 seed한다. 예시 접수는 상태 표시용이고, 실제 시연 접수는 파일과 함께 새로 생성한다.

주요 fixture bigint ID:

| 항목 | bigint ID |
|---|---|
| Seoul 제품 | 1 |
| 모터 부품 | 1 |
| Seoul 장착 모터 | 1 |
| 중앙 창고 | 1 |
| 공급 공장 | 1 |

운영 프로필은 `SPRING_PROFILES_ACTIVE`에서 dev를 제거한다. mock과 seed가 로드되지 않고 HttpBusinessMcp를 사용한다. DB_PASSWORD/INTERNAL_SERVICE_TOKEN은 필수다. 외부 MCP는 MCP_URL/MCP_TOKEN으로 설정한다. `./gradlew bootJar` 후 `java -jar build/libs/backend-0.0.1-SNAPSHOT.jar`로 실행 가능하다. 실제 배포는 수행하지 않았다.

## 프런트 연결

[REST 계약과 인증/SSE 예시](docs/API.md), [OpenAPI 3.1](docs/openapi.json). OpenAPI는 Swagger Editor/Postman 등에 import 가능하며 런타임 Swagger UI는 포함하지 않는다. 요청 DTO는 camelCase, 조회 응답은 OpenAPI에 정의한 snake_case다.

로그인 전 `/api/auth/csrf` → form login → CSRF 재조회. 모든 변경 요청에 X-CSRF-TOKEN, fetch에 credentials:'include'. 로그인·SSE는 HttpOnly 세션 쿠키를 사용하며 토큰을 URL에 넣지 않는다. 개발 origin은 FRONTEND_ORIGIN 하나만 허용한다. 로컬에서도 localhost와 127.0.0.1 호스트를 혼용하지 않는다. 운영은 같은 사이트 reverse proxy/HTTPS를 사용한다.

```javascript
const events = new EventSource(`${base}/api/repairs/${repairId}/events`, {withCredentials:true});
events.addEventListener('PHOTO_REQUESTED', e => {
  const request = JSON.parse(e.data);
  // requestId로 Spring에 파일 저장 후 /diagnosis/resume 호출
});
events.addEventListener('DIAGNOSIS_COMPLETED', () => refreshRepair());
```

SSE는 커밋된 DB 이벤트를 1초 간격으로 전달하고 Last-Event-ID 재연결을 재생한다. 15초 heartbeat·30분 timeout. 같은 DB를 사용하는 여러 인스턴스가 각자의 SSE 연결에 polling 전달한다. 인메모리 세션 때문에 로드밸런서는 sticky session이 필요하며 파일은 공유 영속 저장소가 필요하다.

## AI·MCP 연결

[통합 계약](docs/INTEGRATION.md), [Kafka JSON Schema](docs/kafka-event.schema.json), [메시지 예시](docs/kafka-examples.json), [ERD](docs/ERD.md).

진단 요청 diagnosis.commands.v2, 결과 diagnosis.events.v2, taskId key, 3 partitions. Spring 결과 group=repair-results-v2. 결과 retry-1000/2000/4000 및 .dlt 토픽을 사용한다. 총 4회 시도 후 DLT, 영구 오류는 즉시 DLT, 유효한 현재 실행만 실패 확정. 사진 대기는 오류 재시도가 아니다.

실제 AI팀 연결 시 `./gradlew bootRun --args='--app.mock-ai.enabled=false'`로 dev AI mock을 끄고 LangGraph group=langgraph-diagnosis-v2을 연결한다. 접수 원본은 Spring에 저장된 제품 snapshot·증상·복수 에러·firmware·user_input이다. 첨부는 ID와 인증된 `/internal/attachments/{id}` 경로로 전달한다. AI/MCP 서비스에 base URL과 INTERNAL_SERVICE_TOKEN을 별도 주입한다. HTTP 진단 콜백은 제공하지 않고 Kafka 결과 소비 경로 하나를 사용한다.

MCP 호출은 명시적 JSON-RPC tool 호출이며 LLM을 거치지 않는다. stateless JSON tool gateway 계약과 structuredContent 응답을 지원한다. stateful MCP handshake/SSE transport는 외부 팀 gateway나 adapter 확장이 필요하다. 내부 재고 API는 Spring DB만 읽어서 순환 호출이 없다. AI SUCCEEDED와 procurement FAILED는 공존할 수 있고 조달 retry API를 제공한다. 실제 제조사 주문·결제·공장 시스템은 연결하지 않는다.

## 시연 순서

`python3 scripts/demo.py`가 실제 HTTP와 Kafka로 다음을 검증한다. 같은 계정의 쿠키/CSRF를 사용하며 비밀번호나 서비스 토큰을 출력하지 않는다.

1. `userInput.scenario=complete`: 접수+사진 DB 저장, 202, outbox 발행, 진행/도구/완료 이벤트.
2. 진단 성공 후 별도 Spring worker가 재고 조회·발주 제안 저장. quality 승인으로 예약, tech1 출고로 현재고 차감.
3. tech1 초안 → 리포트 revision 1 확정 → quality 코멘트 → tech1 수정 초안 → revision 2 확정 → 이전 revision·코멘트 보존.
4. `scenario=photo`: 추가 사진 1회 요청 → Spring 업로드 → 같은 task 재개 → 성공. 같은 submissionKey 재전송도 검증.
5. `scenario=manual`: 추가 사진 3회 제출 → 진단 FAILED, 접수 MANUAL_REVIEW. 서버 재시도 횟수는 별도.
6. 대시보드 AREA/PART/CENTER/FACTORY/REGION/PERIOD 조회. 초안 제외와 최신 확정 버전의 중복 없는 통계 갱신을 검증하고 SSE Last-Event-ID 재생도 확인한다.

Mock AI는 dev에서만 고정된 Kafka 이벤트를 내보내며 모델을 구현하지 않는다. MockBusinessMcp는 dev/test에서만 fixture 데이터를 조회한다. 운영 외부 호출 실패는 성공으로 위장하지 않는다.

## 검증

```bash
./gradlew test bootJar
# macOS Docker Desktop에서 Testcontainers가 socket을 찾지 못하는 경우:
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew test bootJar
```

테스트는 별도의 PostgreSQL/Kafka Testcontainers를 시작한다. Compose DB의 수리 접수를 지우지 않는다. Docker가 없으면 테스트가 실패하며 통과로 생략하지 않는다. 결과는 `build/reports/tests/test/index.html`, XML은 `build/test-results/test`.

2026-10-20 기준 DB 정렬 후 검증 결과: PostgreSQL 17·Kafka 4.1.1 Testcontainers 통합 테스트 **22개 통과**. 기존 데이터 보존 이관, 기준 schema.sql과 전체 컬럼·제약·인덱스 일치, OpenAPI 전체 경로 일치도 검사했다. 실제 Compose PostgreSQL Flyway 적용·애플리케이션 기동과 `scripts/demo.py` 전체 HTTP/Kafka 시나리오 통과. 빌드/테스트의 상세 범위는 [검증 기록](docs/VERIFICATION.md)을 참고한다.

검증한 핵심은 센터·역할 차단, 로그인/CSRF/서비스 인증, 저장 실패 시 outbox 없음, 기존 task 반환, 결과 eventId 멱등 처리·낮은 sequence·이전 실행 차단, 사진 3회·중복 재개·타 접수 사진 거절, Kafka 4회 재시도/DLT, 조달 실패 시 진단 유지, 동시 예약 초과 방지·취소/출고, 리포트 동시 수정 409·코멘트 유지·확정 통계 교체, SSE DB 재생이다.

## 운영 범위와 제한

파일 adapter는 로컬 UUID 파일 저장이다. rollback 시 삭제를 시도하고 DB에 메타데이터를 커밋한다. DB commit과 파일시스템은 분산 transaction이 아니므로 프로세스 강제 종료/디스크 오류 시 참조 없는 파일이 남을 수 있다. 접수 데이터와 파일을 함께 백업하고 orphan 정리는 DB object_key 대조 및 보존기간 확인 후 수행해야 한다. 악성코드 검사·백신·이미지 디코딩은 포함하지 않으며 확장 adapter 경계에 추가할 수 있다.

SSE 이력·audit·outbox·진단 원문은 현재 자동 삭제하지 않는다. DLT 운영 감시/재처리 승인 절차, 데이터 보존기간·PII 정책, 운영 사용자 provisioning, Kafka SASL/SSL·고가용성·rate limit, 실제 MCP/AI팀 종단간 연결 검증은 운영 환경에서 준비해야 한다. 외부 서비스가 죽었을 때 무한 대기하는 동기 접수 흐름은 없지만, 실제 AI가 명령을 영구 분실한 경우의 진단 deadline/자동 취소 정책은 팀 운영 계약으로 정해야 한다.

초안마다 revision이 추가되고 version이 증가한다. 마지막 확정 포인터만 집계하므로 수정 횟수가 접수 건수를 늘리지 않는다. 통계 날짜는 Asia/Seoul 확정일 [from,to), PERIOD는 월. 실제 장착 lot를 알 수 없으면 UNKNOWN. 분모가 없어 고장률은 계산하지 않는다. 리포트 수정만으로 발주를 변경하지 않는다.

컨테이너 종료: `docker compose stop` 또는 `docker compose down`(DB named volume 유지). 데이터 초기화를 위해 volume을 지우는 동작은 자동 실행하지 않는다. 커밋·푸시·배포는 수행하지 않았다.
