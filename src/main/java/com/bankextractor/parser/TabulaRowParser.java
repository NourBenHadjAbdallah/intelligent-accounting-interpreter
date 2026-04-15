package com.bankextractor.parser;

import com.bankextractor.model.ExtractionConfig;
import com.bankextractor.model.Transaction;
import com.bankextractor.util.AmountParser;
import com.bankextractor.util.DateParser;
import com.bankextractor.util.TransactionClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TabulaRowParser
 *
 * Converts structured rows produced by {@link TabulaTableExtractor} into
 * {@link Transaction} objects.
 *
 * Unlike {@link TransactionLineParser}, which processes a raw text line and has
 * to infer column positions with heuristics, this parser receives data that is
 * already split into cells by Tabula.  Column roles (date / description /
 * debit / credit / balance) are detected once from the header row and then
 * applied to every data row — making extraction far more accurate.
 *
 * Column detection strategy
 * ─────────────────────────
 * 1. If the first row of the table contains recognisable header keywords
 *    (date, valeur, débit, crédit, solde, …) we map them to column roles.
 * 2. If no header is found, we fall back to positional heuristics:
 *    first column = date, last column = balance, middle columns = amounts.
 */
public class TabulaRowParser {

    private static final Logger log = LoggerFactory.getLogger(TabulaRowParser.class);

    // ─── Column role constants ─────────────────────────────────────────────────
    private static final int COL_UNKNOWN     = -1;
    private static final int COL_DATE        = 0;
    private static final int COL_VALUE_DATE  = 1;
    private static final int COL_DESCRIPTION = 2;
    private static final int COL_DEBIT       = 3;
    private static final int COL_CREDIT      = 4;
    private static final int COL_BALANCE     = 5;
    private static final int COL_AMOUNT      = 6;   // generic — no separate debit/credit col

    // ─── Header keyword maps ───────────────────────────────────────────────────
    private static final Map<Integer, List<String>> HEADER_KEYWORDS = Map.of(
            COL_DATE,        List.of("date", "dat", "dte"),
            COL_VALUE_DATE,  List.of("valeur", "value date", "val"),
            COL_DESCRIPTION, List.of("libellé", "libelle", "nature", "description",
                                     "particulars", "détail", "detail", "opération", "operation"),
            COL_DEBIT,       List.of("débit", "debit", "withdrawal", "sortie", "dépense"),
            COL_CREDIT,      List.of("crédit", "credit", "deposit", "entrée", "versement"),
            COL_BALANCE,     List.of("solde", "balance", "cumul"),
            COL_AMOUNT,      List.of("montant", "amount")
    );

    private final DateParser dateParser;
    private final AmountParser amountParser;
    private final ExtractionConfig config;

    public TabulaRowParser(ExtractionConfig config) {
        this.config       = config;
        this.dateParser   = new DateParser(config.getDateFormats());
        this.amountParser = new AmountParser(config.isUseEuropeanNumberFormat());
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Parse a list of rows (as returned by TabulaTableExtractor) into transactions.
     *
     * @param rows      raw cell rows; first row may be a header
     * @param pageNumber page number (for source tracking)
     * @return list of parsed transactions
     */
    public List<Transaction> parseRows(List<List<String>> rows, int pageNumber) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();

        // Detect column mapping from first row
        int[] colMap = buildColumnMap(rows.get(0));
        boolean hasHeader = isHeaderRow(rows.get(0));
        log.debug("Page {}: colMap={}, hasHeader={}", pageNumber, Arrays.toString(colMap), hasHeader);

        List<Transaction> result = new ArrayList<>();
        LocalDate lastDate = null;

        int startRow = hasHeader ? 1 : 0;
        for (int i = startRow; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            Transaction tx = parseRow(cells, colMap, pageNumber, i + 1, lastDate);
            if (tx != null) {
                result.add(tx);
                if (tx.getDate() != null) lastDate = tx.getDate();
            }
        }

        log.info("Page {}: parsed {}/{} rows into transactions",
                pageNumber, result.size(), rows.size() - startRow);
        return result;
    }

    // ─── Column Map ───────────────────────────────────────────────────────────

    /**
     * Build a column-role array: colMap[i] = role constant for column i.
     * Unrecognised columns get COL_UNKNOWN.
     */
    private int[] buildColumnMap(List<String> headerRow) {
        int[] map = new int[headerRow.size()];
        Arrays.fill(map, COL_UNKNOWN);

        boolean anyMatched = false;
        for (int col = 0; col < headerRow.size(); col++) {
            String cell = headerRow.get(col).toLowerCase().trim();
            for (Map.Entry<Integer, List<String>> entry : HEADER_KEYWORDS.entrySet()) {
                for (String kw : entry.getValue()) {
                    if (cell.contains(kw)) {
                        map[col] = entry.getKey();
                        anyMatched = true;
                        break;
                    }
                }
            }
        }

        // Fallback: positional heuristic if no header matched
        if (!anyMatched) {
            map = positionalFallbackMap(headerRow.size());
        }

        return map;
    }

