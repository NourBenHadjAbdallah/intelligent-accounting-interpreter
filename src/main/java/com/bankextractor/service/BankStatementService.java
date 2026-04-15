package com.bankextractor.service;

import org.springframework.stereotype.Service;
import com.bankextractor.model.*;
import com.bankextractor.parser.*;
import com.bankextractor.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

@Service
public class BankStatementService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementService.class);
    private static final int TABULA_MIN_TX       = 1;
    private static final int TABULA_TIMEOUT_SEC  = 40;   // whole Tabula phase

    private final ExtractionConfig config;

    public BankStatementService()                           { this.config = new ExtractionConfig(); }
    public BankStatementService(ExtractionConfig config)    { this.config = config; }

    // ── Public API ────────────────────────────────────────────────────────────
    public BankStatement extract(String path)                              throws IOException { return extract(new File(path)); }
    public BankStatement extract(File f)                                   throws IOException { return doExtract(f,null,null,null); }
    public BankStatement extract(File f, String pwd)                       throws IOException { return doExtract(f,pwd,null,null); }
    public BankStatement extract(InputStream s, String name)               throws IOException { return withTempFile(s,"bse_",name,null); }
    public BankStatement extractManual(InputStream s,String name,ManualExtractionConfig m) throws IOException { return withTempFile(s,"bse_manual_",name,m); }

    private BankStatement withTempFile(InputStream s,String pfx,String name,ManualExtractionConfig m) throws IOException {
        File tmp = File.createTempFile(pfx,".pdf");
        try (var fos=new FileOutputStream(tmp)) { s.transferTo(fos); }
        try { BankStatement r=doExtract(tmp,null,name,m); r.setSourceFile(name); return r; }
        finally { tmp.delete(); }
    }

    // ── Core ──────────────────────────────────────────────────────────────────
    private BankStatement doExtract(File f,String pwd,String name,ManualExtractionConfig mcfg) throws IOException {
        BankStatement stmt = new BankStatement();
        stmt.setSourceFile(name!=null?name:f.getName());
        List<String> warn = new ArrayList<>();
        stmt.setWarnings(warn);

        try {
            parseHeader(f,pwd,stmt,warn);
            if (stmt.getStatus()==BankStatement.ExtractionStatus.FAILED) return stmt;

            List<Transaction> txns;

            if (mcfg != null) {
                if (mcfg.isZoneOnly()) {
                    // Zone-only: PDFBox region extraction
                    log.info("Zone-only extraction: {}", mcfg.getTableArea());
                    txns = extractZone(f,pwd,stmt,warn,mcfg);
                    if (txns.isEmpty()) warn.add("Zone extraction found no transactions — try a wider selection.");
                } else {
                    // Manual columns: Tabula BEA with timeout
                    log.info("Manual column extraction: {}", mcfg);
                    txns = runWithTimeout(() -> extractTabulaManual(f,stmt,warn,mcfg),
                                         TABULA_TIMEOUT_SEC, "Tabula-manual", warn);
                    if (txns.isEmpty()) warn.add("Manual column extraction found no transactions.");
                }
            } else {
                // AUTO: Tabula with timeout → PDFBox fallback
                log.info("Auto extraction starting…");
                txns = runWithTimeout(() -> extractTabula(f,stmt,warn),
                                      TABULA_TIMEOUT_SEC, "Tabula-auto", warn);

                if (txns.size() < TABULA_MIN_TX) {
                    log.info("Tabula found {} — falling back to PDFBox", txns.size());
                    if (warn.stream().noneMatch(w->w.contains("PDFBox")))
                        warn.add("Tabula found no tables — using PDFBox text-line parser.");
                    txns = extractPdfBox(f,pwd,stmt,warn);
                } else {
                    log.info("Tabula found {} transactions", txns.size());
                }
            }

            stmt.setTransactions(txns);
            computeTotals(stmt);
            if (txns.isEmpty()) warn.add("No transactions found. Try drawing a zone around the table.");
            if (config.isValidateBalances()) validateBalances(stmt,warn);
            stmt.setStatus(warn.isEmpty() ? BankStatement.ExtractionStatus.SUCCESS
                                          : BankStatement.ExtractionStatus.PARTIAL);
            log.info("Done: {} txns, status={}", txns.size(), stmt.getStatus());

        } catch (Exception e) {
            log.error("Extraction failed", e);
            stmt.setStatus(BankStatement.ExtractionStatus.FAILED);
            stmt.getWarnings().add("Extraction failed: "+e.getMessage());
        }
        return stmt;
    }

    // ── Timeout wrapper ───────────────────────────────────────────────────────
    private List<Transaction> runWithTimeout(
            java.util.concurrent.Callable<List<Transaction>> task,
            int timeoutSec, String label, List<String> warn) {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, label); t.setDaemon(true); return t;
        });
        try {
            return ex.submit(task).get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("{} timed out after {}s → falling back", label, timeoutSec);
            warn.add(label+" timed out — switching to PDFBox parser.");
            return Collections.emptyList();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            log.warn("{} error: {}", label, c!=null?c.getMessage():e.getMessage());
            warn.add(label+" error: "+(c!=null?c.getMessage():e.getMessage()));
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally { ex.shutdownNow(); }
    }

    // ── Header ────────────────────────────────────────────────────────────────
    private void parseHeader(File f,String pwd,BankStatement stmt,List<String> warn) {
        try (PdfTextExtractor pdf = pwd!=null?new PdfTextExtractor(f,pwd):new PdfTextExtractor(f)) {
            stmt.setTotalPages(pdf.getPageCount());
            stmt.setRawMetadata(pdf.getMetadata());
            int end = config.getEndPage()<0?pdf.getPageCount():config.getEndPage();
            List<String> pages = pdf.extractPageRange(config.getStartPage(), end);
            if (pages.isEmpty()) { fail(stmt,warn,"No text extracted — PDF may be image-based."); return; }
            String full = String.join("\n", pages);
            if (full.trim().length()<50) { fail(stmt,warn,"PDF has very little text — may be scanned."); return; }
            boolean eu = config.isUseEuropeanNumberFormat() || AmountParser.detectEuropeanFormat(full);
            DateParser dp   = new DateParser(config.getDateFormats());
            AmountParser ap = new AmountParser(eu);
            List<String> lines = new ArrayList<>();
            for (String pg:pages) lines.addAll(Arrays.asList(pg.split("\n",-1)));
            new StatementHeaderParser(dp,ap).parseHeader(stmt,lines);
            if (stmt.getCurrency()==null)
                stmt.setCurrency(config.getExpectedCurrency()!=null?config.getExpectedCurrency():"EUR");
        } catch (IOException e) {
            log.warn("Header parse error: {}", e.getMessage());
            warn.add("Header warning: "+e.getMessage());
            if (stmt.getTotalPages()==0) fail(stmt,warn,null);
        }
    }
    private void fail(BankStatement s,List<String> w,String msg) {
        s.setStatus(BankStatement.ExtractionStatus.FAILED); if(msg!=null) w.add(msg);
    }

    // ── Tabula auto ───────────────────────────────────────────────────────────
    private List<Transaction> extractTabula(File f,BankStatement stmt,List<String> warn) {
        List<Transaction> txns = new ArrayList<>();
        TabulaRowParser rp = new TabulaRowParser(config);
        try (TabulaTableExtractor tab = new TabulaTableExtractor(f)) {
            Map<Integer,List<List<String>>> pages = tab.extractAllPages();
            if (pages.isEmpty()) { log.info("Tabula: no tables detected"); return txns; }
            for (var e:pages.entrySet()) {
                List<Transaction> pt = rp.parseRows(e.getValue(), e.getKey());
                pt.forEach(tx->currency(tx,stmt)); txns.addAll(pt);
            }
        } catch (Exception e) {
            log.warn("Tabula error: {}", e.getMessage());
            warn.add("Tabula warning: "+e.getMessage());
        }
        return txns;
    }

    // ── Tabula manual ─────────────────────────────────────────────────────────
    private List<Transaction> extractTabulaManual(File f,BankStatement stmt,List<String> warn,ManualExtractionConfig mcfg) {
        List<Transaction> txns = new ArrayList<>();
        TabulaRowParser rp = new TabulaRowParser(config);
        try (TabulaTableExtractor tab = new TabulaTableExtractor(f)) {
            Map<Integer,List<List<String>>> pages = tab.extractManual(mcfg);
            if (pages.isEmpty()) return txns;
            for (var e:pages.entrySet()) {
                List<Transaction> pt = mcfg.isHasHeaderRow()
                    ? rp.parseRows(e.getValue(),e.getKey())
                    : rp.parseRowsNoHeader(e.getValue(),e.getKey());
                pt.forEach(tx->currency(tx,stmt)); txns.addAll(pt);
            }
        } catch (Exception e) {
            log.error("Tabula manual failed", e); warn.add("Manual error: "+e.getMessage());
        }
        return txns;
    }

    // ── PDFBox full-page fallback ─────────────────────────────────────────────
    private List<Transaction> extractPdfBox(File f,String pwd,BankStatement stmt,List<String> warn) throws IOException {
        List<Transaction> txns = new ArrayList<>();
        try (PdfTextExtractor pdf = pwd!=null?new PdfTextExtractor(f,pwd):new PdfTextExtractor(f)) {
            int start=config.getStartPage(), end=config.getEndPage()<0?pdf.getPageCount():config.getEndPage();
            List<String> pages = pdf.extractPageRange(start,end);
            BigDecimal tD=null,tC=null;
            for (int i=0;i<pages.size();i++) {
                int pn=start+i; TransactionLineParser lp=new TransactionLineParser(config); LocalDate last=null;
                for (int j=0;j<pages.get(i).split("\n",-1).length;j++) {
                    String line=pages.get(i).split("\n",-1)[j];
                    Transaction tx=lp.parseLine(line,pn,j+1,last);
                    if(tx!=null){currency(tx,stmt);txns.add(tx);if(tx.getDate()!=null)last=tx.getDate();}
                }
                Transaction pen=lp.flush(); if(pen!=null){currency(pen,stmt);txns.add(pen);}
                if(lp.getParsedTotalDebits()!=null)  tD=lp.getParsedTotalDebits();
                if(lp.getParsedTotalCredits()!=null) tC=lp.getParsedTotalCredits();
            }
            if(stmt.getTotalDebits()==null||stmt.getTotalDebits().compareTo(BigDecimal.ZERO)==0) if(tD!=null) stmt.setTotalDebits(tD);
            if(stmt.getTotalCredits()==null||stmt.getTotalCredits().compareTo(BigDecimal.ZERO)==0) if(tC!=null) stmt.setTotalCredits(tC);
        }
        return txns;
    }

    // ── Zone-only extraction ──────────────────────────────────────────────────
    private List<Transaction> extractZone(File f,String pwd,BankStatement stmt,List<String> warn,ManualExtractionConfig mcfg) throws IOException {
        List<Transaction> txns = new ArrayList<>();
        ManualExtractionConfig.TableArea a = mcfg.getTableArea();
        List<Integer> pages = resolvePages(mcfg.getPages(), stmt.getTotalPages());
        try (PdfTextExtractor pdf = pwd!=null?new PdfTextExtractor(f,pwd):new PdfTextExtractor(f)) {
            for (int pn:pages) {
                String text = pdf.extractRegion(pn,(float)a.getLeft(),(float)a.getTop(),
                    (float)(a.getRight()-a.getLeft()),(float)(a.getBottom()-a.getTop()));
                if(text==null||text.isBlank()){log.info("Zone page {}: no text",pn);continue;}
                TransactionLineParser lp=new TransactionLineParser(config); LocalDate last=null;
                String[] lines=text.split("\n",-1);
                for(int i=0;i<lines.length;i++){
                    Transaction tx=lp.parseLine(lines[i],pn,i+1,last);
                    if(tx!=null){currency(tx,stmt);txns.add(tx);if(tx.getDate()!=null)last=tx.getDate();}
                }
                Transaction pen=lp.flush(); if(pen!=null){currency(pen,stmt);txns.add(pen);}
                log.info("Zone page {}: {} txns from {} lines",pn,txns.size(),lines.length);
            }
        }
        return txns;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void currency(Transaction tx,BankStatement s){if(tx.getCurrency()==null||tx.getCurrency().isBlank())tx.setCurrency(s.getCurrency());}

    private List<Integer> resolvePages(List<Integer> req,int total){
        if(req==null||req.isEmpty()){List<Integer> a=new ArrayList<>();for(int p=1;p<=total;p++)a.add(p);return a;}
        return req.stream().filter(p->p>=1&&p<=total).distinct().sorted().toList();
    }

    private void computeTotals(BankStatement s){
        BigDecimal cr=BigDecimal.ZERO,db=BigDecimal.ZERO;
        for(Transaction t:s.getTransactions()){if(t.getAmount()==null)continue;
            if(t.getType()==Transaction.TransactionType.CREDIT)cr=cr.add(t.getAmount());
            else if(t.getType()==Transaction.TransactionType.DEBIT)db=db.add(t.getAmount());}
        if(s.getTotalCredits()==null||s.getTotalCredits().compareTo(BigDecimal.ZERO)==0)s.setTotalCredits(cr);
        if(s.getTotalDebits()==null||s.getTotalDebits().compareTo(BigDecimal.ZERO)==0)s.setTotalDebits(db);
    }

    private void validateBalances(BankStatement s,List<String> w){
        if(s.getOpeningBalance()==null||s.getClosingBalance()==null){w.add("Cannot validate balances.");return;}
        BigDecimal exp=s.getOpeningBalance().add(s.getTotalCredits()).subtract(s.getTotalDebits());
        if(exp.subtract(s.getClosingBalance()).abs().compareTo(new BigDecimal("0.10"))>0)
            w.add(String.format("Balance mismatch: expected %.2f got %.2f",exp,s.getClosingBalance()));
    }
}