package com.bankextractor.controller;

import com.bankextractor.export.ExportService;
import com.bankextractor.model.BankStatement;
import com.bankextractor.service.BankStatementService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/download")
public class DownloadController {

    private final BankStatementService service;
    private final ExportService exporter;

    public DownloadController(BankStatementService service, ExportService exporter) {
        this.service  = service;
        this.exporter = exporter;
    }

    @PostMapping("/json")
    public ResponseEntity<byte[]> downloadJson(@RequestParam("file") MultipartFile file) throws Exception {
        BankStatement statement = service.extract(file.getInputStream(), file.getOriginalFilename());
        String json = exporter.toJson(statement);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statement.json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes());
    }

    @PostMapping("/csv")
    public ResponseEntity<byte[]> downloadCsv(@RequestParam("file") MultipartFile file) throws Exception {
        BankStatement statement = service.extract(file.getInputStream(), file.getOriginalFilename());
        String csv = exporter.toCsv(statement);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statement.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.getBytes());
    }

    @PostMapping("/xlsx")
    public ResponseEntity<byte[]> downloadExcel(@RequestParam("file") MultipartFile file) throws Exception {
        BankStatement statement = service.extract(file.getInputStream(), file.getOriginalFilename());

        File tempOut = File.createTempFile("output_", ".xlsx");
        exporter.toExcelFile(statement, tempOut);
        byte[] bytes = java.nio.file.Files.readAllBytes(tempOut.toPath());
        tempOut.delete();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statement.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}