-- Non-destructive migration of V3 data into docs/db canonical tables. Originals remain in legacy_v3.
INSERT INTO legacy_id_map SELECT 'service_center',id,row_number() OVER(ORDER BY id) FROM legacy_v3.service_center;
INSERT INTO legacy_id_map SELECT 'app_user',id,row_number() OVER(ORDER BY id) FROM legacy_v3.app_user;
INSERT INTO legacy_id_map SELECT 'factory',id,row_number() OVER(ORDER BY id) FROM legacy_v3.factory;
INSERT INTO legacy_id_map SELECT 'part',id,row_number() OVER(ORDER BY id) FROM legacy_v3.part;
INSERT INTO legacy_id_map SELECT 'part_lot',id,row_number() OVER(ORDER BY id) FROM legacy_v3.part_lot;
INSERT INTO legacy_id_map SELECT 'product',id,row_number() OVER(ORDER BY id) FROM legacy_v3.product;
INSERT INTO legacy_id_map SELECT 'product_component',id,row_number() OVER(ORDER BY id) FROM legacy_v3.product_component;
INSERT INTO legacy_id_map SELECT 'warehouse',id,row_number() OVER(ORDER BY id) FROM legacy_v3.warehouse;
INSERT INTO legacy_id_map SELECT 'repair_case',id,row_number() OVER(ORDER BY id) FROM legacy_v3.repair;
INSERT INTO legacy_id_map SELECT 'diagnosis_job',id,row_number() OVER(ORDER BY id) FROM legacy_v3.diagnosis_task;
INSERT INTO legacy_id_map SELECT 'photo_request',id,row_number() OVER(ORDER BY id) FROM legacy_v3.photo_request;
INSERT INTO legacy_id_map SELECT 'attachment',id,row_number() OVER(ORDER BY id) FROM legacy_v3.attachment;
INSERT INTO legacy_id_map SELECT 'repair_report',id,row_number() OVER(ORDER BY id) FROM legacy_v3.report;
INSERT INTO legacy_id_map SELECT 'report_comment',id,row_number() OVER(ORDER BY id) FROM legacy_v3.report_comment;
INSERT INTO legacy_id_map SELECT 'purchase_order',id,row_number() OVER(ORDER BY id) FROM legacy_v3.purchase_order;
INSERT INTO legacy_id_map SELECT 'purchase_order_item',id,row_number() OVER(ORDER BY id) FROM legacy_v3.order_item;
INSERT INTO legacy_id_map SELECT 'stock_snapshot',id,row_number() OVER(ORDER BY id) FROM legacy_v3.stock_snapshot;

CREATE FUNCTION legacy_id(kind text, value uuid) RETURNS bigint LANGUAGE sql STABLE AS $$ SELECT new_id FROM legacy_id_map WHERE entity=kind AND old_id=value $$;
CREATE FUNCTION migrate_legacy_json(value jsonb) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE result jsonb; mapped bigint; raw text;
BEGIN
 IF value IS NULL THEN RETURN NULL; END IF;
 IF jsonb_typeof(value)='object' THEN
   SELECT coalesce(jsonb_object_agg(key,migrate_legacy_json(v)),'{}') INTO result FROM jsonb_each(value) AS x(key,v); RETURN result;
 ELSIF jsonb_typeof(value)='array' THEN
   SELECT coalesce(jsonb_agg(migrate_legacy_json(v) ORDER BY ord),'[]') INTO result FROM jsonb_array_elements(value) WITH ORDINALITY AS x(v,ord); RETURN result;
 ELSIF jsonb_typeof(value)='string' THEN
   raw=value#>>'{}';
   IF raw ~ '^[0-9a-fA-F-]{36}$' THEN SELECT new_id INTO mapped FROM legacy_id_map WHERE old_id=raw::uuid LIMIT 1;
   ELSIF raw LIKE '/internal/attachments/%' AND substring(raw from 23) ~ '^[0-9a-fA-F-]{36}$' THEN
     SELECT new_id INTO mapped FROM legacy_id_map WHERE entity='attachment' AND old_id=substring(raw from 23)::uuid;
     IF mapped IS NOT NULL THEN RETURN to_jsonb('/internal/attachments/'||mapped); END IF;
   END IF;
   IF mapped IS NOT NULL THEN RETURN to_jsonb(mapped::text); END IF;
 END IF; RETURN value;