    /**
     * Positional column-role fallback for tables without recognisable headers.
     *
     * Typical French bank layout: Date | Valeur | Description | Débit | Crédit | Solde
     */
    private int[] positionalFallbackMap(int numCols) {
        int[] map = new int[numCols];
        Arrays.fill(map, COL_UNKNOWN);

        if (numCols == 0) return map;

        // Always: col 0 = date, last col = balance
        map[0] = COL_DATE;
        map[numCols - 1] = COL_BALANCE;

        if (numCols == 2) return map;

        if (numCols == 3) {
            // Date | Amount | Balance
            map[1] = COL_AMOUNT;
            return map;
        }

        if (numCols == 4) {
            // Date | Description | Amount | Balance
            map[1] = COL_DESCRIPTION;
            map[2] = COL_AMOUNT;
            return map;
        }

        if (numCols == 5) {
            // Date | Description | Debit | Credit | Balance
            map[1] = COL_DESCRIPTION;
            map[2] = COL_DEBIT;
            map[3] = COL_CREDIT;
            return map;
        }

        // 6+ columns: Date | ValueDate | Description | Debit | Credit | Balance
        map[1] = COL_VALUE_DATE;
        map[2] = COL_DESCRIPTION;
        map[3] = COL_DEBIT;
        map[4] = COL_CREDIT;
        // anything between 5 and last is unknown
        return map;
    }

