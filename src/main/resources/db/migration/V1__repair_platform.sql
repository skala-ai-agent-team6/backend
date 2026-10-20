CREATE TABLE service_center(id uuid PRIMARY KEY, name text NOT NULL);
CREATE TABLE app_user(id uuid PRIMARY KEY, username varchar(100) NOT NULL UNIQUE, password_hash text NOT NULL,
 role text NOT NULL CHECK(role IN ('TECHNICIAN','QUALITY_MANAGER')), center_id uuid REFERENCES service_center,
 CHECK(role <> 'TECHNICIAN' OR center_id IS NOT NULL));
CREATE TABLE factory(id uuid PRIMARY KEY, name text NOT NULL, region text NOT NULL, contact text NOT NULL);
CREATE TABLE part(id uuid PRIMARY KEY, name text NOT NULL, factory_id uuid REFERENCES factory);
CREATE TABLE part_lot(id uuid PRIMARY KEY, part_id uuid NOT NULL REFERENCES part, factory_id uuid NOT NULL REFERENCES factory, code text NOT NULL UNIQUE);
CREATE TABLE product(id uuid PRIMARY KEY, serial text NOT NULL UNIQUE, model text NOT NULL, information jsonb NOT NULL);
CREATE TABLE product_component(id uuid PRIMARY KEY, product_id uuid NOT NULL REFERENCES product, part_id uuid NOT NULL REFERENCES part,
 lot_id uuid REFERENCES part_lot, installed_at timestamptz NOT NULL DEFAULT now(), removed_at timestamptz);
CREATE TABLE warehouse(id uuid PRIMARY KEY, name text NOT NULL);
CREATE TABLE inventory(warehouse_id uuid NOT NULL REFERENCES warehouse, part_id uuid NOT NULL REFERENCES part,
 on_hand integer NOT NULL CHECK(on_hand >= 0), reserved integer NOT NULL DEFAULT 0 CHECK(reserved >= 0 AND reserved <= on_hand), PRIMARY KEY(warehouse_id,part_id));
