package com.bankextractor.parser;

import com.bankextractor.model.ManualExtractionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import technology.tabula.*;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

public class TabulaTableExtractor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TabulaTableExtractor.class);
    private static final int PAGE_TIMEOUT_SECONDS = 20;
    private static final int MIN_COLUMNS = 2, MIN_ROWS = 2, COLLAPSED_THRESHOLD = 3;

    private static final List<String> HEADER_KW = List.of(
        "date","valeur","nature","débit","debit","crédit","credit",
        "solde","montant","libellé","libelle","opération","operation",
        "amount","balance","description","particulars","withdrawal","deposit","reference","type"
    );

    private final ObjectExtractor oe;
    private final org.apache.pdfbox.pdmodel.PDDocument doc;
    private final File tmp;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r,"tabula-page"); t.setDaemon(true); return t;
    });

    public TabulaTableExtractor(File f) throws IOException {
        this.doc = org.apache.pdfbox.pdmodel.PDDocument.load(f);
        this.oe  = new ObjectExtractor(doc); this.tmp = null;
        log.info("Tabula opened: {} ({} pages)", f.getName(), doc.getNumberOfPages());
    }
    public TabulaTableExtractor(InputStream s) throws IOException {
        this.tmp = File.createTempFile("tabula_",".pdf");
        Files.copy(s, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        this.doc = org.apache.pdfbox.pdmodel.PDDocument.load(tmp);
        this.oe  = new ObjectExtractor(doc);
    }

    public Map<Integer,List<List<String>>> extractAllPages() {
        Map<Integer,List<List<String>>> res = new LinkedHashMap<>();
        for (int pn = 1; pn <= doc.getNumberOfPages(); pn++) {
            final int p = pn;
            Future<List<List<String>>> f = exec.submit(() -> extractPage(oe.extract(p)));
            try {
                List<List<String>> rows = f.get(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!rows.isEmpty()) res.put(pn, rows);
            } catch (TimeoutException e) {
                f.cancel(true);
                log.warn("Page {} timed out after {}s — skipped", pn, PAGE_TIMEOUT_SECONDS);
            } catch (ExecutionException e) {
                log.warn("Page {} error: {}", pn, e.getCause()!=null?e.getCause().getMessage():e.getMessage());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return res;
    }

    public Map<Integer,List<List<String>>> extractManual(ManualExtractionConfig cfg) {
        if (cfg==null||cfg.getColumnBoundaries()==null||cfg.getColumnBoundaries().isEmpty())
            throw new IllegalArgumentException("columnBoundaries required");
        List<Float> cols = cfg.getColumnBoundaries().stream().sorted().map(Double::floatValue)
            .collect(java.util.stream.Collectors.toList());
        List<Integer> pages = resolvePages(cfg.getPages());
        Map<Integer,List<List<String>>> res = new LinkedHashMap<>();
        for (int pn : pages) {
            Future<List<List<String>>> f = exec.submit(() -> {
                Page page = oe.extract(pn);
                if (cfg.getTableArea()!=null) page = clip(page, cfg.getTableArea());
                List<Table> tables = new BasicExtractionAlgorithm().extract(page, cols);
                if (tables==null||tables.isEmpty()) return Collections.emptyList();
                return toRows(tables.stream().max(Comparator.comparingInt(t->t.getRows().size())).orElse(null));
            });
            try {
                List<List<String>> rows = f.get(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!rows.isEmpty()) res.put(pn, rows);
            } catch (TimeoutException e) { f.cancel(true); log.warn("Manual page {} timed out", pn);
            } catch (ExecutionException e) { log.warn("Manual page {} error: {}", pn, e.getMessage());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return res;
    }

    private List<List<String>> extractPage(Page page) {
        // Try SEA
        List<Table> sea = new SpreadsheetExtractionAlgorithm().extract(page);
        Table best = pickBest(sea, "SEA", page.getPageNumber());
        if (best != null) {
            List<List<String>> rows = toRows(best);
            if (!isCollapsed(rows)) return rows;
        }
        // BEA fallback
        List<Table> bea = new BasicExtractionAlgorithm().extract(page);
        best = pickBest(bea, "BEA", page.getPageNumber());
        if (best != null) {
            List<List<String>> rows = toRows(best);
            if (!isCollapsed(rows)) return rows;
        }
        return Collections.emptyList();
    }

    private boolean isCollapsed(List<List<String>> rows) {
        if (rows==null||rows.size()>3) return false;
        long dc = rows.stream().flatMap(Collection::stream)
            .flatMap(c->Arrays.stream(c.split("\\s+")))
            .filter(t->t.matches("\\d{1,2}[/\\-.](\\d{1,2}|[A-Za-z]{3})[/\\-.]\\d{2,4}")).count();
        return dc >= COLLAPSED_THRESHOLD;
    }

    private Table pickBest(List<Table> tables, String algo, int pn) {
        if (tables==null||tables.isEmpty()) return null;
        Table best=null; double bs=Double.NEGATIVE_INFINITY;
        for (Table t : tables) {
            List<List<RectangularTextContainer>> rows = t.getRows();
            if (rows.isEmpty()) continue;
            int cols=rows.get(0).size(), nr=rows.size();
            double s = (cols<MIN_COLUMNS?-10:0) + (nr<MIN_ROWS+1?-10:0) + Math.min(cols,8)*5.0 + nr;
            String fr = rowStr(rows.get(0)).toLowerCase();
            for (String kw:HEADER_KW) if(fr.contains(kw)){s+=10;break;}
            s += rows.stream().skip(1).map(r->r.isEmpty()?"":r.get(0).getText().trim()).filter(this::isDate).count()*3.0;
            if(s>bs){bs=s;best=t;}
        }
        return (best!=null&&bs>-5)?best:null;
    }

    private List<List<String>> toRows(Table t) {
        if (t==null) return Collections.emptyList();
        List<List<String>> res=new ArrayList<>();
        for (List<RectangularTextContainer> row:t.getRows()) {
            List<String> cells=new ArrayList<>();
            for (RectangularTextContainer c:row) cells.add(c.getText().replace("\r"," ").trim());
            if (cells.stream().anyMatch(c->!c.isBlank())) res.add(cells);
        }
        return res;
    }

    private Page clip(Page page, ManualExtractionConfig.TableArea a) {
        try { return page.getArea((float)a.getTop(),(float)a.getLeft(),(float)a.getBottom(),(float)a.getRight()); }
        catch (Exception e) { log.warn("Clip failed: {}", e.getMessage()); return page; }
    }

    private List<Integer> resolvePages(List<Integer> req) {
        int total = doc.getNumberOfPages();
        if (req==null||req.isEmpty()) {
            List<Integer> all=new ArrayList<>(); for(int p=1;p<=total;p++) all.add(p); return all;
        }
        return req.stream().filter(p->p>=1&&p<=total).distinct().sorted().toList();
    }

    private String rowStr(List<RectangularTextContainer> row) {
        StringBuilder sb=new StringBuilder(); for(var c:row) sb.append(c.getText()).append(' '); return sb.toString().trim();
    }
    private boolean isDate(String s) { return s.matches("\\d{1,2}[/\\-.](\\d{1,2}|[A-Za-z]{3})[/\\-.]\\d{2,4}"); }

    public int getPageCount() { return doc.getNumberOfPages(); }

    @Override public void close() {
        exec.shutdownNow();
        try{if(oe!=null)oe.close();}catch(Exception ignored){}
        try{if(doc!=null)doc.close();}catch(Exception ignored){}
        if(tmp!=null) tmp.delete();
    }
}