END $$;
INSERT INTO service_center(id,code,name,region) OVERRIDING SYSTEM VALUE SELECT legacy_id('service_center',id),'LEGACY-'||legacy_id('service_center',id),name,'UNKNOWN' FROM legacy_v3.service_center;
INSERT INTO app_user(id,email,password_hash,name,role,center_id) OVERRIDING SYSTEM VALUE SELECT legacy_id('app_user',id),username,password_hash,username,role,legacy_id('service_center',center_id) FROM legacy_v3.app_user;
INSERT INTO factory(id,name,country,region,address,phone) OVERRIDING SYSTEM VALUE SELECT legacy_id('factory',id),name,'UNKNOWN',region,'UNKNOWN',contact FROM legacy_v3.factory;
INSERT INTO part(id,part_number,name,category) OVERRIDING SYSTEM VALUE SELECT legacy_id('part',id),'LEGACY-'||legacy_id('part',id),name,CASE WHEN lower(name) LIKE '%motor%' THEN 'MOTOR' ELSE 'OTHER' END FROM legacy_v3.part;
INSERT INTO part_supplier(part_id,factory_id) SELECT legacy_id('part',id),legacy_id('factory',factory_id) FROM legacy_v3.part WHERE factory_id IS NOT NULL;
INSERT INTO product_model(model_code,name) SELECT DISTINCT model,model FROM legacy_v3.product ORDER BY model;
INSERT INTO product(id,serial_number,model_id,information) OVERRIDING SYSTEM VALUE SELECT legacy_id('product',p.id),p.serial,m.id,p.information FROM legacy_v3.product p JOIN product_model m ON m.model_code=p.model;
INSERT INTO part_lot(id,part_id,factory_id,lot_number) OVERRIDING SYSTEM VALUE SELECT legacy_id('part_lot',id),legacy_id('part',part_id),legacy_id('factory',factory_id),code FROM legacy_v3.part_lot;
INSERT INTO product_component(id,product_id,part_id,lot_id,slot_code,installed_at,removed_at) OVERRIDING SYSTEM VALUE SELECT legacy_id('product_component',id),legacy_id('product',product_id),legacy_id('part',part_id),legacy_id('part_lot',lot_id),'LEGACY-'||id,installed_at,removed_at FROM legacy_v3.product_component;
INSERT INTO model_part SELECT DISTINCT p.model_id,c.part_id FROM product_component c JOIN product p ON p.id=c.product_id;
INSERT INTO warehouse(id,name,region,address) OVERRIDING SYSTEM VALUE SELECT legacy_id('warehouse',id),name,'UNKNOWN','UNKNOWN' FROM legacy_v3.warehouse;
INSERT INTO inventory SELECT legacy_id('warehouse',warehouse_id),legacy_id('part',part_id),on_hand,reserved,0 FROM legacy_v3.inventory;
INSERT INTO repair_case(id,case_number,product_id,center_id,technician_id,symptom,error_codes,firmware_version,status,created_at,closed_at,user_input,product_snapshot,procurement_status,correlation_id) OVERRIDING SYSTEM VALUE
 SELECT legacy_id('repair_case',id),'LEGACY-'||id,legacy_id('product',product_id),legacy_id('service_center',center_id),legacy_id('app_user',created_by),symptoms,error_codes,firmware,
 CASE WHEN status='REPAIRED' THEN 'CLOSED' WHEN EXISTS(SELECT 1 FROM legacy_v3.diagnosis_task t WHERE t.repair_id=r.id AND t.status='WAITING_INPUT') THEN 'WAITING_INPUT' ELSE status END,
 created_at,CASE WHEN status='REPAIRED' THEN created_at END,user_input,migrate_legacy_json(product_snapshot),procurement_status,id FROM legacy_v3.repair r;
