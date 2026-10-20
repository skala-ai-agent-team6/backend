package com.skala.backend.repair;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.util.*;

@Component
@Profile("dev")
public class DevSeed implements ApplicationRunner {
    final Store db;final DataSource source;final PasswordEncoder encoder;final String password;
    public DevSeed(Store db,DataSource source,PasswordEncoder encoder,@Value("${app.seed-password}") String password) {this.db=db;this.source=source;this.encoder=encoder;this.password=password;}
    @Override @Transactional public void run(ApplicationArguments args) {
        if(password.length()<12) throw new IllegalArgumentException("DEV_PASSWORD requires at least 12 characters");
        new ResourceDatabasePopulator(new ClassPathResource("db/dev-seed.sql")).execute(source);
        String hash=encoder.encode(password);
        for(int n=1;n<=3;n++) {
            String name=n==3?"quality":"tech"+n;String role=n==3?"QUALITY_MANAGER":"TECHNICIAN";
            Long center=n==3?null:(long)n;
            db.update("INSERT INTO app_user(email,name,password_hash,role,center_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",name,name,hash,role,center);
        }
        Long user=(Long)db.one("SELECT id FROM app_user WHERE email='tech1'").get("id");
        if(db.list("SELECT id FROM repair_case LIMIT 1").isEmpty()) for(int n=1;n<=3;n++) {
            String status=n==1?"RECEIVED":n==2?"MANUAL_REVIEW":"DIAGNOSED";
            db.update("INSERT INTO repair_case(case_number,center_id,product_id,technician_id,symptom,error_codes,firmware_version,status) VALUES (?,1,1,?,'Seed demonstration','[\"E01\"]','1.0',?) ON CONFLICT DO NOTHING","SEED-"+n,user,status);
        }
        for(String table:List.of("service_center","factory","product_model","part","part_lot","product","product_component","warehouse"))
            db.one("SELECT setval(pg_get_serial_sequence('"+table+"','id'),greatest((SELECT max(id) FROM "+table+")+1,1),false)");
    }
}