CREATE TABLE repair(id uuid PRIMARY KEY, center_id uuid NOT NULL REFERENCES service_center, product_id uuid NOT NULL REFERENCES product,
 created_by uuid NOT NULL REFERENCES app_user, symptoms text NOT NULL, error_codes jsonb NOT NULL, firmware text NOT NULL,
 user_input jsonb NOT NULL, product_snapshot jsonb NOT NULL, status text NOT NULL DEFAULT 'RECEIVED' CHECK(status IN ('RECEIVED','DIAGNOSING','MANUAL_REVIEW','DIAGNOSED','REPAIRED')),
 procurement_status text NOT NULL DEFAULT 'NOT_STARTED' CHECK(procurement_status IN ('NOT_STARTED','PENDING','SUCCEEDED','FAILED')),
 created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX repair_center_date ON repair(center_id,created_at DESC);
CREATE TABLE diagnosis_task(id uuid PRIMARY KEY, repair_id uuid NOT NULL REFERENCES repair, status text NOT NULL CHECK(status IN ('QUEUED','RUNNING','WAITING_INPUT','SUCCEEDED','FAILED','CANCELLED')),
 input_revision integer NOT NULL DEFAULT 1 CHECK(input_revision>0), execution_id uuid NOT NULL, last_sequence bigint NOT NULL DEFAULT 0,
 photo_round integer NOT NULL DEFAULT 0 CHECK(photo_round BETWEEN 0 AND 3), server_retries integer NOT NULL DEFAULT 0,
 stage text, error text, checkpoint text, result jsonb, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX one_active_task ON diagnosis_task(repair_id) WHERE status IN ('QUEUED','RUNNING','WAITING_INPUT');
CREATE TABLE photo_request(id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES diagnosis_task, round integer NOT NULL CHECK(round BETWEEN 1 AND 3),
 body_part text NOT NULL, description text NOT NULL, fulfilled_at timestamptz, submission_key uuid, UNIQUE(task_id,round));
CREATE TABLE attachment(id uuid PRIMARY KEY, repair_id uuid NOT NULL REFERENCES repair, request_id uuid REFERENCES photo_request,
 object_key text NOT NULL UNIQUE, original_name text NOT NULL, mime_type text NOT NULL, size bigint NOT NULL CHECK(size>0 AND size<=10485760), sha256 char(64) NOT NULL,
 created_by uuid NOT NULL REFERENCES app_user, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX attachment_repair ON attachment(repair_id);
CREATE TABLE outbox(id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES diagnosis_task, topic text NOT NULL, body jsonb NOT NULL,
 published_at timestamptz, attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_until timestamptz, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX outbox_pending ON outbox(next_attempt_at) WHERE published_at IS NULL;
CREATE TABLE received_event(event_id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES diagnosis_task, body jsonb NOT NULL,
 disposition text NOT NULL, received_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE tool_execution(event_id uuid PRIMARY KEY REFERENCES received_event, task_id uuid NOT NULL REFERENCES diagnosis_task, execution_id uuid NOT NULL, details jsonb NOT NULL);
CREATE TABLE sse_event(id bigserial PRIMARY KEY, repair_id uuid NOT NULL REFERENCES repair, event_type text NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX sse_replay ON sse_event(repair_id,id);
CREATE TABLE report(id uuid PRIMARY KEY, repair_id uuid NOT NULL UNIQUE REFERENCES repair, version bigint NOT NULL DEFAULT 0,
 latest_revision integer NOT NULL DEFAULT 0, confirmed_revision integer, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE report_revision(report_id uuid NOT NULL REFERENCES report, revision integer NOT NULL, content jsonb NOT NULL,
 author_id uuid NOT NULL REFERENCES app_user, created_at timestamptz NOT NULL DEFAULT now(), confirmed_at timestamptz,
 PRIMARY KEY(report_id,revision));
ALTER TABLE report ADD CONSTRAINT confirmed_revision_fk FOREIGN KEY(id,confirmed_revision) REFERENCES report_revision(report_id,revision);
CREATE TABLE report_comment(id uuid PRIMARY KEY, report_id uuid NOT NULL, revision integer NOT NULL, author_id uuid NOT NULL REFERENCES app_user,
 body varchar(4000) NOT NULL CHECK(length(trim(body))>0), request_key uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(report_id,revision) REFERENCES report_revision, UNIQUE(report_id,request_key));
CREATE TABLE procurement_job(repair_id uuid PRIMARY KEY REFERENCES repair, attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_until timestamptz, last_error text, done boolean NOT NULL DEFAULT false);
CREATE TABLE stock_snapshot(id uuid PRIMARY KEY, repair_id uuid NOT NULL REFERENCES repair, body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE purchase_order(id uuid PRIMARY KEY, repair_id uuid NOT NULL REFERENCES repair, status text NOT NULL DEFAULT 'PROPOSED'
 CHECK(status IN ('PROPOSED','APPROVED','REJECTED','CANCELLED','SHIPPED')), created_by uuid REFERENCES app_user,
 decided_by uuid REFERENCES app_user, decided_at timestamptz, reason text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE order_item(id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES purchase_order, part_id uuid NOT NULL REFERENCES part,
 quantity integer NOT NULL CHECK(quantity>0), source text NOT NULL CHECK(source IN ('WAREHOUSE','FACTORY')),
 warehouse_id uuid REFERENCES warehouse, factory_id uuid REFERENCES factory,
 CHECK((source='WAREHOUSE' AND warehouse_id IS NOT NULL AND factory_id IS NULL) OR (source='FACTORY' AND factory_id IS NOT NULL AND warehouse_id IS NULL)));
CREATE TABLE audit_log(id bigserial PRIMARY KEY, actor text NOT NULL, action text NOT NULL, resource_id uuid NOT NULL, details jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
