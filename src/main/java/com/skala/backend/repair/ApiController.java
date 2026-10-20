package com.skala.backend.repair;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    final RepairQueries queries;final Access access;final RepairService repairs;final ReportService reports;final OrderService orders;final LocalFiles files;final SseHub sse;final Catalog catalog;final DashboardService dashboard;
    public ApiController(RepairQueries queries,Access access,RepairService repairs,ReportService reports,OrderService orders,LocalFiles files,SseHub sse,Catalog catalog,DashboardService dashboard) {
        this.queries=queries;this.access=access;this.repairs=repairs;this.reports=reports;this.orders=orders;this.files=files;this.sse=sse;this.catalog=catalog;this.dashboard=dashboard;
    }
    @GetMapping("/auth/csrf") public Map<String,Object> csrf(CsrfToken token) {return Map.of("headerName",token.getHeaderName(),"token",token.getToken());}
    @GetMapping("/auth/me") public Map<String,Object> me() {var u=access.user();var m=new LinkedHashMap<String,Object>();m.put("id",u.id);m.put("username",u.email);m.put("email",u.email);m.put("name",u.name);m.put("active",u.active);m.put("role",u.role);m.put("centerId",u.centerId);return m;}
    @GetMapping("/products") public Map<String,Object> product(@RequestParam(required=false) String serial,@RequestParam(required=false) String serialNumber) {access.user();return catalog.product(serialNumber!=null?serialNumber:serial);}
    @PostMapping(value="/repairs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(@Valid @RequestPart("data") Inputs.Intake input,@RequestPart("files") List<MultipartFile> uploads) {return ResponseEntity.accepted().body(repairs.create(input,uploads));}
    @GetMapping("/repairs") public Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status,@RequestParam(required=false) String serial) {
        return queries.list(page,size,status,serial);
    }
    @GetMapping("/repairs/{id}") public Map<String,Object> detail(@PathVariable Long id) {return queries.detail(id);}
    @PostMapping({"/repairs/{id}/diagnosis","/repairs/{id}/diagnoses"}) public ResponseEntity<?> diagnose(@PathVariable Long id,@RequestHeader(value="Idempotency-Key",required=false) UUID key) {return ResponseEntity.accepted().body(repairs.start(id,key));}
    @GetMapping("/tasks/{id}") public Map<String,Object> task(@PathVariable Long id) {return queries.task(id);}
    @PostMapping("/photo-requests/{id}/submit") public ResponseEntity<?> submit(@PathVariable Long id,@Valid @RequestBody Inputs.PhotoSubmission input) {return ResponseEntity.accepted().body(repairs.resume(queries.requestRepairId(id),new Inputs.Resume(id,input.submissionKey(),input.attachmentIds())));}
    @PostMapping("/repairs/{id}/report") public Map<String,Object> saveAndConfirm(@PathVariable Long id,@Valid @RequestBody Inputs.Draft input) {return reports.saveAndConfirm(id,input);}
    @GetMapping("/repairs/{id}/diagnosis") public Map<String,Object> diagnosis(@PathVariable Long id) {return queries.diagnosis(id);}
    @GetMapping("/repairs/{id}/diagnosis/tools") public List<Map<String,Object>> tools(@PathVariable Long id) {return queries.tools(id);}
    @PostMapping(value="/repairs/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable Long id,@RequestParam Long requestId,@RequestPart MultipartFile file) {return repairs.upload(id,requestId,file);}
    @GetMapping("/attachments/{id}") public ResponseEntity<Resource> download(@PathVariable Long id) {
        return attachment(queries.attachment(id,false),files);
    }
    static ResponseEntity<Resource> attachment(Map<String,Object> a,LocalFiles files) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)a.get("mime_type"))).contentLength(((Number)a.get("byte_size")).longValue())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename((String)a.get("original_name"),StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(files.path((String)a.get("object_key"))));
    }
    @PostMapping("/repairs/{id}/diagnosis/resume") public ResponseEntity<?> resume(@PathVariable Long id,@Valid @RequestBody Inputs.Resume input) {return ResponseEntity.accepted().body(repairs.resume(id,input));}
    @GetMapping(value="/repairs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable Long id,@RequestHeader(value="Last-Event-ID",defaultValue="0") long cursor) {return sse.subscribe(id,cursor);}
    @GetMapping("/parts") public List<Map<String,Object>> parts() {return catalog.parts();}
    @GetMapping("/parts/{id}/inventory") public List<Map<String,Object>> stock(@PathVariable Long id) {return catalog.stocks(id);}
    @GetMapping("/parts/{id}/factories") public List<Map<String,Object>> factories(@PathVariable Long id) {return catalog.factories(id);}
    @GetMapping("/repairs/{id}/procurement") public Map<String,Object> procurement(@PathVariable Long id) {return queries.procurement(id); }
    @PostMapping("/repairs/{id}/procurement/retry") public ResponseEntity<?> retryProcurement(@PathVariable Long id) {repairs.retryProcurement(id);return ResponseEntity.accepted().body(Map.of("repairId",id));}
    @PostMapping("/repairs/{id}/orders") public Map<String,Object> proposal(@PathVariable Long id,@Valid @RequestBody Inputs.Proposal input) {return orders.propose(id,input);}
    @GetMapping("/orders/{id}") public Map<String,Object> order(@PathVariable Long id) {return orders.get(id);}
    @PostMapping("/orders/{id}/{action:approve|reject|cancel|ship|receive}") public Map<String,Object> decision(@PathVariable Long id,@PathVariable String action,@Valid @RequestBody Inputs.Decision input) {return orders.transition(id,action,input);}
    @PutMapping("/repairs/{id}/report/draft") public Map<String,Object> draft(@PathVariable Long id,@Valid @RequestBody Inputs.Draft input) {return reports.draft(id,input);}
    @GetMapping("/repairs/{id}/report") public Map<String,Object> reportForRepair(@PathVariable Long id) {return reports.get(queries.reportId(id));}
    @GetMapping("/reports/{id}") public Map<String,Object> report(@PathVariable Long id) {return reports.get(id);}
    @GetMapping("/reports/{id}/history") public List<Map<String,Object>> history(@PathVariable Long id) {return reports.history(id);}
    @PostMapping("/reports/{id}/confirm") public Map<String,Object> confirm(@PathVariable Long id,@Valid @RequestBody Inputs.Confirm input) {return reports.confirm(id,input);}
    @PostMapping("/reports/{id}/comments") public Map<String,Object> comment(@PathVariable Long id,@Valid @RequestBody Inputs.Comment input) {return reports.comment(id,input);}
    @GetMapping("/reports/{id}/comments") public List<Map<String,Object>> comments(@PathVariable Long id) {return reports.comments(id);}
    @GetMapping({"/dashboard","/dashboard/faults"}) public List<Map<String,Object>> dashboard(@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="PERIOD") String groupBy) {return dashboard.counts(from,to,groupBy);}
}
