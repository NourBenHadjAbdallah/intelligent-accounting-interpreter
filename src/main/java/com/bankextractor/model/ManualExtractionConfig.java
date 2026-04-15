package com.bankextractor.model;

import java.util.List;

public class ManualExtractionConfig {

    private List<Double> columnBoundaries = List.of();
    private TableArea tableArea;
    private List<Integer> pages;
    private boolean hasHeaderRow = true;
    /** When true: ignore columnBoundaries, use PDFBox region extraction on tableArea */
    private boolean zoneOnly = false;

    public static class TableArea {
        private double top,left,bottom,right;
        public TableArea(){}
        public TableArea(double t,double l,double b,double r){top=t;left=l;bottom=b;right=r;}
        public double getTop(){return top;} public void setTop(double v){top=v;}
        public double getLeft(){return left;} public void setLeft(double v){left=v;}
        public double getBottom(){return bottom;} public void setBottom(double v){bottom=v;}
        public double getRight(){return right;} public void setRight(double v){right=v;}
        @Override public String toString(){return String.format("TableArea{t=%.1f,l=%.1f,b=%.1f,r=%.1f}",top,left,bottom,right);}
    }

    public List<Double>  getColumnBoundaries(){return columnBoundaries;}
    public void          setColumnBoundaries(List<Double> v){columnBoundaries=v!=null?v:List.of();}
    public TableArea     getTableArea(){return tableArea;}
    public void          setTableArea(TableArea v){tableArea=v;}
    public List<Integer> getPages(){return pages;}
    public void          setPages(List<Integer> v){pages=v;}
    public boolean       isHasHeaderRow(){return hasHeaderRow;}
    public void          setHasHeaderRow(boolean v){hasHeaderRow=v;}
    public boolean       isZoneOnly(){return zoneOnly;}
    public void          setZoneOnly(boolean v){zoneOnly=v;}

    @Override public String toString(){
        return String.format("ManualExtractionConfig{zoneOnly=%s,cols=%s,area=%s,pages=%s}",zoneOnly,columnBoundaries,tableArea,pages);
    }
}