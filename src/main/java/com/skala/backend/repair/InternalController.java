package com.skala.backend.repair;

import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/internal")
public class InternalController {
    final RepairQueries queries;final Catalog catalog;final RepairService repairs;final LocalFiles files;
    public InternalController(RepairQueries queries,Catalog catalog,RepairService repairs,LocalFiles files) {this.queries=queries;this.catalog=catalog;this.repairs=repairs;this.files=files;}
    @GetMapping("/tasks/{id}/input") public Map<String,Object> input(@PathVariable Long id) {return repairs.analysisInput(queries.taskRepairId(id),id);}
    @GetMapping("/attachments/{id}") public ResponseEntity<Resource> file(@PathVariable Long id) {return ApiController.attachment(queries.attachment(id,true),files);}
    @GetMapping("/products") public Map<String,Object> product(@RequestParam(required=false) String serial,@RequestParam(required=false) String serialNumber) {return catalog.product(serialNumber!=null?serialNumber:serial);}
    @GetMapping("/parts") public List<Map<String,Object>> parts() {return catalog.parts();}
    @GetMapping("/parts/{id}/availability") public Map<String,Object> availability(@PathVariable Long id) {return catalog.availability(id);}
    @GetMapping("/parts/{id}/inventory") public List<Map<String,Object>> stock(@PathVariable Long id) {return catalog.stocks(id);}
    @GetMapping("/parts/{id}/factories") public List<Map<String,Object>> factories(@PathVariable Long id) {return catalog.factories(id);}
    @GetMapping({"/confirmed-cases","/fault-cases"}) public List<Map<String,Object>> cases(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size) {
        return queries.confirmedCases(page,size);
    }
}
