package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OrderService {
    final Store db; final Access access;
    public OrderService(Store db,Access access) {this.db=db;this.access=access;}
    @Transactional public Map<String,Object> propose(Long repair,Inputs.Proposal input) {
        access.technician();access.repair(repair);
        return create(repair,input.items(),access.user().id,access.user().email);
    }
    Map<String,Object> create(Long repair,List<Inputs.Item> items,Long actor,String actorName) {
        Long requester=actor==null?(Long)db.one("SELECT technician_id FROM repair_case WHERE id=?",repair).get("technician_id"):actor;
        Long order=db.insert("INSERT INTO purchase_order(case_id,request_key,requested_by) VALUES (?,?,?)",repair,UUID.randomUUID(),requester);
        for(var i:items) {
            if(i.quantity()<1 || (i.source()==Inputs.Source.WAREHOUSE && (i.warehouseId()==null || i.factoryId()!=null)) || (i.source()==Inputs.Source.FACTORY && (i.factoryId()==null || i.warehouseId()!=null))) throw ApiError.bad("ORDER_SOURCE");
            db.one("SELECT id FROM part WHERE id=?",i.partId());
            if(i.source()==Inputs.Source.WAREHOUSE) db.one("SELECT warehouse_id FROM inventory WHERE warehouse_id=? AND part_id=?",i.warehouseId(),i.partId());
            else db.one("SELECT factory_id FROM part_supplier WHERE factory_id=? AND part_id=?",i.factoryId(),i.partId());
            db.update("INSERT INTO purchase_order_item(order_id,part_id,quantity,warehouse_id,factory_id) VALUES (?,?,?,?,?)",order,i.partId(),i.quantity(),i.warehouseId(),i.factoryId());
        }
        db.event(repair,"ORDER_PROPOSED",Map.of("orderId",order));db.audit(actorName,"ORDER_PROPOSED",order,items);
        return raw(order);
    }
    Map<String,Object> raw(Long id) { var o=db.one("SELECT * FROM purchase_order WHERE id=?",id);o.put("items",db.list("SELECT * FROM purchase_order_item WHERE order_id=? ORDER BY warehouse_id,part_id,id",id));return o; }
    public Map<String,Object> get(Long id) {var o=raw(id);access.repair((Long)o.get("case_id"));return o;}
    @Transactional public Map<String,Object> transition(Long id,String action,Inputs.Decision decision) {
        if(action.equals("approve") || action.equals("reject")) access.manager();else access.technician();
        var order=get(id);order=db.one("SELECT * FROM purchase_order WHERE id=? FOR UPDATE",id);
        String status=(String)order.get("status");String target=switch(action) {case "approve"->"APPROVED";case "reject"->"REJECTED";case "cancel"->"CANCELLED";case "ship"->"ORDERED";case "receive"->"RECEIVED";default->throw ApiError.bad("ORDER_ACTION");};
        if(status.equals(target)) return raw(id);
        if((Set.of("approve","reject").contains(action) && !status.equals("PENDING")) || (action.equals("ship") && !status.equals("APPROVED")) || (action.equals("receive") && !status.equals("ORDERED")) || (action.equals("cancel") && !Set.of("PENDING","APPROVED").contains(status))) throw ApiError.conflict("ORDER_STATE");
        // Globally consistent stock lock ordering; grouped quantities include repeated lines.
        var stock=db.list("SELECT warehouse_id,part_id,sum(quantity)::integer AS quantity FROM purchase_order_item WHERE order_id=? AND source='WAREHOUSE' GROUP BY warehouse_id,part_id ORDER BY warehouse_id,part_id",id);
        for(var i:stock) {
            db.one("SELECT on_hand,reserved FROM inventory WHERE warehouse_id=? AND part_id=? FOR UPDATE",i.get("warehouse_id"),i.get("part_id"));
            int quantity=Json.number(i.get("quantity"));int changed=1;
            if(action.equals("approve")) changed=db.update("UPDATE inventory SET version=version+1,reserved=reserved+? WHERE warehouse_id=? AND part_id=? AND on_hand-reserved>=?",quantity,i.get("warehouse_id"),i.get("part_id"),quantity);
            if(action.equals("cancel") && status.equals("APPROVED")) changed=db.update("UPDATE inventory SET version=version+1,reserved=reserved-? WHERE warehouse_id=? AND part_id=? AND reserved>=?",quantity,i.get("warehouse_id"),i.get("part_id"),quantity);
            if(action.equals("ship")) changed=db.update("UPDATE inventory SET version=version+1,on_hand=on_hand-?,reserved=reserved-? WHERE warehouse_id=? AND part_id=? AND reserved>=? AND on_hand>=?",quantity,quantity,i.get("warehouse_id"),i.get("part_id"),quantity,quantity);
            if(changed!=1) throw ApiError.conflict("INSUFFICIENT_STOCK");
        }
        // Factory items only advance local state; no manufacturer order or payment is sent.
        if(action.equals("approve") || action.equals("reject")) db.update("UPDATE purchase_order SET version=version+1,status=?,decided_by=?,decided_at=now(),decision_note=? WHERE id=?",target,access.user().id,decision.reason(),id);
        else db.update("UPDATE purchase_order SET version=version+1,status=? WHERE id=?",target,id);
        db.event((Long)order.get("case_id"),(action.equals("ship")?"ORDER_SHIPPED":"ORDER_"+target),Map.of("orderId",id));
        db.audit(access.user().email,"ORDER_"+target,id,decision);return raw(id);
    }
}
