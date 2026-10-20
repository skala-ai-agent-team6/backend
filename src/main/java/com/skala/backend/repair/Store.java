package com.skala.backend.repair;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

@Repository
public class Store {
    final JdbcTemplate jdbc;
    public Store(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public int update(String sql,Object... args) {return jdbc.update(sql,args);}
    public Long insert(String sql,Object... args) {return Json.id(one(sql+" RETURNING id",args).get("id"));}
    public List<Map<String,Object>> list(String sql,Object... args) {
        return jdbc.query(sql,(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                Object v=rs.getObject(i);
                if(v instanceof org.postgresql.util.PGobject p && (p.getType().equals("jsonb") || p.getType().equals("json"))) v=tools.jackson.databind.json.JsonMapper.builder().build().readValue(p.getValue(),Object.class);
                if(v instanceof Timestamp t) v=t.toInstant().toString();
                row.put(rs.getMetaData().getColumnLabel(i),v);
            }
            return row;
        },args);
    }
    public Map<String,Object> one(String sql,Object... args) {var rows=list(sql,args);if(rows.isEmpty()) throw ApiError.missing();return rows.getFirst();}
    public void event(Long repair,String type,Object data) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Events require a transaction");
        one("SELECT id FROM repair_case WHERE id=? FOR UPDATE",repair);
        update("INSERT INTO outbox_event(case_id,event_key,event_type,payload) VALUES (?,?,?,?::jsonb)",repair,UUID.randomUUID(),type,Json.write(data));
    }
    public void audit(String actor,String action,Long id,Object details) {
        var users=list("SELECT id FROM app_user WHERE email=?",actor);Object actorId=users.isEmpty()?null:users.getFirst().get("id");
        String entity=action.startsWith("ORDER_")?"purchase_order":action.startsWith("REPORT_")?"repair_report":action.startsWith("COMMENT_")?"report_comment":action.startsWith("ATTACHMENT_")?"attachment":action.equals("RESULT_EVENT_DLT")?"diagnosis_job":"repair_case";
        update("INSERT INTO audit_log(actor_user_id,actor_service,action,entity_type,entity_id,detail) VALUES (?,?,?,?,?,?::jsonb)",actorId,actorId==null?actor:null,action,entity,id,Json.write(details));
    }
}
