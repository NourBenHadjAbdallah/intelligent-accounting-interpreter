package com.bankextractor.model;

import java.util.List;

public class ExtractionConfig {

    private List<String> dateFormats = List.of(
            "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd",
            "dd-MM-yyyy", "dd MMM yyyy", "MMM dd, yyyy",
            "dd.MM.yyyy", "d MMM yyyy", "MMM d yyyy"
    );
    private String expectedCurrency = null;
    private boolean classifyCategories = true;
    private boolean validateBalances = true;
    private boolean extractRawText = false;
    private int startPage = 1;
    private int endPage = -1;
    private double transactionConfidenceThreshold = 0.5;
    private String bankHint = null;
    private boolean useEuropeanNumberFormat = false;

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ExtractionConfig cfg = new ExtractionConfig();
        public Builder currency(String c) { cfg.expectedCurrency = c; return this; }
        public Builder classifyCategories(boolean v) { cfg.classifyCategories = v; return this; }
        public Builder validateBalances(boolean v) { cfg.validateBalances = v; return this; }
        public Builder extractRawText(boolean v) { cfg.extractRawText = v; return this; }
        public Builder pageRange(int s, int e) { cfg.startPage = s; cfg.endPage = e; return this; }
        public Builder bankHint(String h) { cfg.bankHint = h; return this; }
        public Builder europeanFormat(boolean v) { cfg.useEuropeanNumberFormat = v; return this; }
        public Builder confidenceThreshold(double t) { cfg.transactionConfidenceThreshold = t; return this; }
        public Builder dateFormats(List<String> f) { cfg.dateFormats = f; return this; }
        public ExtractionConfig build() { return cfg; }
    }

    public List<String> getDateFormats() { return dateFormats; }
    public void setDateFormats(List<String> f) { this.dateFormats = f; }
    public String getExpectedCurrency() { return expectedCurrency; }
    public void setExpectedCurrency(String c) { this.expectedCurrency = c; }
    public boolean isClassifyCategories() { return classifyCategories; }
    public void setClassifyCategories(boolean v) { this.classifyCategories = v; }
    public boolean isValidateBalances() { return validateBalances; }
    public void setValidateBalances(boolean v) { this.validateBalances = v; }
    public boolean isExtractRawText() { return extractRawText; }
    public void setExtractRawText(boolean v) { this.extractRawText = v; }
    public int getStartPage() { return startPage; }
    public void setStartPage(int p) { this.startPage = p; }
    public int getEndPage() { return endPage; }
    public void setEndPage(int p) { this.endPage = p; }
    public double getTransactionConfidenceThreshold() { return transactionConfidenceThreshold; }
    public void setTransactionConfidenceThreshold(double t) { this.transactionConfidenceThreshold = t; }
    public String getBankHint() { return bankHint; }
    public void setBankHint(String h) { this.bankHint = h; }
    public boolean isUseEuropeanNumberFormat() { return useEuropeanNumberFormat; }
    public void setUseEuropeanNumberFormat(boolean v) { this.useEuropeanNumberFormat = v; }
}