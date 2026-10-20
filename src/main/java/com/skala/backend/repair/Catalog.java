package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class Catalog {
    final Store db;
    public Catalog(Store db) {this.db=db;}
    public List<Map<String,Object>> parts() {return db.list("SELECT * FROM part ORDER BY part_number,id");}
    public Map<String,Object> product(String serial) {
        if(serial==null || serial.isBlank()) throw ApiError.bad("SERIAL_REQUIRED");
        var p=db.one("SELECT p.*,m.model_code,m.name AS model_name FROM product p JOIN product_model m ON m.id=p.model_id WHERE p.serial_number=?",serial);
        p.put("components",db.list("SELECT c.*,l.lot_number,l.factory_id,f.name AS factory_name,f.region FROM product_component c LEFT JOIN part_lot l ON l.id=c.lot_id LEFT JOIN factory f ON f.id=l.factory_id WHERE c.product_id=? AND c.removed_at IS NULL ORDER BY c.slot_code,c.id",p.get("id")));
        p.put("compatibleParts",db.list("SELECT p.* FROM model_part m JOIN part p ON p.id=m.part_id WHERE m.model_id=? ORDER BY p.id",p.get("model_id")));return p;
    }
    public List<Map<String,Object>> stocks(Long part) {return db.list("SELECT i.*,i.on_hand-i.reserved AS available,w.name AS warehouse_name,w.region,w.address,w.phone FROM inventory i JOIN warehouse w ON w.id=i.warehouse_id WHERE i.part_id=? ORDER BY warehouse_id",part);}
    public List<Map<String,Object>> factories(Long part) {return db.list("SELECT f.*,s.lead_days FROM part_supplier s JOIN factory f ON f.id=s.factory_id WHERE s.part_id=? ORDER BY s.lead_days NULLS LAST,f.id",part);}
    public Map<String,Object> availability(Long part) {return Map.of("part",db.one("SELECT * FROM part WHERE id=?",part),"inventory",stocks(part),"factories",factories(part));}
}
