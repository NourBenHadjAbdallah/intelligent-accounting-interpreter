package com.bankextractor.controller;

import com.bankextractor.model.BankStatement;
import com.bankextractor.model.ManualExtractionConfig;
import com.bankextractor.service.BankStatementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController @RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private final BankStatementService svc;
    private final ObjectMapper om;

    public ApiController(BankStatementService svc) {
        this.svc = svc;
        this.om  = new ObjectMapper(); this.om.findAndRegisterModules();
    }

    @GetMapping("/health") public ResponseEntity<?> health() { return ResponseEntity.ok(Map.of("status","running")); }

    @PostMapping("/extract")
    public ResponseEntity<?> extract(@RequestParam("file") MultipartFile file) {
        try {
            if(file.isEmpty()) return bad("No file provided.");
            if(!isPdf(file))   return bad("Only PDF files are supported.");
            log.info("extract: {} ({} bytes)", file.getOriginalFilename(), file.getSize());
            BankStatement s = svc.extract(file.getInputStream(), file.getOriginalFilename());
            log.info("extract done: {} txns status={}", s.getTransactionCount(), s.getStatus());
            return ResponseEntity.ok(s);
        } catch (Exception e) { log.error("extract failed",e); return err("Extraction failed: "+e.getMessage()); }
    }

    @PostMapping("/extract/manual")
    public ResponseEntity<?> extractManual(
            @RequestParam("file") MultipartFile file,
            @RequestParam("config") String cfgJson) {
        try {
            if(file.isEmpty()) return bad("No file provided.");
            if(!isPdf(file))   return bad("Only PDF files are supported.");
            if(cfgJson==null||cfgJson.isBlank()) return bad("config JSON required.");
            ManualExtractionConfig cfg;
            try { cfg = om.readValue(cfgJson, ManualExtractionConfig.class); }
            catch(Exception e) { return bad("Invalid config JSON: "+e.getMessage()); }
            // Validate
            if(cfg.isZoneOnly()) {
                if(cfg.getTableArea()==null) return bad("zoneOnly requires tableArea.");
            } else {
                if(cfg.getColumnBoundaries()==null||cfg.getColumnBoundaries().isEmpty())
                    return bad("columnBoundaries required (or set zoneOnly=true).");
            }
            log.info("extract/manual: {} config={}", file.getOriginalFilename(), cfg);
            BankStatement s = svc.extractManual(file.getInputStream(), file.getOriginalFilename(), cfg);
            log.info("extract/manual done: {} txns", s.getTransactionCount());
            return ResponseEntity.ok(s);
        } catch (Exception e) { log.error("extract/manual failed",e); return err("Manual extraction failed: "+e.getMessage()); }
    }

    private boolean isPdf(MultipartFile f){ String n=f.getOriginalFilename(); return n!=null&&n.toLowerCase().endsWith(".pdf"); }
    private ResponseEntity<?> bad(String m){ return ResponseEntity.badRequest().body(Map.of("error",m)); }
    private ResponseEntity<?> err(String m){ return ResponseEntity.internalServerError().body(Map.of("error",m)); }
}