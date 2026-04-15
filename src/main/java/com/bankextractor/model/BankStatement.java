package com.bankextractor.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BankStatement {

    // ── Bank identity ──────────────────────────────────────────────────────────
    private String bankName;          // e.g. "Société Générale"
    private String bankAddress;       // e.g. "29, bd Haussmann 75009 Paris"
    private String bankRegistration;  // e.g. "552 120 222 RCS Paris"
    private String bankCapital;       // e.g. "933 027 038,75 Eur"
    private String bic;               // e.g. "SOGEFRPP"

    // ── Branch ────────────────────────────────────────────────────────────────
    private String branch;            // e.g. "Agence Château"
    private String branchAddress;     // e.g. "3, rue du Colonel Péri 01184 Maville"
    private String branchPhone;       // e.g. "01 23 45 67 89"
    private String branchFax;         // e.g. "01 23 45 67 80"

    // ── Advisor ───────────────────────────────────────────────────────────────
    private String advisorName;       // e.g. "M. Nicolas DUPONDT"
    private String advisorPhone;      // e.g. "01 23 45 67 88"

    // ── Account holder ────────────────────────────────────────────────────────
    private String accountHolder;     // e.g. "Barbara MARTINON"
    private String accountHolderAddress; // e.g. "2, rue pablo picasso, 12345 maville"
    private String clientCode;        // e.g. "12345678"

    // ── Account ───────────────────────────────────────────────────────────────
    private String accountNumber;     // e.g. "30003 01234 00012345678 12"
    private String iban;              // e.g. "FR7630003012340001234567812"
    private String accountType;       // e.g. "COMPTE DE PARTICULIER"
    private String currency;          // e.g. "EUR"

    // RIB components (French)
    private String ribBanque;         // e.g. "30003"
    private String ribAgence;         // e.g. "01234"
    private String ribCle;            // e.g. "12"

    // ── Statement period ──────────────────────────────────────────────────────
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate statementFrom;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate statementTo;

    private String statementReference; // e.g. "envoi n°7 p. 1/3"

    // ── Balances ──────────────────────────────────────────────────────────────
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal totalCredits;
    private BigDecimal totalDebits;

    // ── Loyalty program (e.g. JAZZ) ───────────────────────────────────────────
    private String loyaltyProgramName;      // e.g. "JAZZ"
    private String loyaltyMemberNumber;     // e.g. "0000000001234567"
    private Integer loyaltyPointsPrevious;  // e.g. 3000
    private Integer loyaltyPointsEarned;    // e.g. 650
    private Integer loyaltyPointsUsed;      // e.g. 0
    private Integer loyaltyPointsNew;       // e.g. 3650

    // ── Transactions ──────────────────────────────────────────────────────────
    private List<Transaction> transactions = new ArrayList<>();

    // ── Metadata ──────────────────────────────────────────────────────────────
    private String sourceFile;
    private int totalPages;
    private ExtractionStatus status;
    private List<String> warnings = new ArrayList<>();
    private Map<String, String> rawMetadata;

    public enum ExtractionStatus {
        SUCCESS, PARTIAL, FAILED
    }

    // ── Computed helpers ──────────────────────────────────────────────────────
    public int getTransactionCount() { return transactions.size(); }

    public long getCreditCount() {
        return transactions.stream()
                .filter(t -> t.getType() == Transaction.TransactionType.CREDIT).count();
    }

    public long getDebitCount() {
        return transactions.stream()
                .filter(t -> t.getType() == Transaction.TransactionType.DEBIT).count();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }

    public String getBankAddress() { return bankAddress; }
    public void setBankAddress(String v) { this.bankAddress = v; }

    public String getBankRegistration() { return bankRegistration; }
    public void setBankRegistration(String v) { this.bankRegistration = v; }

    public String getBankCapital() { return bankCapital; }
    public void setBankCapital(String v) { this.bankCapital = v; }

    public String getBic() { return bic; }
    public void setBic(String v) { this.bic = v; }

    public String getBranch() { return branch; }
    public void setBranch(String v) { this.branch = v; }

    public String getBranchAddress() { return branchAddress; }
    public void setBranchAddress(String v) { this.branchAddress = v; }

    public String getBranchPhone() { return branchPhone; }
    public void setBranchPhone(String v) { this.branchPhone = v; }

    public String getBranchFax() { return branchFax; }
    public void setBranchFax(String v) { this.branchFax = v; }

    public String getAdvisorName() { return advisorName; }
    public void setAdvisorName(String v) { this.advisorName = v; }

    public String getAdvisorPhone() { return advisorPhone; }
    public void setAdvisorPhone(String v) { this.advisorPhone = v; }

    public String getAccountHolder() { return accountHolder; }
    public void setAccountHolder(String v) { this.accountHolder = v; }

    public String getAccountHolderAddress() { return accountHolderAddress; }
    public void setAccountHolderAddress(String v) { this.accountHolderAddress = v; }

    public String getClientCode() { return clientCode; }
    public void setClientCode(String v) { this.clientCode = v; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String v) { this.accountNumber = v; }

    public String getIban() { return iban; }
    public void setIban(String v) { this.iban = v; }

    public String getAccountType() { return accountType; }
    public void setAccountType(String v) { this.accountType = v; }

    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }

    public String getRibBanque() { return ribBanque; }
    public void setRibBanque(String v) { this.ribBanque = v; }

    public String getRibAgence() { return ribAgence; }
    public void setRibAgence(String v) { this.ribAgence = v; }

    public String getRibCle() { return ribCle; }
    public void setRibCle(String v) { this.ribCle = v; }

    public LocalDate getStatementFrom() { return statementFrom; }
    public void setStatementFrom(LocalDate v) { this.statementFrom = v; }

    public LocalDate getStatementTo() { return statementTo; }
    public void setStatementTo(LocalDate v) { this.statementTo = v; }

    public String getStatementReference() { return statementReference; }
    public void setStatementReference(String v) { this.statementReference = v; }

    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal v) { this.openingBalance = v; }

    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal v) { this.closingBalance = v; }

    public BigDecimal getTotalCredits() { return totalCredits; }
    public void setTotalCredits(BigDecimal v) { this.totalCredits = v; }

    public BigDecimal getTotalDebits() { return totalDebits; }
    public void setTotalDebits(BigDecimal v) { this.totalDebits = v; }

    public String getLoyaltyProgramName() { return loyaltyProgramName; }
    public void setLoyaltyProgramName(String v) { this.loyaltyProgramName = v; }

    public String getLoyaltyMemberNumber() { return loyaltyMemberNumber; }
    public void setLoyaltyMemberNumber(String v) { this.loyaltyMemberNumber = v; }

    public Integer getLoyaltyPointsPrevious() { return loyaltyPointsPrevious; }
    public void setLoyaltyPointsPrevious(Integer v) { this.loyaltyPointsPrevious = v; }

    public Integer getLoyaltyPointsEarned() { return loyaltyPointsEarned; }
    public void setLoyaltyPointsEarned(Integer v) { this.loyaltyPointsEarned = v; }

    public Integer getLoyaltyPointsUsed() { return loyaltyPointsUsed; }
    public void setLoyaltyPointsUsed(Integer v) { this.loyaltyPointsUsed = v; }

    public Integer getLoyaltyPointsNew() { return loyaltyPointsNew; }
    public void setLoyaltyPointsNew(Integer v) { this.loyaltyPointsNew = v; }

    public List<Transaction> getTransactions() { return transactions; }
    public void setTransactions(List<Transaction> v) { this.transactions = v; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String v) { this.sourceFile = v; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int v) { this.totalPages = v; }

    public ExtractionStatus getStatus() { return status; }
    public void setStatus(ExtractionStatus v) { this.status = v; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> v) { this.warnings = v; }

    public Map<String, String> getRawMetadata() { return rawMetadata; }
    public void setRawMetadata(Map<String, String> v) { this.rawMetadata = v; }

    @Override
    public String toString() {
        return String.format(
            "BankStatement{bank='%s', holder='%s', account='%s', iban='%s', period=%s→%s, transactions=%d, status=%s}",
            bankName, accountHolder, accountNumber, iban, statementFrom, statementTo,
            getTransactionCount(), status);
    }
}