package com.bankextractor.export;

import com.bankextractor.model.BankStatement;
import com.bankextractor.model.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private final ObjectMapper objectMapper;

    public ExportService() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─── JSON ─────────────────────────────────────────────────────────────────

    public String toJson(BankStatement statement) throws IOException {
        return objectMapper.writeValueAsString(statement);
    }

    public void toJsonFile(BankStatement statement, File outputFile) throws IOException {
        objectMapper.writeValue(outputFile, statement);
        log.info("JSON written: {}", outputFile.getAbsolutePath());
    }

    // ─── CSV ──────────────────────────────────────────────────────────────────

    public String toCsv(BankStatement statement) throws IOException {
        StringWriter sw = new StringWriter();
        writeCsv(statement, sw);
        return sw.toString();
    }

    public void toCsvFile(BankStatement statement, File outputFile) throws IOException {
        try (FileWriter fw = new FileWriter(outputFile)) {
            writeCsv(statement, fw);
        }
        log.info("CSV written: {}", outputFile.getAbsolutePath());
    }

    private void writeCsv(BankStatement statement, Writer writer) throws IOException {
        try (CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(new String[]{
                    "Date", "Value Date", "Type", "Amount", "Balance",
                    "Currency", "Description", "Category", "Reference", "Page"
            });
            for (Transaction tx : statement.getTransactions()) {
                csv.writeNext(new String[]{
                        str(tx.getDate()),
                        str(tx.getValueDate()),
                        tx.getType()        != null ? tx.getType().name() : "",
                        str(tx.getAmount()),
                        str(tx.getBalance()),
                        tx.getCurrency()    != null ? tx.getCurrency()    : "",
                        tx.getDescription() != null ? tx.getDescription() : "",
                        tx.getCategory()    != null ? tx.getCategory()    : "",
                        tx.getReference()   != null ? tx.getReference()   : "",
                        String.valueOf(tx.getPageNumber())
                });
            }
        }
    }

    // ─── Excel ────────────────────────────────────────────────────────────────

    public void toExcelFile(BankStatement statement, File outputFile) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {

            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle moneyStyle  = createMoneyStyle(wb);
            CellStyle debitStyle  = createColorStyle(wb, IndexedColors.ROSE.index);
            CellStyle creditStyle = createColorStyle(wb, IndexedColors.LIGHT_GREEN.index);

            writeSummarySheet(wb.createSheet("Summary"),
                    statement, headerStyle, moneyStyle);
            writeTransactionsSheet(wb.createSheet("Transactions"),
                    statement, headerStyle, moneyStyle, debitStyle, creditStyle);
            writeCategorySheet(wb.createSheet("Categories"),
                    statement, headerStyle, moneyStyle);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                wb.write(fos);
            }
        }
        log.info("Excel written: {}", outputFile.getAbsolutePath());
    }

    // ─── Summary Sheet ────────────────────────────────────────────────────────

    private void writeSummarySheet(Sheet sheet, BankStatement stmt,
                                   CellStyle hdr, CellStyle money) {
        int row = 0;
        // ── Bank ──────────────────────────────────────────────────────────────
        row = addRow(sheet, row, "Bank Name",             stmt.getBankName(),                        null);
        row = addRow(sheet, row, "Bank Address",          stmt.getBankAddress(),                     null);
        row = addRow(sheet, row, "Bank Registration",     stmt.getBankRegistration(),                null);
        row = addRow(sheet, row, "Bank Capital",          stmt.getBankCapital(),                     null);
        row = addRow(sheet, row, "BIC / SWIFT",           stmt.getBic(),                             null);
        // ── Branch ────────────────────────────────────────────────────────────
        row = addRow(sheet, row, "Branch",                stmt.getBranch(),                          null);
        row = addRow(sheet, row, "Branch Address",        stmt.getBranchAddress(),                   null);
        row = addRow(sheet, row, "Branch Phone",          stmt.getBranchPhone(),                     null);
        row = addRow(sheet, row, "Branch Fax",            stmt.getBranchFax(),                       null);
        // ── Advisor ───────────────────────────────────────────────────────────
        row = addRow(sheet, row, "Advisor Name",          stmt.getAdvisorName(),                     null);
        row = addRow(sheet, row, "Advisor Phone",         stmt.getAdvisorPhone(),                    null);
        // ── Account holder ────────────────────────────────────────────────────
        row = addRow(sheet, row, "Account Holder",        stmt.getAccountHolder(),                   null);
        row = addRow(sheet, row, "Holder Address",        stmt.getAccountHolderAddress(),            null);
        row = addRow(sheet, row, "Client Code",           stmt.getClientCode(),                      null);
        // ── Account ───────────────────────────────────────────────────────────
        row = addRow(sheet, row, "Account Number",        stmt.getAccountNumber(),                   null);
        row = addRow(sheet, row, "IBAN",                  stmt.getIban(),                            null);
        row = addRow(sheet, row, "Account Type",          stmt.getAccountType(),                     null);
        row = addRow(sheet, row, "Currency",              stmt.getCurrency(),                        null);
        row = addRow(sheet, row, "RIB – Banque",          stmt.getRibBanque(),                       null);
        row = addRow(sheet, row, "RIB – Agence",          stmt.getRibAgence(),                       null);
        row = addRow(sheet, row, "RIB – Clé",             stmt.getRibCle(),                          null);
        // ── Statement ─────────────────────────────────────────────────────────
        row = addRow(sheet, row, "Statement From",        str(stmt.getStatementFrom()),              null);
        row = addRow(sheet, row, "Statement To",          str(stmt.getStatementTo()),                null);
        row = addRow(sheet, row, "Statement Reference",   stmt.getStatementReference(),              null);
        // ── Balances ──────────────────────────────────────────────────────────
        row = addRow(sheet, row, "Opening Balance",       null,  stmt.getOpeningBalance());
        row = addRow(sheet, row, "Closing Balance",       null,  stmt.getClosingBalance());
        row = addRow(sheet, row, "Total Credits",         null,  stmt.getTotalCredits());
        row = addRow(sheet, row, "Total Debits",          null,  stmt.getTotalDebits());
        row = addRow(sheet, row, "Total Transactions",    String.valueOf(stmt.getTransactionCount()), null);
        row = addRow(sheet, row, "Credit Count",          String.valueOf(stmt.getCreditCount()),      null);
        row = addRow(sheet, row, "Debit Count",           String.valueOf(stmt.getDebitCount()),       null);
        // ── Loyalty ───────────────────────────────────────────────────────────
        if (stmt.getLoyaltyProgramName() != null) {
            row++;
            row = addRow(sheet, row, "--- Loyalty Program ---", "", null);
            row++;
            row = addRow(sheet, row, "Program",               stmt.getLoyaltyProgramName(),          null);
            row = addRow(sheet, row, "Member Number",         stmt.getLoyaltyMemberNumber(),          null);
            row = addRow(sheet, row, "Points – Previous",
                    stmt.getLoyaltyPointsPrevious() != null ? stmt.getLoyaltyPointsPrevious().toString() : null, null);
            row = addRow(sheet, row, "Points – Earned",
                    stmt.getLoyaltyPointsEarned()   != null ? stmt.getLoyaltyPointsEarned().toString()   : null, null);
            row = addRow(sheet, row, "Points – Used",
                    stmt.getLoyaltyPointsUsed()     != null ? stmt.getLoyaltyPointsUsed().toString()     : null, null);
            row = addRow(sheet, row, "Points – New Total",
                    stmt.getLoyaltyPointsNew()      != null ? stmt.getLoyaltyPointsNew().toString()      : null, null);
        }
        // ── Extraction metadata ───────────────────────────────────────────────
        row = addRow(sheet, row, "Extraction Status",     str(stmt.getStatus()),                     null);

        // Warnings
        if (stmt.getWarnings() != null && !stmt.getWarnings().isEmpty()) {
            row++;
            addRow(sheet, row, "--- Warnings ---", "", null);
            row++;
            for (String warning : stmt.getWarnings()) {
                addRow(sheet, row, "Warning", warning, null);
                row++;
            }
        }

        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 10000);
    }

    private int addRow(Sheet sheet, int rowIdx, String label,
                       String textVal, BigDecimal numVal) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(label);
        if (textVal != null)      row.createCell(1).setCellValue(textVal);
        else if (numVal != null)  row.createCell(1).setCellValue(numVal.doubleValue());
        return rowIdx + 1;
    }

    // ─── Transactions Sheet ───────────────────────────────────────────────────

    private void writeTransactionsSheet(Sheet sheet, BankStatement stmt,
                                        CellStyle hdr, CellStyle money,
                                        CellStyle debit, CellStyle credit) {
        String[] headers = {
            "Date", "Value Date", "Type", "Amount", "Balance",
            "Currency", "Description", "Category", "Reference", "Page"
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
        }

        int rowIdx = 1;
        for (Transaction tx : stmt.getTransactions()) {
            Row row = sheet.createRow(rowIdx++);
            boolean isDebit = tx.getType() == Transaction.TransactionType.DEBIT;

            row.createCell(0).setCellValue(str(tx.getDate()));
            row.createCell(1).setCellValue(str(tx.getValueDate()));
            row.createCell(2).setCellValue(tx.getType() != null ? tx.getType().name() : "");

            if (tx.getAmount() != null) {
                Cell c = row.createCell(3);
                c.setCellValue(tx.getAmount().doubleValue());
                c.setCellStyle(isDebit ? debit : credit);
            }

            if (tx.getBalance() != null) {
                Cell c = row.createCell(4);
                c.setCellValue(tx.getBalance().doubleValue());
                c.setCellStyle(money);
            }

            row.createCell(5).setCellValue(tx.getCurrency()    != null ? tx.getCurrency()    : "");
            row.createCell(6).setCellValue(tx.getDescription() != null ? tx.getDescription() : "");
            row.createCell(7).setCellValue(tx.getCategory()    != null ? tx.getCategory()    : "");
            row.createCell(8).setCellValue(tx.getReference()   != null ? tx.getReference()   : "");
            row.createCell(9).setCellValue(tx.getPageNumber());
        }

        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
        sheet.createFreezePane(0, 1); // freeze header row
    }

    // ─── Categories Sheet ─────────────────────────────────────────────────────

    private void writeCategorySheet(Sheet sheet, BankStatement stmt,
                                    CellStyle hdr, CellStyle money) {
        String[] headers = {"Category", "Count", "Total Debits", "Total Credits"};

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
        }

        // Aggregate by category
        java.util.Map<String, long[]> catMap = new java.util.LinkedHashMap<>();
        for (Transaction tx : stmt.getTransactions()) {
            String cat = tx.getCategory() != null ? tx.getCategory() : "Other";
            catMap.computeIfAbsent(cat, k -> new long[3]);
            catMap.get(cat)[0]++;
            if (tx.getAmount() != null) {
                long cents = tx.getAmount()
                        .multiply(BigDecimal.valueOf(100)).longValue();
                if (tx.getType() == Transaction.TransactionType.DEBIT)
                    catMap.get(cat)[1] += cents;
                else
                    catMap.get(cat)[2] += cents;
            }
        }

        int rowIdx = 1;
        for (var entry : catMap.entrySet()) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(entry.getValue()[0]);
            row.createCell(2).setCellValue(entry.getValue()[1] / 100.0);
            row.createCell(3).setCellValue(entry.getValue()[2] / 100.0);
        }

        for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);
    }

    // ─── Style Helpers ────────────────────────────────────────────────────────

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.index);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.index);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private CellStyle createMoneyStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private CellStyle createColorStyle(Workbook wb, short color) {
        CellStyle style = createMoneyStyle(wb);
        style.setFillForegroundColor(color);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}