    private boolean isHeaderRow(List<String> row) {
        String joined = row.stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining(" "));
        for (List<String> kws : HEADER_KEYWORDS.values()) {
            for (String kw : kws) {
                if (joined.contains(kw)) return true;
            }
        }
        return false;
    }

    // ─── Row → Transaction ────────────────────────────────────────────────────

    private Transaction parseRow(List<String> cells, int[] colMap,
                                 int pageNumber, int rowIndex, LocalDate lastDate) {
        if (cells == null || cells.isEmpty()) return null;

        // Skip rows that look like headers or separators
        if (isHeaderRow(cells)) return null;
        if (isAllBlankOrNumeric(cells)) return null;

        Transaction tx = new Transaction();
        tx.setPageNumber(pageNumber);
        tx.setLineNumber(rowIndex);

        // ── Extract by column role ─────────────────────────────────────────────
        String descriptionAccumulator = "";

        for (int col = 0; col < Math.min(cells.size(), colMap.length); col++) {
            String cell = cells.get(col).trim();
            if (cell.isBlank()) continue;

            switch (colMap[col]) {

                case COL_DATE -> {
                    LocalDate d = dateParser.parse(cell);
                    if (d == null) d = dateParser.extractFirstDate(cell);
                    if (d != null) tx.setDate(d);
                }

                case COL_VALUE_DATE -> {
                    LocalDate d = dateParser.parse(cell);
                    if (d == null) d = dateParser.extractFirstDate(cell);
                    if (d != null) tx.setValueDate(d);
                }

                case COL_DESCRIPTION -> {
                    descriptionAccumulator = descriptionAccumulator.isBlank()
                            ? cell : descriptionAccumulator + " " + cell;
                }

                case COL_DEBIT -> {
                    BigDecimal amt = amountParser.parse(cell);
                    if (amt != null && amt.compareTo(BigDecimal.ZERO) != 0) {
                        tx.setAmount(amt.abs());
                        tx.setType(Transaction.TransactionType.DEBIT);
                    }
                }

                case COL_CREDIT -> {
                    BigDecimal amt = amountParser.parse(cell);
                    if (amt != null && amt.compareTo(BigDecimal.ZERO) != 0) {
                        // Only set if no debit amount already claimed the tx
                        if (tx.getAmount() == null) {
                            tx.setAmount(amt.abs());
                            tx.setType(Transaction.TransactionType.CREDIT);
                        }
                    }
                }

                case COL_BALANCE -> {
                    BigDecimal b = amountParser.parse(cell);
                    if (b != null) tx.setBalance(b.abs());
                }

                case COL_AMOUNT -> {
                    BigDecimal amt = amountParser.parse(cell);
                    if (amt != null && amt.compareTo(BigDecimal.ZERO) != 0) {
                        tx.setAmount(amt.abs());
                        // Negative raw value → debit; positive → credit
                        if (amt.compareTo(BigDecimal.ZERO) < 0) {
                            tx.setType(Transaction.TransactionType.DEBIT);
                        } else {
                            // Use description keywords to decide
                            tx.setType(Transaction.TransactionType.DEBIT); // default
                        }
                    }
                }

                case COL_UNKNOWN -> {
                    // Accumulate unknown columns into description if they look textual
                    if (!looksLikeAmount(cell) && !looksLikeDate(cell)) {
                        descriptionAccumulator = descriptionAccumulator.isBlank()
                                ? cell : descriptionAccumulator + " " + cell;
                    }
                }
            }
        }

        // ── Description ───────────────────────────────────────────────────────
        String description = descriptionAccumulator.trim()
                .replaceAll("\\s{2,}", " ");
        if (description.isBlank()) return null;
        tx.setDescription(description);

        // ── Date fallback ─────────────────────────────────────────────────────
        if (tx.getDate() == null) {
            // Try to find a date hidden inside the description cell
            LocalDate d = dateParser.extractFirstDate(description);
            if (d != null) tx.setDate(d);
            else if (lastDate != null) tx.setDate(lastDate);   // continuation row
            else return null;                                    // no date → skip
        }

        // ── Amount fallback: scan whole row if no amount found yet ─────────────
        if (tx.getAmount() == null) {
            String fullRow = String.join(" ", cells);
            List<AmountParser.ParsedAmount> amounts = amountParser.extractAmounts(fullRow);
            if (!amounts.isEmpty()) {
                // Largest non-balance amount
                amounts.stream()
                        .filter(a -> tx.getBalance() == null ||
                                a.value().abs().compareTo(tx.getBalance()) < 0)
                        .max(Comparator.comparing(a -> a.value().abs()))
                        .ifPresent(a -> {
                            tx.setAmount(a.value().abs());
                            tx.setType(a.isNegative()
                                    ? Transaction.TransactionType.DEBIT
                                    : Transaction.TransactionType.DEBIT); // default
                        });
            }
            if (tx.getAmount() == null) return null;
        }

        // ── Fix DEBIT/CREDIT from description keywords ─────────────────────────
        fixTransactionType(tx, description);

        // ── Currency ──────────────────────────────────────────────────────────
        String detectedCurrency = AmountParser.detectCurrency(String.join(" ", cells));
        tx.setCurrency(detectedCurrency != null ? detectedCurrency
                : (config.getExpectedCurrency() != null ? config.getExpectedCurrency() : "EUR"));

        // ── Category ──────────────────────────────────────────────────────────
        if (config.isClassifyCategories()) {
            tx.setCategory(TransactionClassifier.classify(description));
        }

        // ── Default type if still unknown ──────────────────────────────────────
        if (tx.getType() == null) {
            tx.setType(Transaction.TransactionType.DEBIT);
        }

        log.debug("Parsed row {}: {}", rowIndex, tx);
        return tx;
    }

    // ─── Type correction from keywords ────────────────────────────────────────

    private void fixTransactionType(Transaction tx, String description) {
        String lower = description.toLowerCase();
        if (lower.contains("vir recu") || lower.contains("virement reçu") ||
            lower.contains("remise")   || lower.contains("versement") ||
            lower.contains("depot")    || lower.contains("dépôt") ||
            lower.contains("salaire")  || lower.contains("salary") ||
            lower.contains("payroll")) {
            tx.setType(Transaction.TransactionType.CREDIT);
        }
        if (lower.contains("carte")       || lower.contains("retrait") ||
            lower.contains("cheque")      || lower.contains("chèque") ||
            lower.contains("prelevement") || lower.contains("cotisation") ||
            lower.contains("frais")       || lower.contains("commission")) {
            tx.setType(Transaction.TransactionType.DEBIT);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean isAllBlankOrNumeric(List<String> cells) {
        int nonBlank = 0;
        for (String c : cells) {
            if (!c.isBlank()) nonBlank++;
        }
        return nonBlank == 0;
    }

    private boolean looksLikeAmount(String s) {
        return s.matches("[\\d\\s.,()\\-+]+") && s.replaceAll("[^\\d]", "").length() >= 2;
    }

    private boolean looksLikeDate(String s) {
        return s.matches("\\d{1,2}[/\\-.](\\d{1,2}|[A-Za-z]{3})[/\\-.]\\d{2,4}");
    }
    /**
     * Parse rows when the caller knows there is no header row.
     * Uses positional fallback column mapping directly.
     *
     * Called from {@link com.bankextractor.service.BankStatementService}
     * when {@link com.bankextractor.model.ManualExtractionConfig#isHasHeaderRow()} is false.
     */
    public List<Transaction> parseRowsNoHeader(List<List<String>> rows, int pageNumber) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();

        int numCols = rows.get(0).size();
        int[] colMap = positionalFallbackMap(numCols);
        log.debug("Page {} (no-header mode): colMap={}", pageNumber, Arrays.toString(colMap));

        List<Transaction> result = new ArrayList<>();
        LocalDate lastDate = null;

        for (int i = 0; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            Transaction tx = parseRow(cells, colMap, pageNumber, i + 1, lastDate);
            if (tx != null) {
                result.add(tx);
                if (tx.getDate() != null) lastDate = tx.getDate();
            }
        }

        log.info("Page {} (no-header): parsed {}/{} rows into transactions",
                pageNumber, result.size(), rows.size());
        return result;
    }


}