INSERT INTO diagnosis_job(id,case_id,request_key,status,photo_request_count,retry_count,workflow_version,checkpoint_ref,error_code,created_at,finished_at,input_revision,execution_id,last_sequence,stage,result,updated_at) OVERRIDING SYSTEM VALUE
 SELECT legacy_id('diagnosis_job',id),legacy_id('repair_case',repair_id),id,status,photo_round,server_retries,'legacy-v1',checkpoint,error,created_at,
 CASE WHEN status IN ('SUCCEEDED','FAILED','CANCELLED') THEN updated_at END,input_revision,execution_id,last_sequence,stage,migrate_legacy_json(result),updated_at FROM legacy_v3.diagnosis_task;
INSERT INTO photo_request(id,job_id,round_no,instruction,target,status,submitted_at,request_key,submission_key) OVERRIDING SYSTEM VALUE
 SELECT legacy_id('photo_request',id),legacy_id('diagnosis_job',task_id),round,description,body_part,CASE WHEN fulfilled_at IS NULL THEN 'OPEN' ELSE 'SUBMITTED' END,fulfilled_at,id,submission_key FROM legacy_v3.photo_request;
INSERT INTO attachment(id,case_id,request_id,uploader_id,kind,target,object_key,mime_type,byte_size,sha256,created_at,original_name) OVERRIDING SYSTEM VALUE
 SELECT legacy_id('attachment',id),legacy_id('repair_case',repair_id),legacy_id('photo_request',request_id),legacy_id('app_user',created_by),
 CASE WHEN mime_type LIKE 'image/%' THEN 'IMAGE' WHEN mime_type='text/plain' THEN 'LOG' ELSE 'DOCUMENT' END,'GENERAL',object_key,mime_type,size,sha256,created_at,original_name FROM legacy_v3.attachment;
INSERT INTO received_event SELECT event_id,legacy_id('diagnosis_job',task_id),body,disposition,received_at FROM legacy_v3.received_event;
INSERT INTO agent_execution(job_id,call_key,agent_type,mcp_server,tool_name,model_version,status,input_payload,output_payload,event_id,execution_ref,tool_version,finished_at)
 SELECT legacy_id('diagnosis_job',task_id),event_id,'RAG','legacy',coalesce(details->>'tool','UNKNOWN'),details->>'modelVersion','SUCCEEDED','{}',migrate_legacy_json(details),event_id,execution_id,coalesce(details->>'toolVersion','UNKNOWN'),now() FROM legacy_v3.tool_execution;
INSERT INTO diagnosis_candidate(job_id,rank,cause_code,cause_description,confidence,repair_guide)
 SELECT j.id,(c->>'rank')::integer,coalesce(c->>'causeCode',c->>'cause'),c->>'explanation',(c->>'confidence')::numeric,coalesce(j.result->>'repairMethod','UNKNOWN') FROM diagnosis_job j CROSS JOIN LATERAL jsonb_array_elements(j.result->'candidates') c;
INSERT INTO diagnosis_recommended_part(job_id,part_id,quantity) SELECT j.id,(p->>'partId')::bigint,(p->>'quantity')::integer FROM diagnosis_job j CROSS JOIN LATERAL jsonb_array_elements(j.result->'recommendedParts') p;
INSERT INTO diagnosis_evidence(candidate_id,attachment_id,source_uri,source_version,excerpt,annotation,details)
 SELECT c.id,(e->>'attachmentId')::bigint,e->>'sourceUri',e->>'sourceVersion',coalesce(e->>'citation',e->>'excerpt',e->>'type','UNKNOWN'),e->'annotation',e
 FROM diagnosis_job j JOIN diagnosis_candidate c ON c.job_id=j.id AND c.rank=(SELECT min(rank) FROM diagnosis_candidate WHERE job_id=j.id) CROSS JOIN LATERAL jsonb_array_elements(j.result->'evidence') e;
INSERT INTO repair_report(id,case_id,product_id,author_id,diagnosis_job_id,outcome,ai_assessment,comment,action_taken,version,latest_revision,created_at) OVERRIDING SYSTEM VALUE
 SELECT legacy_id('repair_report',r.id),legacy_id('repair_case',r.repair_id),c.product_id,legacy_id('app_user',v.author_id),
 (SELECT id FROM diagnosis_job WHERE case_id=c.id ORDER BY created_at DESC LIMIT 1),CASE WHEN (v.content->>'repairSuccessful')::boolean THEN 'FIXED' ELSE 'NOT_FIXED' END,
 v.content->>'aiAcceptance',v.content->>'technicianOpinion',v.content->>'actualAction',r.version,r.latest_revision,r.created_at
 FROM legacy_v3.report r JOIN legacy_v3.report_revision v ON v.report_id=r.id AND v.revision=coalesce(r.confirmed_revision,r.latest_revision) JOIN repair_case c ON c.id=legacy_id('repair_case',r.repair_id);
