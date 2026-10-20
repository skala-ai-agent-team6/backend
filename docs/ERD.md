# 기준 ERD와 스키마

정식 원본은 [docs/db/README.md](db/README.md)의 ERD와 [docs/db/schema.sql](db/schema.sql)이다. DB 기준 구조와 코드가 따로 변하는 것을 막기 위해 이 문서는 해당 위치를 참조한다.

최초 DB 설계 25개 테이블을 유지하고 최신 요구사항으로 7개 테이블을 추가했다. 추가 컬럼·제약·데이터 이관은 [db/CHANGES.md](db/CHANGES.md)에 모두 기록했다. V1~V3 기존 migration은 보존하고 V4에서 기준 구조를 만들며 V5에서 기존 UUID 데이터와 관계를 bigint로 이관한다.

JPA는 app_user 인증 엔티티, 업무 서비스는 같은 DataSource의 JDBC repository를 사용한다. JpaTransactionManager를 통해 사용자 입력·업무 변경·감사·이벤트를 같은 transaction에 참여시킨다. ddl-auto=validate이며 Flyway가 스키마 변경을 관리한다.

자동 테스트는 Flyway로 이관한 DB와 schema.sql로 새로 만든 DB의 **전체 컬럼·nullability·identity·FK/CHECK/UNIQUE/PK·인덱스**가 동일한지 대조한다. 업무 규칙과 모든 API의 연결은 [db/API_AUDIT.md](db/API_AUDIT.md)를 참고한다.
