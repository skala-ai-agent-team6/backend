ALTER TABLE part_lot ADD CONSTRAINT lot_id_part UNIQUE(id,part_id);
ALTER TABLE product_component ADD CONSTRAINT installed_lot_part_fk FOREIGN KEY(lot_id,part_id) REFERENCES part_lot(id,part_id);
CREATE INDEX task_repair_created ON diagnosis_task(repair_id,created_at DESC);
CREATE INDEX photo_task ON photo_request(task_id);
CREATE INDEX order_repair_created ON purchase_order(repair_id,created_at DESC);
CREATE INDEX order_items_order ON order_item(order_id);
CREATE INDEX comment_report_date ON report_comment(report_id,created_at);
CREATE INDEX component_product ON product_component(product_id);
CREATE INDEX procurement_pending ON procurement_job(next_attempt_at) WHERE done=false;