INSERT INTO repair_report_revision SELECT legacy_id('repair_report',report_id),revision,migrate_legacy_json(content),legacy_id('app_user',author_id),created_at,confirmed_at FROM legacy_v3.report_revision;
UPDATE repair_report r SET confirmed_revision=o.confirmed_revision,confirmed_at=v.confirmed_at FROM legacy_v3.report o LEFT JOIN legacy_v3.report_revision v ON v.report_id=o.id AND v.revision=o.confirmed_revision WHERE r.id=legacy_id('repair_report',o.id);
INSERT INTO confirmed_fault(report_id,product_id,component_id,fault_domain,cause_code,description,revision,ordinal,part_id,factory_id,factory_name,production_region)
 SELECT legacy_id('repair_report',f.report_id),r.product_id,legacy_id('product_component',f.component_id),CASE WHEN area IN ('PCB','MOTOR','SW','OTHER') THEN area ELSE 'OTHER' END,
 coalesce(v.content->>'actualCause','UNKNOWN'),coalesce(v.content->>'actualCause','UNKNOWN'),f.revision,f.ordinal,legacy_id('part',f.part_id),legacy_id('factory',f.factory_id),f.factory_name,f.region
 FROM legacy_v3.report_fault f JOIN repair_report r ON r.id=legacy_id('repair_report',f.report_id) JOIN legacy_v3.report_revision v ON v.report_id=f.report_id AND v.revision=f.revision;
INSERT INTO report_comment(id,report_id,revision,author_id,body,request_key,created_at) OVERRIDING SYSTEM VALUE SELECT legacy_id('report_comment',id),legacy_id('repair_report',report_id),revision,legacy_id('app_user',author_id),body,request_key,created_at FROM legacy_v3.report_comment;
INSERT INTO purchase_order(id,case_id,request_key,requested_by,status,decided_by,decision_note,decided_at,created_at) OVERRIDING SYSTEM VALUE
 SELECT legacy_id('purchase_order',o.id),c.id,o.id,coalesce(legacy_id('app_user',created_by),c.technician_id),CASE o.status WHEN 'PROPOSED' THEN 'PENDING' WHEN 'SHIPPED' THEN 'ORDERED' ELSE o.status END,legacy_id('app_user',decided_by),reason,decided_at,o.created_at FROM legacy_v3.purchase_order o JOIN repair_case c ON c.id=legacy_id('repair_case',o.repair_id);
INSERT INTO purchase_order_item(id,order_id,part_id,quantity,warehouse_id,factory_id) OVERRIDING SYSTEM VALUE SELECT legacy_id('purchase_order_item',id),legacy_id('purchase_order',order_id),legacy_id('part',part_id),quantity,legacy_id('warehouse',warehouse_id),legacy_id('factory',factory_id) FROM legacy_v3.order_item;
INSERT INTO procurement_job SELECT legacy_id('repair_case',repair_id),attempts,next_attempt_at,NULL,last_error,done FROM legacy_v3.procurement_job;
INSERT INTO stock_snapshot(id,case_id,body,created_at) OVERRIDING SYSTEM VALUE SELECT legacy_id('stock_snapshot',id),legacy_id('repair_case',repair_id),migrate_legacy_json(body),created_at FROM legacy_v3.stock_snapshot;
-- Preserve SSE cursor IDs; Kafka command rows get new IDs after the highest replay ID.
INSERT INTO outbox_event(id,case_id,event_key,event_type,payload,created_at) OVERRIDING SYSTEM VALUE SELECT id,legacy_id('repair_case',repair_id),gen_random_uuid(),event_type,migrate_legacy_json(payload),created_at FROM legacy_v3.sse_event;
SELECT setval(pg_get_serial_sequence('outbox_event','id'),greatest(coalesce((SELECT max(id) FROM outbox_event),0)+1,1),false);
INSERT INTO outbox_event(case_id,job_id,event_key,event_type,payload,topic,created_at,published_at,publish_attempts,next_attempt_at)
 SELECT j.case_id,j.id,o.id,o.body->>'eventType',jsonb_set(jsonb_set(migrate_legacy_json(o.body),'{schemaVersion}','2'),'{correlationId}',o.body->'correlationId'),replace(o.topic,'.v1','.v2'),o.created_at,o.published_at,o.attempts,o.next_attempt_at FROM legacy_v3.outbox o JOIN diagnosis_job j ON j.id=legacy_id('diagnosis_job',o.task_id);
