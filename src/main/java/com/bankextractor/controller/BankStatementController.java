package com.bankextractor.controller;

import com.bankextractor.export.ExportService;
import com.bankextractor.model.BankStatement;
import com.bankextractor.service.BankStatementService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

@Controller
public class BankStatementController {

    private final BankStatementService service;
    private final ExportService exporter;

    public BankStatementController(BankStatementService service, ExportService exporter) {
        this.service  = service;
        this.exporter = exporter;
    }

    // ── Upload page ───────────────────────────────────────────────────────────
    @GetMapping("/")
    public String index() {
        return "index";
    }

    // ── Extract and show results ──────────────────────────────────────────────
    @PostMapping("/extract")
    public String extract(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "format", defaultValue = "xlsx") String format,
                          Model model) {
        try {
            if (file.isEmpty()) {
                model.addAttribute("error", "Please select a PDF file.");
                return "index";
            }

            if (!file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
                model.addAttribute("error", "Only PDF files are supported.");
                return "index";
            }

            File tempFile = File.createTempFile("statement_", ".pdf");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(file.getBytes());
            }

            BankStatement statement = service.extract(tempFile);
            tempFile.delete();

            model.addAttribute("statement", statement);
            model.addAttribute("format", format);
            model.addAttribute("fileName", file.getOriginalFilename());

        } catch (Exception e) {
            model.addAttribute("error", "Extraction failed: " + e.getMessage());
            return "index";
        }

        return "result";
    }

    // ── Debug: shows raw text extracted from the PDF ──────────────────────────
    @PostMapping("/debug")
    @ResponseBody
    public String debug(@RequestParam("file") MultipartFile file) throws Exception {
        File tempFile = File.createTempFile("debug_", ".pdf");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(file.getBytes());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body>");
        sb.append("<style>body{font-family:monospace;font-size:13px;padding:20px;background:#1e1e1e;color:#d4d4d4;}");
        sb.append("h2{color:#569cd6;} h3{color:#4ec9b0;border-top:1px solid #555;padding-top:10px;}");
        sb.append(".line{padding:1px 0;} .line:hover{background:#2d2d2d;}");
        sb.append(".num{color:#858585;min-width:40px;display:inline-block;}</style>");

        try (com.bankextractor.parser.PdfTextExtractor extractor =
                     new com.bankextractor.parser.PdfTextExtractor(tempFile)) {

            sb.append("<h2>📄 Raw PDF Text — ").append(file.getOriginalFilename()).append("</h2>");
            sb.append("<p>Total Pages: <b>").append(extractor.getPageCount()).append("</b></p>");

            List<String> pages = extractor.extractAllPages();
            for (int i = 0; i < pages.size(); i++) {
                sb.append("<h3>═══ PAGE ").append(i + 1).append(" ═══</h3>");
                String[] lines = pages.get(i).split("\n", -1);
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j]
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace(" ", "&nbsp;");
                    sb.append("<div class='line'><span class='num'>")
                      .append(j + 1).append("</span>&nbsp;")
                      .append(line).append("</div>");
                }
            }
        } finally {
            tempFile.delete();
        }

        sb.append("</body></html>");
        return sb.toString();
    }
}