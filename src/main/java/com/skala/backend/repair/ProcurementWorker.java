package com.skala.backend.repair;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Component
public class ProcurementWorker {
    final Store db; final BusinessMcp mcp; final OrderService orders; final TransactionTemplate tx;
    public ProcurementWorker(Store db,BusinessMcp mcp,OrderService orders,PlatformTransactionManager manager) {this.db=db;this.mcp=mcp;this.orders=orders;this.tx=new TransactionTemplate(manager);}
    @Scheduled(fixedDelayString="${app.procurement-delay:2000}") public void run() {
        var jobs=tx.execute(s->db.list("UPDATE procurement_job SET lease_until=now()+interval '60 seconds',attempts=attempts+1 WHERE case_id IN (SELECT case_id FROM procurement_job WHERE done=false AND attempts<3 AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *"));
        if(jobs==null) return;
        for(var job:jobs) {
            Long repair=(Long)job.get("case_id");
            try {
                var task=db.one("SELECT result FROM diagnosis_job WHERE case_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",repair);
                var recommended=Json.objects(((Map<?,?>)task.get("result")).get("recommendedParts"));
                var ids=recommended.stream().map(r->Json.id(r.get("partId"))).distinct().toList();
                Map<String,Object> result=mcp.lookup(ids); // No database transaction is held during the network call.
                var items=items(recommended,result);
                tx.executeWithoutResult(s->{
                    var current=db.one("SELECT * FROM procurement_job WHERE case_id=? FOR UPDATE",repair);
                    if(Boolean.TRUE.equals(current.get("done"))) return;
                    db.update("INSERT INTO stock_snapshot(case_id,body) VALUES (?,?::jsonb)",repair,Json.write(result));
                    if(!items.isEmpty()) orders.create(repair,items,null,"procurement-worker");
                    db.update("UPDATE procurement_job SET done=true,lease_until=NULL,last_error=NULL WHERE case_id=?",repair);
                    db.update("UPDATE repair_case SET procurement_status='SUCCEEDED' WHERE id=?",repair);
                    db.event(repair,"INVENTORY_COMPLETED",Map.of("repairId",repair));
                });
            } catch(Exception failure) {
                tx.executeWithoutResult(s->{
                    int changed=db.update("UPDATE procurement_job SET lease_until=NULL,last_error='MCP_OR_PROCUREMENT_FAILED',next_attempt_at=now()+interval '30 seconds' WHERE case_id=? AND done=false",repair);
                    if(changed==1) {
                        db.update("UPDATE repair_case SET procurement_status='FAILED' WHERE id=?",repair);
                        db.event(repair,"INVENTORY_FAILED",Map.of("repairId",repair,"attempt",job.get("attempts")));
                    }
                });
            }
        }
    }
    static List<Inputs.Item> items(List<Map<String,Object>> recommended,Map<String,Object> result) {
        var stocks=Json.objects(result.get("stocks"));var factories=Json.objects(result.get("factories"));var items=new ArrayList<Inputs.Item>();
        // Aggregate repeated AI part lines before choosing a supply source.
        Map<Long,Integer> quantities=new LinkedHashMap<>();
        for(var r:recommended) quantities.merge(Json.id(r.get("partId")),Json.number(r.get("quantity")),Math::addExact);
        for(var entry:quantities.entrySet()) {
            Long part=entry.getKey();int quantity=entry.getValue();
            var stock=stocks.stream().filter(r->part.equals(Json.id(r.get("partId"))) && Json.number(r.get("available"))>=quantity).findFirst();
            if(stock.isPresent()) items.add(new Inputs.Item(part,quantity,Inputs.Source.WAREHOUSE,Json.id(stock.get().get("warehouseId")),null));
            else {
                var factory=factories.stream().filter(r->part.equals(Json.id(r.get("partId")))).findFirst().orElseThrow(()->new IllegalStateException("NO_SOURCE"));
                items.add(new Inputs.Item(part,quantity,Inputs.Source.FACTORY,null,Json.id(factory.get("factoryId"))));
            }
        }
        return items;
    }
}
