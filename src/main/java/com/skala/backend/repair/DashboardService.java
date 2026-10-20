package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class DashboardService {
    final Store db; final Access access;
    public DashboardService(Store db,Access access) {this.db=db;this.access=access;}
    public List<Map<String,Object>> counts(LocalDate from,LocalDate to,String group) {
        if(!to.isAfter(from)) throw ApiError.bad("DATE_RANGE");
        String expression=switch(group) {
            case "PERIOD"->"to_char(rr.confirmed_at AT TIME ZONE 'Asia/Seoul','YYYY-MM')";
            case "PART"->"coalesce(f.part_id::text,'UNKNOWN')";
            case "CENTER"->"r.center_id::text";
            case "FACTORY"->"coalesce(f.factory_id::text,'UNKNOWN')";
            case "REGION"->"f.production_region";
            case "AREA"->"f.fault_domain";
            default->throw ApiError.bad("DASHBOARD_GROUP");
        };
        var u=access.user();List<Object> args=new ArrayList<>();args.add(from.atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime());args.add(to.atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
        String scope="";if(u.role.equals("TECHNICIAN")){scope=" AND r.center_id=?";args.add(u.centerId);}
        return db.list("SELECT "+expression+" AS dimension,count(DISTINCT r.id) AS count FROM repair_report p JOIN repair_case r ON r.id=p.case_id JOIN repair_report_revision rr ON rr.report_id=p.id AND rr.revision=p.confirmed_revision JOIN confirmed_fault f ON f.report_id=rr.report_id AND f.revision=rr.revision WHERE rr.confirmed_at>=? AND rr.confirmed_at<?"+scope+" GROUP BY 1 ORDER BY 1",args.toArray());
    }
}