INSERT INTO audit_log(actor_user_id,actor_service,action,entity_type,entity_id,detail,created_at)
 SELECT u.id,CASE WHEN u.id IS NULL THEN a.actor END,a.action,'LEGACY',coalesce((SELECT new_id FROM legacy_id_map WHERE old_id=a.resource_id LIMIT 1),0),a.details||jsonb_build_object('legacyResourceId',a.resource_id),a.created_at FROM legacy_v3.audit_log a LEFT JOIN app_user u ON u.email=a.actor;
SELECT setval(pg_get_serial_sequence('service_center','id'),greatest(coalesce((SELECT max(id) FROM service_center),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('app_user','id'),greatest(coalesce((SELECT max(id) FROM app_user),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('factory','id'),greatest(coalesce((SELECT max(id) FROM factory),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('part','id'),greatest(coalesce((SELECT max(id) FROM part),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('part_lot','id'),greatest(coalesce((SELECT max(id) FROM part_lot),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('product','id'),greatest(coalesce((SELECT max(id) FROM product),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('product_component','id'),greatest(coalesce((SELECT max(id) FROM product_component),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('warehouse','id'),greatest(coalesce((SELECT max(id) FROM warehouse),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('repair_case','id'),greatest(coalesce((SELECT max(id) FROM repair_case),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('diagnosis_job','id'),greatest(coalesce((SELECT max(id) FROM diagnosis_job),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('photo_request','id'),greatest(coalesce((SELECT max(id) FROM photo_request),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('attachment','id'),greatest(coalesce((SELECT max(id) FROM attachment),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('repair_report','id'),greatest(coalesce((SELECT max(id) FROM repair_report),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('report_comment','id'),greatest(coalesce((SELECT max(id) FROM report_comment),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('purchase_order','id'),greatest(coalesce((SELECT max(id) FROM purchase_order),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('purchase_order_item','id'),greatest(coalesce((SELECT max(id) FROM purchase_order_item),0)+1,1),false);
SELECT setval(pg_get_serial_sequence('stock_snapshot','id'),greatest(coalesce((SELECT max(id) FROM stock_snapshot),0)+1,1),false);

UPDATE outbox_event o SET payload=jsonb_set(jsonb_set(o.payload,'{payload,repair}',to_jsonb(c)),'{payload,task}',to_jsonb(j))
 FROM diagnosis_job j JOIN repair_case c ON c.id=j.case_id WHERE o.job_id=j.id AND o.topic IS NOT NULL AND o.published_at IS NULL;
UPDATE outbox_event o SET payload=jsonb_set(o.payload,'{payload,attachments}',coalesce((SELECT jsonb_agg(jsonb_build_object('id',a.id::text,'request_id',a.request_id::text,'kind',a.kind,'target',a.target,'mime_type',a.mime_type,'byte_size',a.byte_size,'sha256',a.sha256,'downloadPath','/internal/attachments/'||a.id) ORDER BY a.created_at,a.id) FROM attachment a WHERE a.case_id=o.case_id),'[]'))
 WHERE o.topic IS NOT NULL AND o.published_at IS NULL;
UPDATE outbox_event o SET payload=jsonb_set(o.payload,'{payload,requestKey}',to_jsonb(p.request_key::text)) FROM photo_request p WHERE o.topic IS NOT NULL AND o.published_at IS NULL AND o.payload->'payload'->>'requestId'=p.id::text;
DROP FUNCTION migrate_legacy_json(jsonb);
DROP FUNCTION legacy_id(text,uuid);
