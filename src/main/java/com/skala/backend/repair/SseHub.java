package com.skala.backend.repair;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseHub {
    final Store db; final Access access;
    final Map<SseEmitter,Connection> clients=new ConcurrentHashMap<>();
    static class Connection {final Long repair;long cursor;long heartbeat=0;Connection(Long repair,long cursor){this.repair=repair;this.cursor=cursor;}}
    public SseHub(Store db,Access access) {this.db=db;this.access=access;}
    public SseEmitter subscribe(Long repair,long cursor) {
        access.repair(repair);if(cursor<0) throw ApiError.bad("SSE_CURSOR");
        if(cursor>0 && db.list("SELECT id FROM outbox_event WHERE topic IS NULL AND id=? AND case_id=?",cursor,repair).isEmpty()) throw ApiError.bad("SSE_CURSOR");
        var emitter=new SseEmitter(1800000L);var c=new Connection(repair,cursor);
        emitter.onCompletion(()->clients.remove(emitter));emitter.onTimeout(()->{clients.remove(emitter);emitter.complete();});emitter.onError(e->clients.remove(emitter));
        clients.put(emitter,c);return emitter;
    }
    public List<Map<String,Object>> replay(Long repair,long cursor,int limit) {
        return db.list("SELECT * FROM outbox_event WHERE topic IS NULL AND case_id=? AND id>? ORDER BY id LIMIT ?",repair,cursor,limit);
    }
    @Scheduled(fixedDelay=1000) public void deliver() {
        for(var entry:clients.entrySet()) {
            var emitter=entry.getKey();var c=entry.getValue();
            try {
                for(var event:replay(c.repair,c.cursor,500)) {
                    emitter.send(SseEmitter.event().id(String.valueOf(event.get("id"))).name(String.valueOf(event.get("event_type"))).data(Json.write(event.get("payload"))));
                    c.cursor=((Number)event.get("id")).longValue();
                }
                if(System.currentTimeMillis()-c.heartbeat>15000) {emitter.send(SseEmitter.event().comment("heartbeat"));c.heartbeat=System.currentTimeMillis();}
            } catch(Exception e) {clients.remove(emitter);emitter.completeWithError(e);}
        }
    }
}
