package com.bankextractor;

import com.bankextractor.model.ExtractionConfig;
import com.bankextractor.model.Transaction;
import com.bankextractor.parser.TransactionLineParser;
import com.bankextractor.util.AmountParser;
import com.bankextractor.util.DateParser;
import com.bankextractor.util.TransactionClassifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BankStatementExtractorTest {

    // ─── DateParser Tests ─────────────────────────────────────────────────────

    @Test
    void testDateParsingDdMmYyyy() {
        DateParser parser = new DateParser(List.of("dd/MM/yyyy"));
        assertEquals(LocalDate.of(2024, 3, 15), parser.parse("15/03/2024"));
    }

    @Test
    void testDateParsingMmDdYyyy() {
        DateParser parser = new DateParser(List.of("MM/dd/yyyy"));
        assertEquals(LocalDate.of(2024, 3, 15), parser.parse("03/15/2024"));
    }

    @Test
    void testDateParsingDdMmmYyyy() {
        DateParser parser = new DateParser(List.of("dd MMM yyyy"));
        assertEquals(LocalDate.of(2024, 1, 5), parser.parse("05 Jan 2024"));
    }

    @Test
    void testDateExtractFromLine() {
        DateParser parser = new DateParser(List.of("dd/MM/yyyy", "dd MMM yyyy"));
        String line = "15/03/2024  PAYMENT TO UTILITY  150.00  2,345.00";
        LocalDate date = parser.extractFirstDate(line);
        assertNotNull(date);
        assertEquals(LocalDate.of(2024, 3, 15), date);
    }

    @Test
    void testNullDateReturnsNull() {
        DateParser parser = new DateParser(List.of("dd/MM/yyyy"));
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
        assertNull(parser.parse("NOT A DATE"));
    }

    // ─── AmountParser Tests ───────────────────────────────────────────────────

    @Test
    void testAmountParsingUS() {
        AmountParser parser = new AmountParser(false);
        assertEquals(new BigDecimal("1234.56"), parser.parse("1,234.56"));
        assertEquals(new BigDecimal("100.00"), parser.parse("100.00"));
        assertEquals(new BigDecimal("5000"), parser.parse("5,000"));
    }

    @Test
    void testAmountParsingEuropean() {
        AmountParser parser = new AmountParser(true);
        assertEquals(new BigDecimal("1234.56"), parser.parse("1.234,56"));
        assertEquals(new BigDecimal("100.50"), parser.parse("100,50"));
    }

    @Test
    void testNegativeAmount() {
        AmountParser parser = new AmountParser(false);
        BigDecimal result = parser.parse("-1,234.56");
        assertNotNull(result);
        assertTrue(result.compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void testParenthesisNegative() {
        AmountParser parser = new AmountParser(false);
        BigDecimal result = parser.parse("(500.00)");
        assertNotNull(result);
        assertEquals(new BigDecimal("500.00"), result.abs());
        assertTrue(result.compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void testExtractMultipleAmounts() {
        AmountParser parser = new AmountParser(false);
        String line = "15/03/2024  ATM WITHDRAWAL  500.00  2,345.67";
        List<AmountParser.ParsedAmount> amounts = parser.extractAmounts(line);
        assertEquals(2, amounts.size());
    }

    @Test
    void testCurrencyDetection() {
        assertEquals("USD", AmountParser.detectCurrency("Total $1,234.56"));
        assertEquals("EUR", AmountParser.detectCurrency("Amount: €500.00"));
        assertEquals("GBP", AmountParser.detectCurrency("Payment £200"));
        assertEquals("USD", AmountParser.detectCurrency("Amount: 1,234.56 USD"));
    }

    // ─── TransactionClassifier Tests ─────────────────────────────────────────

    @Test
    void testClassifySalary() {
        assertEquals("Salary", TransactionClassifier.classify("SALARY PAYMENT MARCH 2024"));
    }

    @Test
    void testClassifyATM() {
        assertEquals("ATM", TransactionClassifier.classify("ATM WITHDRAWAL DOWNTOWN"));
        assertEquals("ATM", TransactionClassifier.classify("CASH RETRAIT DAB"));
    }

    @Test
    void testClassifyTransfer() {
        assertEquals("Transfer", TransactionClassifier.classify("VIREMENT BANCAIRE REF 12345"));
    }

    @Test
    void testClassifyGroceries() {
        assertEquals("Groceries", TransactionClassifier.classify("CARREFOUR SUPERMARKET PURCHASE"));
    }

    @Test
    void testClassifyUnknown() {
        assertEquals("Other", TransactionClassifier.classify("XYZABC RANDOM DESCRIPTION"));
    }

    @Test
    void testClassifyNull() {
        assertEquals("Other", TransactionClassifier.classify(null));
        assertEquals("Other", TransactionClassifier.classify(""));
    }

    // ─── TransactionLineParser Tests ──────────────────────────────────────────

    @Test
    void testParseTransactionLine() {
        ExtractionConfig config = ExtractionConfig.builder()
                .confidenceThreshold(0.3)
                .build();
        TransactionLineParser parser = new TransactionLineParser(config);

        String line = "15/03/2024  ATM WITHDRAWAL DOWNTOWN   500.00   2,345.00";
        Transaction tx = parser.parseLine(line, 1, 1, null);

        assertNotNull(tx);
        assertEquals(LocalDate.of(2024, 3, 15), tx.getDate());
        assertNotNull(tx.getAmount());
        assertEquals(1, tx.getPageNumber());
    }

    @Test
    void testSkipHeaderLines() {
        ExtractionConfig config = new ExtractionConfig();
        TransactionLineParser parser = new TransactionLineParser(config);

        assertNull(parser.parseLine("", 1, 1, null));
        assertNull(parser.parseLine("   ", 1, 1, null));
        assertNull(parser.parseLine("Date  Description  Debit  Credit  Balance", 1, 1, null));
        assertNull(parser.parseLine("Page 1", 1, 1, null));
    }

    @Test
    void testLineScore() {
        ExtractionConfig config = new ExtractionConfig();
        TransactionLineParser parser = new TransactionLineParser(config);

        double highScore = parser.scoreLine("15/03/2024  SALARY PAYMENT  3,500.00  10,250.00");
        double lowScore  = parser.scoreLine("Bank Statement for Period January 2024");

        assertTrue(highScore > lowScore);
        assertTrue(highScore > 0.5);
    }
}