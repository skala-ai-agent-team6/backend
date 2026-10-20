package com.skala.backend.repair;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Profile("dev")
@ConditionalOnProperty(name="app.mock-ai.enabled",havingValue="true",matchIfMissing=true)
class MockAi {
    final KafkaTemplate<String,String> kafka;
    MockAi(KafkaTemplate<String,String> kafka) {this.kafka=kafka;}
    @RetryableTopic(attempts="4",backOff=@BackOff(delay=1000,multiplier=2),retryTopicSuffix=".retry",dltTopicSuffix=".dlt",numPartitions="3",replicationFactor="1")
    @KafkaListener(topics="${app.commands-topic}",groupId="development-mock-ai-v2")
    public void command(ConsumerRecord<String,String> record) throws Exception {
        var command=Json.object(record.value());var payload=(Map<?,?>)command.get("payload");var repair=(Map<?,?>)payload.get("repair");var input=(Map<?,?>)repair.get("user_input");
        String scenario=String.valueOf(input.get("scenario"));int revision=Json.number(command.get("inputRevision"));
        send(command,1,"DIAGNOSIS_STARTED",Map.of("stage","STARTED"));
        send(command,2,"DIAGNOSIS_PROGRESS",Map.of("stage","INSPECTING_PHOTOS"));
        send(command,3,"TOOL_EXECUTED",Map.of("tool","scripted-fixture","toolVersion","dev-1","modelVersion","none","output",Map.of("scenario",scenario)));
        if((scenario.equals("photo") && revision==1) || (scenario.equals("manual") && revision<=3)) {
            UUID request=UUID.nameUUIDFromBytes((command.get("eventId")+":request").getBytes(StandardCharsets.UTF_8));
            send(command,4,"PHOTO_REQUESTED",Map.of("requestKey",request,"round",revision,"bodyPart","wheel","description","바퀴 하부 사진을 추가해 주세요","checkpoint",command.get("taskId")+":checkpoint"));
        } else if(scenario.equals("manual")) send(command,4,"DIAGNOSIS_FAILED",Map.of("errorCode","INCONCLUSIVE_AFTER_THIRD_PHOTO"));
        else {
            var attachments=(List<?>)payload.get("attachmentIds");
            var evidence=attachments.isEmpty()?List.of():List.of(Map.of("type","PHOTO","attachmentId",attachments.getFirst(),"annotation",Map.of("x",0.1,"y",0.2,"width",0.3,"height",0.3)),Map.of("type","MANUAL","citation","DEMO-MANUAL §2"));
            send(command,4,"DIAGNOSIS_COMPLETED",Map.of("summary","개발용 고정 진단: 바퀴 모터 확인","modelVersion","scripted-dev-1","candidates",List.of(Map.of("rank",1,"cause","Wheel motor obstruction","confidence",0.85,"explanation","고정된 개발용 시나리오")),"repairMethod","바퀴 모터 교체","recommendedParts",List.of(Map.of("partId","1","quantity",1)),"evidence",evidence));
        }
    }
    void send(Map<String,Object> command,int sequence,String type,Object payload) throws Exception {
        var body=new LinkedHashMap<String,Object>(command);
        body.put("eventId",UUID.nameUUIDFromBytes((command.get("eventId")+":"+sequence).getBytes(StandardCharsets.UTF_8)));body.put("eventType",type);body.put("sequence",sequence);body.put("occurredAt",Instant.now().toString());body.put("payload",payload);
        kafka.send("diagnosis.events.v2",String.valueOf(command.get("taskId")),Json.write(body)).get(10,TimeUnit.SECONDS);
    }
}
