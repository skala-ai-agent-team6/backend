CREATE TABLE report_fault(report_id uuid NOT NULL, revision integer NOT NULL, ordinal integer NOT NULL,
 area text NOT NULL, part_id uuid REFERENCES part, component_id uuid REFERENCES product_component,
 factory_id uuid REFERENCES factory, factory_name text NOT NULL DEFAULT 'UNKNOWN', region text NOT NULL DEFAULT 'UNKNOWN',
 PRIMARY KEY(report_id,revision,ordinal), FOREIGN KEY(report_id,revision) REFERENCES report_revision);
CREATE INDEX report_confirmed ON report(confirmed_revision) WHERE confirmed_revision IS NOT NULL;
