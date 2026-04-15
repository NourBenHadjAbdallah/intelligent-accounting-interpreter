package com.bankextractor.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.*;

public class PdfTextExtractor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    private final PDDocument doc;
    private final String src;

    public PdfTextExtractor(File f)             throws IOException { this.src=f.getAbsolutePath(); this.doc=PDDocument.load(f); }
    public PdfTextExtractor(File f,String pwd)  throws IOException { this.src=f.getAbsolutePath(); this.doc=PDDocument.load(f,pwd); }
    public PdfTextExtractor(InputStream s)      throws IOException { this.src="<stream>";          this.doc=PDDocument.load(s); }

    public List<String> extractAllPages() throws IOException { return extractPageRange(1,doc.getNumberOfPages()); }

    public List<String> extractPageRange(int from, int to) throws IOException {
        int total=doc.getNumberOfPages();
        from=Math.max(1,from); to=to<0?total:Math.min(to,total);
        List<String> pages=new ArrayList<>();
        PDFTextStripper st=new PDFTextStripper(); st.setSortByPosition(true);
        for(int p=from;p<=to;p++){st.setStartPage(p);st.setEndPage(p);pages.add(st.getText(doc));}
        return pages;
    }

    /**
     * Extract text inside a rectangular region using PDFTextStripperByArea.
     * Coordinates in PDF points (1pt=1/72in), origin top-left of page.
     */
    public String extractRegion(int pageNum, float x, float y, float w, float h) throws IOException {
        if(pageNum<1||pageNum>doc.getNumberOfPages()) return "";
        PDPage page = doc.getPage(pageNum-1);
        float pw=page.getMediaBox().getWidth(), ph=page.getMediaBox().getHeight();
        float cx=Math.max(0,Math.min(x,pw)), cy=Math.max(0,Math.min(y,ph));
        float cw=Math.min(w,pw-cx),         ch=Math.min(h,ph-cy);
        if(cw<=0||ch<=0){ log.warn("extractRegion p{}: zero area after clamp",pageNum); return ""; }
        PDFTextStripperByArea st=new PDFTextStripperByArea(); st.setSortByPosition(true);
        st.addRegion("zone",new Rectangle2D.Float(cx,cy,cw,ch));
        st.extractRegions(page);
        String text=st.getTextForRegion("zone");
        log.debug("Region p{} [{},{},{},{}]: {} chars",pageNum,cx,cy,cw,ch,text==null?0:text.length());
        return text!=null?text:"";
    }

    public Map<String,String> getMetadata() {
        PDDocumentInformation i=doc.getDocumentInformation(); Map<String,String> m=new HashMap<>();
        if(i.getTitle()!=null)    m.put("title",i.getTitle());
        if(i.getAuthor()!=null)   m.put("author",i.getAuthor());
        if(i.getCreator()!=null)  m.put("creator",i.getCreator());
        if(i.getProducer()!=null) m.put("producer",i.getProducer());
        return m;
    }

    public int    getPageCount()  { return doc.getNumberOfPages(); }
    public String getSourcePath() { return src; }

    @Override public void close() {
        try{if(doc!=null)doc.close();}catch(IOException e){log.warn("Close error",e);}
    }
}