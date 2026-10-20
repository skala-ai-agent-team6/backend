package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReportService {
    final Store db; final Access access;
    public ReportService(Store db,Access access) { this.db=db;this.access=access; }
    @Transactional public Map<String,Object> saveAndConfirm(Long repair,Inputs.Draft input) {
        var saved=draft(repair,input);return confirm((Long)saved.get("id"),new Inputs.Confirm(((Number)saved.get("version")).longValue(),Json.number(saved.get("latest_revision"))));
    }
    @Transactional public Map<String,Object> draft(Long repair,Inputs.Draft input) {
        access.technician();var repairRow=access.repair(repair);
        if(input.content().outcome()==Inputs.Outcome.NO_FAULT_FOUND && !input.content().faults().isEmpty()) throw ApiError.bad("NO_FAULT_FOUND_WITH_FAULTS");
        if(input.content().outcome()==Inputs.Outcome.FIXED && !input.content().repairSuccessful() || input.content().outcome()==Inputs.Outcome.NOT_FIXED && input.content().repairSuccessful()) throw ApiError.bad("OUTCOME_SUCCESS_MISMATCH");Long actor=access.user().id;
        // Serialize first creation, then use the caller's version as a compare-and-set.
        db.one("SELECT id FROM repair_case WHERE id=? FOR UPDATE",repair);
        var reports=db.list("SELECT * FROM repair_report WHERE case_id=?",repair);
        Long id;
        if(reports.isEmpty()) {
            if(input.version()!=0) throw ApiError.conflict("REPORT_VERSION");
            id=db.insert("INSERT INTO repair_report(case_id,product_id,author_id,diagnosis_job_id,outcome,ai_assessment,comment,action_taken) VALUES (?,?,?,(SELECT id FROM diagnosis_job WHERE case_id=? ORDER BY created_at DESC LIMIT 1),?,?,?,?)",
                repair,repairRow.get("product_id"),actor,repair,input.content().outcome()==null?(input.content().repairSuccessful()?"FIXED":"NOT_FIXED"):input.content().outcome().name(),input.content().aiAcceptance().name(),input.content().technicianOpinion(),input.content().actualAction());
        } else id=(Long)reports.getFirst().get("id");
        if(db.update("UPDATE repair_report SET version=version+1,latest_revision=latest_revision+1 WHERE id=? AND version=?",id,input.version())!=1) throw ApiError.conflict("REPORT_VERSION");
        var report=db.one("SELECT * FROM repair_report WHERE id=?",id);int revision=Json.number(report.get("latest_revision"));
        db.update("INSERT INTO repair_report_revision(report_id,revision,content,author_id) VALUES (?,?,?::jsonb,?)",id,revision,Json.write(input.content()),actor);
        int ordinal=0;
        for(var fault:input.content().faults()) {
            Long part=fault.partId(),factory=null;String factoryName="UNKNOWN",region="UNKNOWN";
            if(fault.area().equals("SW") && fault.componentId()!=null) throw ApiError.bad("SW_COMPONENT");
            if(fault.componentId()!=null) {
                var c=db.one("SELECT c.part_id,l.factory_id,f.name,f.region FROM product_component c LEFT JOIN part_lot l ON l.id=c.lot_id LEFT JOIN factory f ON f.id=l.factory_id WHERE c.id=? AND c.product_id=? AND c.installed_at<=?::timestamptz AND (c.removed_at IS NULL OR c.removed_at>?::timestamptz)",fault.componentId(),repairRow.get("product_id"),repairRow.get("created_at"),repairRow.get("created_at"));
                if(part!=null && !part.equals(c.get("part_id"))) throw ApiError.bad("COMPONENT_PART_MISMATCH");
                part=(Long)c.get("part_id");factory=(Long)c.get("factory_id");
                if(factory!=null) {factoryName=(String)c.get("name");region=(String)c.get("region");}
            }
            if(part!=null) db.one("SELECT p.id FROM part p JOIN model_part mp ON mp.part_id=p.id JOIN product pr ON pr.model_id=mp.model_id WHERE p.id=? AND pr.id=?",part,repairRow.get("product_id"));
            if(part!=null && Set.of("PCB","MOTOR").contains(fault.area())) {
                var category=db.one("SELECT category FROM part WHERE id=?",part).get("category");
                if(!category.equals(fault.area())) throw ApiError.bad("FAULT_PART_CATEGORY");
            }
            db.update("INSERT INTO confirmed_fault(report_id,product_id,revision,ordinal,fault_domain,cause_code,description,part_id,component_id,factory_id,factory_name,production_region) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",id,repairRow.get("product_id"),revision,++ordinal,fault.area(),input.content().actualCause(),input.content().actualCause(),part,fault.componentId(),factory,factoryName,region);
        }
        db.event(repair,"REPORT_DRAFT_SAVED",Map.of("reportId",id,"revision",revision));
        db.audit(access.user().email,"REPORT_DRAFT_SAVED",id,Map.of("revision",revision));
        return get(id);
    }
    @Transactional public Map<String,Object> confirm(Long id,Inputs.Confirm input) {
        access.technician();var report=access.report(id);
        db.one("SELECT id FROM repair_report WHERE id=? FOR UPDATE",id);
        if(db.update("UPDATE repair_report SET confirmed_revision=?,version=version+1 WHERE id=? AND version=? AND latest_revision=? AND confirmed_revision IS DISTINCT FROM ?",input.revision(),id,input.version(),input.revision(),input.revision())!=1) throw ApiError.conflict("REPORT_VERSION_OR_REVISION");
        db.update("UPDATE repair_report_revision SET confirmed_at=now() WHERE report_id=? AND revision=?",id,input.revision());
        var revision=db.one("SELECT content FROM repair_report_revision WHERE report_id=? AND revision=?",id,input.revision());
        var content=(Map<?,?>)revision.get("content");
        String outcome=content.get("outcome")==null?(Boolean.TRUE.equals(content.get("repairSuccessful"))?"FIXED":"NOT_FIXED"):String.valueOf(content.get("outcome"));
        db.update("UPDATE repair_report SET outcome=?,ai_assessment=?,comment=?,action_taken=?,confirmed_at=now() WHERE id=?",outcome,content.get("aiAcceptance"),content.get("technicianOpinion"),content.get("actualAction"),id);
        if(!outcome.equals("NOT_FIXED")) db.update("UPDATE repair_case SET status='CLOSED',closed_at=now(),version=version+1 WHERE id=?",report.get("case_id"));
        else db.update("UPDATE repair_case SET status='MANUAL_REVIEW' WHERE id=?",report.get("case_id"));
        db.event((Long)report.get("case_id"),"REPORT_CONFIRMED",Map.of("reportId",id,"revision",input.revision()));
        db.audit(access.user().email,"REPORT_CONFIRMED",id,Map.of("revision",input.revision()));return get(id);
    }
    public Map<String,Object> get(Long id) {
        var r=access.report(id); r.put("revisions",db.list("SELECT * FROM repair_report_revision WHERE report_id=? ORDER BY revision DESC",id));
        r.put("comments",comments(id));return r;
    }
    public List<Map<String,Object>> history(Long id) {access.report(id);return db.list("SELECT * FROM repair_report_revision WHERE report_id=? ORDER BY revision DESC",id);}
    public List<Map<String,Object>> comments(Long id) { access.report(id); return db.list("SELECT * FROM report_comment WHERE report_id=? ORDER BY created_at,id",id); }
    @Transactional public Map<String,Object> comment(Long id,Inputs.Comment input) {
        access.manager();var report=access.report(id);
        db.one("SELECT revision FROM repair_report_revision WHERE report_id=? AND revision=?",id,input.revision());
        Long actor=access.user().id;
        int inserted=db.update("INSERT INTO report_comment(report_id,revision,author_id,body,request_key) VALUES (?,?,?,?,?) ON CONFLICT (report_id,request_key) DO NOTHING",id,input.revision(),actor,input.body(),input.requestKey());
        var saved=db.one("SELECT * FROM report_comment WHERE report_id=? AND request_key=?",id,input.requestKey());
        if(!saved.get("body").equals(input.body()) || Json.number(saved.get("revision"))!=input.revision() || !saved.get("author_id").equals(actor)) throw ApiError.conflict("IDEMPOTENCY_KEY_REUSED");
        if(inserted==1) {db.event((Long)report.get("case_id"),"COMMENT_ADDED",Map.of("reportId",id,"commentId",saved.get("id")));db.audit(access.user().email,"COMMENT_ADDED",(Long)saved.get("id"),Map.of("commentId",saved.get("id")));}
        return saved;
    }
}
