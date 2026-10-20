package com.skala.backend.repair;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SchemaMigrationTests {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine");
    @Test void upgradesPopulatedUuidDatabaseWithoutLosingDataAndMatchesCanonicalSchema() throws Exception {
        var flyway=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("3").load();flyway.migrate();
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var stmt=connection.createStatement()) {
            stmt.execute("""
                INSERT INTO service_center VALUES ('10000000-0000-0000-0000-000000000001','center');
                INSERT INTO app_user VALUES ('80000000-0000-0000-0000-000000000001','legacy-tech','hash','TECHNICIAN','10000000-0000-0000-0000-000000000001');
                INSERT INTO factory VALUES ('20000000-0000-0000-0000-000000000001','factory','region','phone');
                INSERT INTO part VALUES ('30000000-0000-0000-0000-000000000001','Wheel motor','20000000-0000-0000-0000-000000000001');
                INSERT INTO product VALUES ('50000000-0000-0000-0000-000000000001','S-1','M1','{"original":"keep"}');
                INSERT INTO warehouse VALUES ('70000000-0000-0000-0000-000000000001','warehouse');
                INSERT INTO inventory VALUES ('70000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',10,2);
                INSERT INTO repair(id,center_id,product_id,created_by,symptoms,error_codes,firmware,user_input,product_snapshot,status) VALUES ('90000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001','80000000-0000-0000-0000-000000000001','원본 증상','["E1","E2"]','1.0','{"id":123,"free":"원문"}','{"information":{"original":"keep"}}','REPAIRED');
                INSERT INTO diagnosis_task(id,repair_id,status,execution_id,result) VALUES ('a0000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001','SUCCEEDED','b0000000-0000-0000-0000-000000000001','{"summary":"AI original","repairMethod":"replace","modelVersion":"v1","candidates":[{"rank":1,"confidence":0.8,"cause":"motor","explanation":"inspect"}],"recommendedParts":[{"partId":"30000000-0000-0000-0000-000000000001","quantity":2}],"evidence":[{"type":"MANUAL","citation":"manual"}]}');
                INSERT INTO report(id,repair_id,version,latest_revision) VALUES ('c0000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001',2,1);
                INSERT INTO report_revision VALUES ('c0000000-0000-0000-0000-000000000001',1,'{"actualCause":"motor","actualAction":"replace","technicianOpinion":"original","repairSuccessful":true,"aiAcceptance":"PARTIAL","faults":[{"area":"SW"}]}','80000000-0000-0000-0000-000000000001',now(),now());
                UPDATE report SET confirmed_revision=1;
                INSERT INTO report_fault(report_id,revision,ordinal,area) VALUES ('c0000000-0000-0000-0000-000000000001',1,1,'SW');
                INSERT INTO report_comment VALUES ('d0000000-0000-0000-0000-000000000001','c0000000-0000-0000-0000-000000000001',1,'80000000-0000-0000-0000-000000000001','retain comment','d0000000-0000-0000-0000-000000000002',now());
                INSERT INTO purchase_order(id,repair_id,status,created_by,decided_by,decided_at) VALUES ('e0000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001','APPROVED','80000000-0000-0000-0000-000000000001','80000000-0000-0000-0000-000000000001',now());
                INSERT INTO order_item VALUES ('e0000000-0000-0000-0000-000000000002','e0000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',2,'WAREHOUSE','70000000-0000-0000-0000-000000000001',NULL);
                INSERT INTO sse_event(id,repair_id,event_type,payload) VALUES (42,'90000000-0000-0000-0000-000000000001','REPORT_CONFIRMED','{"reportId":"c0000000-0000-0000-0000-000000000001"}');
                INSERT INTO outbox(id,task_id,topic,body) VALUES ('f0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000001','diagnosis.commands.v1','{"eventId":"f0000000-0000-0000-0000-000000000001","eventType":"DIAGNOSIS_REQUESTED","schemaVersion":1,"occurredAt":"2026-10-20T00:00:00Z","repairId":"90000000-0000-0000-0000-000000000001","taskId":"a0000000-0000-0000-0000-000000000001","correlationId":"90000000-0000-0000-0000-000000000001","executionId":"b0000000-0000-0000-0000-000000000001","inputRevision":1,"sequence":0,"payload":{"attachmentIds":[],"addedAttachmentIds":[],"round":0,"requestId":null,"checkpoint":null}}');
                """);
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var stmt=connection.createStatement()) {
            try(var r=stmt.executeQuery("SELECT c.id,c.symptom,c.user_input->>'free',r.confirmed_revision,i.on_hand,i.reserved FROM repair_case c JOIN repair_report r ON r.case_id=c.id CROSS JOIN inventory i")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(1);assertThat(r.getString(2)).isEqualTo("원본 증상");assertThat(r.getString(3)).isEqualTo("원문");assertThat(r.getInt(4)).isEqualTo(1);assertThat(r.getInt(5)).isEqualTo(10);assertThat(r.getInt(6)).isEqualTo(2);
            }
            for(String table:List.of("legacy_v3.repair","legacy_v3.report_comment","repair_case","report_comment","diagnosis_candidate","diagnosis_recommended_part","diagnosis_evidence","purchase_order","confirmed_fault")) {
                try(var r=stmt.executeQuery("SELECT count(*) FROM "+table)){r.next();assertThat(r.getInt(1)).as(table).isEqualTo(1);}
            }
            try(var r=stmt.executeQuery("SELECT payload->>'reportId' FROM outbox_event WHERE id=42")){r.next();assertThat(r.getString(1)).isEqualTo("1");}
            try(var r=stmt.executeQuery("SELECT payload->>'schemaVersion',payload->>'repairId',topic FROM outbox_event WHERE topic IS NOT NULL")){r.next();assertThat(r.getString(1)).isEqualTo("2");assertThat(r.getString(2)).isEqualTo("1");assertThat(r.getString(3)).isEqualTo("diagnosis.commands.v2");}
            stmt.execute("CREATE DATABASE canonical_fresh");
        }
        String canonical=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/canonical_fresh");
        try(var connection=DriverManager.getConnection(canonical,postgres.getUsername(),postgres.getPassword());var stmt=connection.createStatement()) {
            stmt.execute(Files.readString(Path.of("docs/db/schema.sql")));
        }
        assertThat(columns(postgres.getJdbcUrl())).isEqualTo(columns(canonical));
        String constraints="SELECT t.relname,c.conname,pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' AND t.relname<>'flyway_schema_history' ORDER BY 1,2";
        assertThat(definitions(postgres.getJdbcUrl(),constraints)).isEqualTo(definitions(canonical,constraints));
        String indexes="SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY 1,2";
        assertThat(definitions(postgres.getJdbcUrl(),indexes)).isEqualTo(definitions(canonical,indexes));
    }
    Set<String> definitions(String url,String query) throws Exception {
        var values=new TreeSet<String>();
        try(var c=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var stmt=c.createStatement();var rows=stmt.executeQuery(query)) {
            while(rows.next()) values.add(rows.getString(1)+":"+rows.getString(2)+":"+rows.getString(3));
        }
        return values;
    }
    Set<String> columns(String url) throws Exception {
        Set<String> result=new TreeSet<>();
        try(var connection=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var stmt=connection.createStatement();var rows=stmt.executeQuery("SELECT table_name,column_name,data_type,is_nullable,is_identity FROM information_schema.columns WHERE table_schema='public' AND table_name<>'flyway_schema_history' ORDER BY 1,2")) {
            while(rows.next()) result.add(rows.getString(1)+":"+rows.getString(2)+":"+rows.getString(3)+":"+rows.getString(4)+":"+rows.getString(5));
        }
        return result;
    }
}
