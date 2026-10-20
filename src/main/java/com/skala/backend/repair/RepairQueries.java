package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RepairQueries {
    final Store db;final Access access;
    public RepairQueries(Store db,Access access) {this.db=db;this.access=access;}
    public Map<String,Object> list(int page,int size,String status,String serial) {
        if(page<0 || page>100000 || size<1 || size>100) throw ApiError.bad("PAGE");
        var u=access.user();String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(u.role.equals("TECHNICIAN")){where+=" AND r.center_id=?";args.add(u.centerId);}
        if(status!=null){where+=" AND r.status=?";args.add(status);}if(serial!=null){where+=" AND p.serial_number=?";args.add(serial);}
        var count=db.one("SELECT count(*) AS total FROM repair_case r JOIN product p ON p.id=r.product_id"+where,args.toArray());args.add(size);args.add(page*size);
        return Map.of("page",page,"size",size,"total",count.get("total"),"items",db.list("SELECT r.*,p.serial_number FROM repair_case r JOIN product p ON p.id=r.product_id"+where+" ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",args.toArray()));
    }
    public Map<String,Object> detail(Long id) {
        var r=access.repair(id);r.put("attachments",db.list("SELECT id,request_id,original_name,kind,target,mime_type,byte_size,sha256,created_at FROM attachment WHERE case_id=? ORDER BY created_at,id",id));
        r.put("tasks",db.list("SELECT * FROM diagnosis_job WHERE case_id=? ORDER BY created_at DESC",id));r.put("photoRequests",db.list("SELECT p.* FROM photo_request p JOIN diagnosis_job t ON t.id=p.job_id WHERE t.case_id=? ORDER BY round_no",id));
        r.put("reports",db.list("SELECT * FROM repair_report WHERE case_id=?",id));r.put("orders",db.list("SELECT * FROM purchase_order WHERE case_id=? ORDER BY created_at,id",id));return r;
    }
    public Long requestRepairId(Long request) {return (Long)db.one("SELECT j.case_id FROM photo_request p JOIN diagnosis_job j ON j.id=p.job_id WHERE p.id=?",request).get("case_id");}
    public Map<String,Object> task(Long task) {
        var job=db.one("SELECT * FROM diagnosis_job WHERE id=?",task);access.repair((Long)job.get("case_id"));var value=enrich(job);
        value.put("photoRequests",db.list("SELECT * FROM photo_request WHERE job_id=? ORDER BY round_no",task));return value;
    }
    public Map<String,Object> diagnosis(Long id) {access.repair(id);return enrich(db.one("SELECT * FROM diagnosis_job WHERE case_id=? ORDER BY created_at DESC,id DESC LIMIT 1",id));}
    private Map<String,Object> enrich(Map<String,Object> job) {
        job.put("candidates",db.list("SELECT * FROM diagnosis_candidate WHERE job_id=? ORDER BY rank",job.get("id")));
        job.put("evidence",db.list("SELECT e.* FROM diagnosis_evidence e JOIN diagnosis_candidate c ON c.id=e.candidate_id WHERE c.job_id=? ORDER BY c.rank,e.id",job.get("id")));
        job.put("recommendedParts",db.list("SELECT * FROM diagnosis_recommended_part WHERE job_id=? ORDER BY id",job.get("id")));return job;
    }
    public List<Map<String,Object>> tools(Long id) {access.repair(id);return db.list("SELECT e.* FROM agent_execution e JOIN diagnosis_job t ON t.id=e.job_id WHERE t.case_id=? ORDER BY e.event_id",id);}
    public Map<String,Object> attachment(Long id,boolean internal) {var a=db.one("SELECT * FROM attachment WHERE id=?",id);if(!internal) access.repair((Long)a.get("case_id"));return a;}
    public Map<String,Object> procurement(Long id) {var r=access.repair(id);return Map.of("status",r.get("procurement_status"),"snapshots",db.list("SELECT * FROM stock_snapshot WHERE case_id=? ORDER BY created_at",id),"orders",db.list("SELECT * FROM purchase_order WHERE case_id=? ORDER BY created_at",id));}
    public Long reportId(Long repair) {access.repair(repair);return (Long)db.one("SELECT id FROM repair_report WHERE case_id=?",repair).get("id");}
    public Long taskRepairId(Long task) {return (Long)db.one("SELECT case_id FROM diagnosis_job WHERE id=?",task).get("case_id");}
    public List<Map<String,Object>> confirmedCases(int page,int size) {
        if(page<0 || page>100000 || size<1 || size>500) throw ApiError.bad("PAGE");
        return db.list("SELECT p.id AS report_id,p.case_id,p.confirmed_revision,rr.content,rr.confirmed_at FROM repair_report p JOIN repair_report_revision rr ON rr.report_id=p.id AND rr.revision=p.confirmed_revision ORDER BY p.id LIMIT ? OFFSET ?",size,page*size);
    }
}
