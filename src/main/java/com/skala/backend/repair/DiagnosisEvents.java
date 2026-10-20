package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class DiagnosisEvents {
    final Store db;
    public DiagnosisEvents(Store db) { this.db=db; }
    public record Event(UUID eventId,String eventType,Long repairId,Long taskId,UUID executionId,int inputRevision,long sequence,Map<String,Object> payload) {}
    public static Event parse(String json) {
        try {
            var m=Json.object(json);
            if(Json.number(m.get("schemaVersion"))!=2) throw ApiError.bad("SCHEMA_VERSION");
            Instant.parse(String.valueOf(m.get("occurredAt"))); Json.uuid(m.get("correlationId"));
            String type=String.valueOf(m.get("eventType"));
            if(!Set.of("DIAGNOSIS_STARTED","DIAGNOSIS_PROGRESS","TOOL_EXECUTED","PHOTO_REQUESTED","DIAGNOSIS_COMPLETED","DIAGNOSIS_FAILED").contains(type)) throw ApiError.bad("EVENT_TYPE");
            int revision=Json.number(m.get("inputRevision"));long sequence=new java.math.BigDecimal(String.valueOf(m.get("sequence"))).longValueExact();
            if(revision<1 || sequence<1 || !(m.get("payload") instanceof Map<?,?>)) throw ApiError.bad("INVALID_EVENT");
            @SuppressWarnings("unchecked") var payload=(Map<String,Object>)m.get("payload");
            return new Event(Json.uuid(m.get("eventId")),type,Json.id(m.get("repairId")),Json.id(m.get("taskId")),Json.uuid(m.get("executionId")),revision,sequence,payload);
        } catch(RuntimeException e) { throw ApiError.bad("INVALID_EVENT"); }
    }
    static String required(Map<String,Object> p,String key) { Object v=p.get(key); if(!(v instanceof String s) || s.isBlank() || s.length()>20000) throw ApiError.bad("MISSING_"+key); return (String)v; }
    @Transactional public void receive(String json) {
        Event e=parse(json);
        var tasks=db.list("SELECT * FROM diagnosis_job WHERE id=? AND case_id=? FOR UPDATE",e.taskId,e.repairId);
        if(tasks.isEmpty()) throw ApiError.bad("UNKNOWN_TASK"); var t=tasks.getFirst();
        if(!db.list("SELECT event_id FROM received_event WHERE event_id=?",e.eventId).isEmpty()) return;
        boolean current=Objects.equals(t.get("execution_id"),e.executionId) && Json.number(t.get("input_revision"))==e.inputRevision;
        boolean active=Set.of("QUEUED","RUNNING").contains(t.get("status"));
        boolean newer=e.sequence>((Number)t.get("last_sequence")).longValue();
        if(!current || !active || !newer) {
            history(e,json,"IGNORED_STALE");
            if(current && e.eventType.equals("TOOL_EXECUTED")) {
                required(e.payload,"tool");required(e.payload,"toolVersion");
                tool(e);
            }
            return;
        }
        var p=e.payload;
        switch(e.eventType) {
            case "DIAGNOSIS_STARTED","DIAGNOSIS_PROGRESS" -> {
                String stage=required(p,"stage");
                db.update("UPDATE diagnosis_job SET status='RUNNING',stage=? WHERE id=?",stage,e.taskId);
            }
            case "TOOL_EXECUTED" -> { required(p,"tool");required(p,"toolVersion"); }
            case "PHOTO_REQUESTED" -> {
                int round=Json.number(t.get("photo_request_count"))+1;
                if(round>3) { fail(e.taskId,e.repairId,"PHOTO_LIMIT_EXCEEDED"); break; }
                if(Json.number(p.get("round"))!=round) throw ApiError.bad("INVALID_PHOTO_ROUND");
                UUID request=Json.uuid(p.get("requestKey")); String checkpoint=required(p,"checkpoint");
                Long requestId=db.insert("INSERT INTO photo_request(job_id,request_key,round_no,target,instruction) VALUES (?,?,?,?,?)",e.taskId,request,round,required(p,"bodyPart"),required(p,"description"));
                p=new LinkedHashMap<>(p);p.put("requestId",requestId);
                db.update("UPDATE repair_case SET status='WAITING_INPUT',version=version+1 WHERE id=?",e.repairId);
                db.update("UPDATE diagnosis_job SET status='WAITING_INPUT',photo_request_count=?,checkpoint_ref=? WHERE id=?",round,checkpoint,e.taskId);
            }
            case "DIAGNOSIS_COMPLETED" -> {
                validateResult(p,e.repairId);
                saveResult(e,p);
                db.update("UPDATE diagnosis_job SET status='SUCCEEDED',finished_at=now(),result=?::jsonb WHERE id=?",Json.write(p),e.taskId);
                db.update("UPDATE repair_case SET status='DIAGNOSED',procurement_status='PENDING' WHERE id=?",e.repairId);
                db.update("INSERT INTO procurement_job(case_id) VALUES (?) ON CONFLICT DO NOTHING",e.repairId);
            }
            case "DIAGNOSIS_FAILED" -> fail(e.taskId,e.repairId,required(p,"errorCode"));
            default -> throw ApiError.bad("EVENT_TYPE");
        }
        db.update("UPDATE diagnosis_job SET last_sequence=?,updated_at=now() WHERE id=?",e.sequence,e.taskId);
        history(e,json,"APPLIED");
        if(e.eventType.equals("TOOL_EXECUTED")) tool(e);
        // UI events deliberately carry summaries; raw model/tool details remain in authorized GET APIs.
        var status=db.one("SELECT status FROM diagnosis_job WHERE id=?",e.taskId).get("status");
        String uiType=status.equals("FAILED") ? "DIAGNOSIS_FAILED" : e.eventType;
        Map<String,Object> ui=new LinkedHashMap<>();ui.put("taskId",e.taskId);ui.put("status",status);ui.put("inputRevision",e.inputRevision);
        if(e.eventType.equals("PHOTO_REQUESTED") && status.equals("WAITING_INPUT")) {
            for(String key:List.of("requestId","requestKey","round","bodyPart","description")) ui.put(key,p.get(key));
        }
        if(p.get("stage")!=null) ui.put("stage",p.get("stage"));
        db.event(e.repairId,uiType,ui);
    }
    void validateResult(Map<String,Object> p,Long repair) {
        required(p,"summary");required(p,"modelVersion");
        var candidates=Json.objects(p.get("candidates")); if(candidates.isEmpty()) throw ApiError.bad("CANDIDATES_REQUIRED");
        Set<Integer> ranks=new HashSet<>();
        for(var c:candidates) {
            int rank=Json.number(c.get("rank"));double confidence;
            try { confidence=Double.parseDouble(String.valueOf(c.get("confidence"))); } catch(RuntimeException x) {throw ApiError.bad("CONFIDENCE");}
            if(rank<1 || !ranks.add(rank) || !Double.isFinite(confidence) || confidence<0 || confidence>1) throw ApiError.bad("CANDIDATE_RANGE");
            required(c,"cause");required(c,"explanation");
        }
        for(var part:Json.objects(p.get("recommendedParts"))) {
            if(Json.number(part.get("quantity"))<1) throw ApiError.bad("QUANTITY");
            if(db.list("SELECT id FROM part WHERE id=?",Json.id(part.get("partId"))).isEmpty()) throw ApiError.bad("UNKNOWN_PART");
        }
        required(p,"repairMethod");
        for(var evidence:Json.objects(p.get("evidence"))) {
            required(evidence,"type");
            if(evidence.get("attachmentId")!=null && db.list("SELECT id FROM attachment WHERE id=? AND case_id=?",Json.id(evidence.get("attachmentId")),repair).isEmpty()) throw ApiError.bad("FOREIGN_EVIDENCE_ATTACHMENT");
        }
    }
    void tool(Event e) {
        var p=e.payload;String agent=String.valueOf(p.getOrDefault("agentType","RAG"));
        if(!Set.of("PCB","MOTOR","SW","RAG","BUSINESS").contains(agent)) throw ApiError.bad("AGENT_TYPE");
        db.update("INSERT INTO agent_execution(job_id,call_key,agent_type,mcp_server,tool_name,tool_version,model_version,status,input_payload,output_payload,event_id,execution_ref,finished_at) VALUES (?,?,?,?,?,?,?,'SUCCEEDED',?::jsonb,?::jsonb,?,?,now())",
            e.taskId,e.eventId,agent,String.valueOf(p.getOrDefault("mcpServer","UNSPECIFIED")),required(p,"tool"),required(p,"toolVersion"),p.get("modelVersion"),Json.write(p.getOrDefault("input",Map.of())),Json.write(p),e.eventId,e.executionId);
    }
    void saveResult(Event e,Map<String,Object> p) {
        Long first=null;Map<Integer,Long> ranks=new HashMap<>();
        for(var c:Json.objects(p.get("candidates"))) {
            Long part=c.get("partId")==null?null:Json.id(c.get("partId"));
            Long id=db.insert("INSERT INTO diagnosis_candidate(job_id,part_id,rank,cause_code,cause_description,confidence,repair_guide,recommended_quantity) VALUES (?,?,?,?,?,?,?,?)",
                e.taskId,part,Json.number(c.get("rank")),c.getOrDefault("causeCode",c.get("cause")),c.get("explanation"),new java.math.BigDecimal(String.valueOf(c.get("confidence"))),p.get("repairMethod"),c.get("recommendedQuantity"));
            ranks.put(Json.number(c.get("rank")),id);
            if(first==null || Json.number(c.get("rank"))==1) first=id;
        }
        for(var part:Json.objects(p.get("recommendedParts"))) db.update("INSERT INTO diagnosis_recommended_part(job_id,part_id,quantity) VALUES (?,?,?)",e.taskId,Json.id(part.get("partId")),Json.number(part.get("quantity")));
        for(var evidence:Json.objects(p.get("evidence"))) {
            Long candidate=evidence.get("candidateRank")==null?first:ranks.get(Json.number(evidence.get("candidateRank")));
            if(candidate==null) throw ApiError.bad("EVIDENCE_CANDIDATE");
            db.update("INSERT INTO diagnosis_evidence(candidate_id,attachment_id,source_uri,source_version,excerpt,annotation,details) VALUES (?,?,?,?,?,?::jsonb,?::jsonb)",candidate,evidence.get("attachmentId")==null?null:Json.id(evidence.get("attachmentId")),evidence.get("sourceUri"),evidence.get("sourceVersion"),String.valueOf(evidence.getOrDefault("excerpt",evidence.getOrDefault("citation",evidence.get("type")))),Json.write(evidence.get("annotation")),Json.write(evidence));
        }
    }
    void history(Event e,String body,String disposition) { db.update("INSERT INTO received_event(event_id,job_id,body,disposition) VALUES (?,?,?::jsonb,?)",e.eventId,e.taskId,body,disposition); }
    void fail(Long task,Long repair,String error) {
        db.update("UPDATE diagnosis_job SET status='FAILED',error_code=?,finished_at=now(),updated_at=now() WHERE id=?",error,task);
        db.update("UPDATE repair_case SET status='MANUAL_REVIEW' WHERE id=?",repair);
    }
    @Transactional public void retryCount(Event e,int count) {
        if(count>0) db.update("UPDATE diagnosis_job SET retry_count=greatest(retry_count,?) WHERE id=? AND execution_id=? AND input_revision=?",count,e.taskId,e.executionId,e.inputRevision);
    }
    @Transactional public void deadLetter(String body,int retries) {
        Event e;
        try { e=parse(body); } catch(ApiError invalid) { return; }
        var rows=db.list("SELECT * FROM diagnosis_job WHERE id=? AND case_id=? FOR UPDATE",e.taskId,e.repairId);
        if(rows.isEmpty()) return; var t=rows.getFirst();
        if(!Objects.equals(t.get("execution_id"),e.executionId) || Json.number(t.get("input_revision"))!=e.inputRevision || e.sequence<=((Number)t.get("last_sequence")).longValue() || !Set.of("RUNNING","QUEUED").contains(t.get("status"))) return;
        if(!db.list("SELECT event_id FROM received_event WHERE event_id=?",e.eventId).isEmpty()) return;
        fail(e.taskId,e.repairId,"RESULT_EVENT_DLT");
        db.update("UPDATE diagnosis_job SET retry_count=greatest(retry_count,?) WHERE id=?",retries,e.taskId);
        history(e,body,"DLT");
        db.event(e.repairId,"DIAGNOSIS_FAILED",Map.of("taskId",e.taskId,"errorCode","RESULT_EVENT_DLT"));
        db.audit("kafka","RESULT_EVENT_DLT",e.taskId,Map.of("eventId",e.eventId));
    }
}
