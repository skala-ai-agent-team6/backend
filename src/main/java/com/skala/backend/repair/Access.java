package com.skala.backend.repair;

import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;

@Component
public class Access {
    final UserRepository users;
    final Store db;
    public Access(UserRepository users,Store db) { this.users=users; this.db=db; }
    public AppUser user() { var a=SecurityContextHolder.getContext().getAuthentication(); if(a==null) throw new ApiError(401,"UNAUTHENTICATED"); return users.findByEmail(a.getName()).filter(u->u.active).orElseThrow(()->new ApiError(401,"UNAUTHENTICATED")); }
    public void technician() { if(!user().role.equals("TECHNICIAN")) throw ApiError.denied(); }
    public void manager() { if(!user().role.equals("QUALITY_MANAGER")) throw ApiError.denied(); }
    public Map<String,Object> repair(Long id) {
        var r=db.one("SELECT * FROM repair_case WHERE id=?",id); var u=user();
        if(!u.role.equals("QUALITY_MANAGER") && !Objects.equals(u.centerId,r.get("center_id"))) throw ApiError.denied();
        return r;
    }
    public Map<String,Object> report(Long id) { var r=db.one("SELECT * FROM repair_report WHERE id=?",id); repair((Long)r.get("case_id")); return r; }
}
