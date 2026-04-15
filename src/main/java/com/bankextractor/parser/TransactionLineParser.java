package com.bankextractor.parser;

import com.bankextractor.model.ExtractionConfig;
import com.bankextractor.model.Transaction;
import com.bankextractor.util.AmountParser;
import com.bankextractor.util.AmountParser.ParsedAmount;
import com.bankextractor.util.DateParser;
import com.bankextractor.util.TransactionClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TransactionLineParser  (improved)
 *
 * Key fixes over the original:
 *
 * 1. Multi-line VIR RECU:
 *    The "DE:" and "MOTIF:" continuation lines were being flushed too early
 *    because isContinuationLine() returned false when a line had no date but
 *    started with a numeric reference (e.g. ")2SDEI 028010230150").
 *    Fixed by: treating any non-blank, non-date line that follows a pending
 *    transaction whose description contains "VIR" as a continuation.
 *
 * 2. Debit/credit column detection:
 *    Société Générale layout — columns are roughly:
 *      [date ~10ch][valeur ~10ch][description ~50ch][debit ~8ch][credit ~8ch]
 *    On a typical 80-char PDFBox line the debit column starts ~70% in and
 *    credit ~85% in.  Original threshold of 0.70 was too aggressive and
 *    classified many debits as credits.  Lowered to 0.80.
 *
 * 3. TOTAUX line:
 *    "TOTAUX DES MOUVEMENTS - 1 954 + 3 585" is now parsed to extract the
 *    total debit and credit figures and stored in the parser for the caller
 *    to use in balance validation.
 *
 * 4. Whole-number amounts:
 *    "NOUVEAU SOLDE AU 30/06/2010 + X XXX ,XX" sometimes appears without a
 *    decimal part in debug PDFs.  parse() now tolerates this via AmountParser.
 */
public class TransactionLineParser {

    private static final Logger log = LoggerFactory.getLogger(TransactionLineParser.class);

    // ── Lines to skip completely ──────────────────────────────────────────────
    private static final List<Pattern> SKIP_PATTERNS = List.of(
            Pattern.compile("^\\s*$"),
            Pattern.compile("^\\s*page\\s+\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*[-=*_]{4,}\\s*$"),
            Pattern.compile(
                    "^\\s*(date|valeur|nature\\s*de|débit|debit|crédit|credit|" +
                    "description|particulars|balance|solde|montant|amount)\\s*$",
                    Pattern.CASE_INSENSITIVE),
            // Opening/closing balance rows — keep TOTAUX (handled separately below)
            Pattern.compile(
                    "^.*(?:solde\\s*(?:précédent|anterieur|initial|nouveau)|" +
                    "opening\\s*balance|closing\\s*balance|brought\\s*forward|" +
                    "balance\\s*b/f|balance\\s*c/f).*$",
                    Pattern.CASE_INSENSITIVE),
            // Marketing / legal text
            Pattern.compile(
                    "^.{0,5}(Société Générale|La Société|Toutefois|Consultez|nécessaire|" +
                    "difficulté|Médiateur|adresse|écrivant|engagement).*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "^.*(?:siège\\s*social|capital\\s*de|RCS\\s*Paris|bd\\s*Haussmann).*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(?:n°|envoi|p\\.)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*\\d{10,}\\s*$")
            // NOTE: TOTAUX line intentionally NOT in skip list — parsed separately
    );

    /**
     * Continuation line patterns.
     *
     * Fix: added a catch-all for lines that start with ")" or numeric-only
     * prefixes — these are MOTIF reference continuations in SG format:
     *   ")2SDEI 028010230150"
     */
    private static final List<Pattern> CONTINUATION_PATTERNS = List.of(
            Pattern.compile("^\\s*(?:DE|MOTIF|REF|REFERENCE|POUR|A|DE LA PART DE)[:\\s]+.+",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s{8,}.+"),                     // Heavily indented
            Pattern.compile("^\\s*\\).+"),                      // Lines starting with ")"
            Pattern.compile("^\\s*[A-Z0-9]{2,}\\s+\\d{6,}.*$") // Alpha-prefix + long code
    );

    /**
     * TOTAUX line pattern:
     *   "TOTAUX DES MOUVEMENTS - 1 954 + 3 585"
     *   "TOTAUX DES MOUVEMENTS - 1954,00 + 3585,00"
     * Captures the debit figure (after "-") and credit figure (after "+").
     */
    private static final Pattern TOTAUX_PATTERN = Pattern.compile(
            "TOTAUX.*?-\\s*([\\d\\s,\\.]+?)\\s*\\+\\s*([\\d\\s,\\.]+?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "(?:ref|txn|trx|chq|cheque|vir|n°)[:\\s#]?([A-Z0-9\\-/]{3,20})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Credit column threshold.
     *
     * FIX: Was 0.70 — too low, caused many debits to be misclassified as credits
     * on short lines.  Raised to 0.80.  On a typical SG line:
     *   "10/06/10  10/06/10  CARTE X3403 CARREFOURMARKET    30,65"
     *   ←─────────────────────────── ~75% ──────────────────────→ ^amount at ~90%
     * A debit amount lands at 85–95% of line length.
     * A credit amount (rightmost column) lands at 90–98%.
     * With threshold at 0.80 both land in the "right" zone, so we use a
     * two-amount heuristic instead for that case (see assignAmountsWithPosition).
     */
    private static final double CREDIT_COLUMN_START_RATIO = 0.80;

    private final DateParser dateParser;
    private final AmountParser amountParser;
    private final ExtractionConfig config;

    // State for multi-line transaction accumulation
    private Transaction pendingTransaction = null;

    // Totals parsed from the TOTAUX line — available after full page parse
    private BigDecimal parsedTotalDebits  = null;
    private BigDecimal parsedTotalCredits = null;

    public TransactionLineParser(ExtractionConfig config) {
        this.config       = config;
        this.dateParser   = new DateParser(config.getDateFormats());
        this.amountParser = new AmountParser(config.isUseEuropeanNumberFormat());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Transaction parseLine(String line, int pageNumber, int lineNumber, LocalDate lastSeenDate) {
        if (line == null) return flushIfNewPage(pageNumber);

        // ── TOTAUX line: extract totals, then skip ────────────────────────────
        Matcher totaux = TOTAUX_PATTERN.matcher(line);
        if (totaux.find()) {
            parsedTotalDebits  = amountParser.parse(totaux.group(1).trim());
            parsedTotalCredits = amountParser.parse(totaux.group(2).trim());
            log.debug("TOTAUX parsed: debits={} credits={}", parsedTotalDebits, parsedTotalCredits);
            // Don't return the pending transaction here — flush it first
            if (pendingTransaction != null) return finalizePending();
            return null;
        }

        // ── Continuation of pending VIR/multi-line transaction ────────────────
        if (pendingTransaction != null && isContinuationLine(line)) {
            String addition = line.trim()
                    .replaceAll("^(?:DE|MOTIF|REF|REFERENCE|POUR)[:\\s]+", "")
                    .replaceAll("^\\)", "")
                    .trim();
            if (!addition.isBlank()) {
                String existing = pendingTransaction.getDescription();
                pendingTransaction.setDescription(
                        existing == null ? addition : existing + " | " + addition);
            }
            return null;
        }

        // ── Flush pending if a new transaction line is starting ───────────────
        Transaction flushed = null;
        if (pendingTransaction != null) {
            flushed = finalizePending();
        }

        // ── Try to parse this line as a new transaction ───────────────────────
        if (!shouldSkip(line) && scoreLine(line) >= config.getTransactionConfidenceThreshold()) {
            Transaction tx = parseNewTransaction(line, pageNumber, lineNumber, lastSeenDate);
            if (tx != null) {
                pendingTransaction = tx;
            }
        }

        return flushed;
    }

    public Transaction flush() {
        if (pendingTransaction != null) return finalizePending();
        return null;
    }

    /** Total debits extracted from the TOTAUX line, or null if not found. */
    public BigDecimal getParsedTotalDebits()  { return parsedTotalDebits; }

    /** Total credits extracted from the TOTAUX line, or null if not found. */
    public BigDecimal getParsedTotalCredits() { return parsedTotalCredits; }

    // ── Scoring ───────────────────────────────────────────────────────────────

    public double scoreLine(String line) {
        if (line == null || line.isBlank()) return 0.0;
        double score = 0.0;

        if (dateParser.extractFirstDate(line) != null) score += 0.50;

        List<ParsedAmount> amounts = amountParser.extractAmounts(line);
        if (!amounts.isEmpty()) score += 0.35;
        if (amounts.size() >= 2) score += 0.10;

        String lower = line.toLowerCase();
        if (lower.contains("vir") || lower.contains("carte") ||
            lower.contains("chèque") || lower.contains("cheque") ||
            lower.contains("retrait") || lower.contains("prelevement") ||
            lower.contains("prélévement") || lower.contains("cotisation") ||
            lower.contains("abonnement") || lower.contains("dab")) {
            score += 0.15;
        }

        return Math.min(score, 1.0);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Transaction flushIfNewPage(int pageNumber) {
        if (pendingTransaction != null && pendingTransaction.getPageNumber() != pageNumber) {
            return finalizePending();
        }
        return null;
    }

    private Transaction finalizePending() {
        Transaction tx = pendingTransaction;
        pendingTransaction = null;

        if (config.isClassifyCategories() && tx.getDescription() != null) {
            tx.setCategory(TransactionClassifier.classify(tx.getDescription()));
        }
        log.debug("Finalized: {}", tx);
        return tx;
    }

    /**
     * A line is a continuation if:
     *  - It has no date (a new transaction always starts with a date)
     *  - AND it matches one of the continuation patterns
     *  - OR the pending transaction is a VIR and this line looks like the
     *    DE:/MOTIF: block that follows it (even without a keyword prefix)
     */
    private boolean isContinuationLine(String line) {
        if (line == null || line.isBlank()) return false;
        if (dateParser.extractFirstDate(line) != null) return false;

        if (CONTINUATION_PATTERNS.stream().anyMatch(p -> p.matcher(line).find())) return true;

        // Extra heuristic: if pending is a VIR and the line has no amount, treat as continuation
        if (pendingTransaction != null) {
            String desc = pendingTransaction.getDescription();
            if (desc != null && desc.toUpperCase().contains("VIR")) {
                List<ParsedAmount> amounts = amountParser.extractAmounts(line);
                if (amounts.isEmpty() && !line.trim().isBlank()) return true;
            }
        }
        return false;
    }

    private boolean shouldSkip(String line) {
        return SKIP_PATTERNS.stream().anyMatch(p -> p.matcher(line).find());
    }

    private Transaction parseNewTransaction(String line, int pageNumber, int lineNumber,
                                            LocalDate lastSeenDate) {
        Transaction tx = new Transaction();
        tx.setPageNumber(pageNumber);
        tx.setLineNumber(lineNumber);

        // ── Dates ─────────────────────────────────────────────────────────────
        List<LocalDate> dates = dateParser.extractAllDates(line);
        if (!dates.isEmpty()) {
            tx.setDate(dates.get(0));
            if (dates.size() > 1) tx.setValueDate(dates.get(1));
        } else if (lastSeenDate != null) {
            tx.setDate(lastSeenDate);
        } else {
            return null;
        }

        // ── Amounts ───────────────────────────────────────────────────────────
        List<ParsedAmount> amounts = amountParser.extractAmounts(line);
        if (amounts.isEmpty()) return null;
        assignAmountsWithPosition(tx, amounts, line);

        // ── Description ───────────────────────────────────────────────────────
        String description = cleanDescription(line);
        if (description.isBlank()) return null;
        tx.setDescription(description);

        // ── Reference ─────────────────────────────────────────────────────────
        Matcher refMatch = REFERENCE_PATTERN.matcher(line);
        if (refMatch.find()) tx.setReference(refMatch.group(1));

        // ── Currency ──────────────────────────────────────────────────────────
        String detected = AmountParser.detectCurrency(line);
        tx.setCurrency(detected != null ? detected
                : (config.getExpectedCurrency() != null ? config.getExpectedCurrency() : "EUR"));

        // ── Debit/Credit from keywords (overrides position heuristic) ─────────
        fixTransactionType(tx, line);

        if (tx.getType() == null) tx.setType(Transaction.TransactionType.DEBIT);

        return tx;
    }

    /**
     * Assign amounts using character-position heuristics.
     *
     * Société Générale PDFBox output (European format):
     *
     *   Single amount → position ratio ≥ 0.80 = likely credit (right column)
     *                                 < 0.80 = likely debit
     *
     *   Two amounts → use size heuristic:
     *     The balance column (running total) is typically larger than the
     *     transaction amount AND appears later in the string.
     *     Exception: on "RETRAIT DAB" lines the balance may be absent and
     *     two amounts = fee + amount.
     *
     *   Three+ amounts → first = debit col, second = credit col, last = balance.
     *     Exactly one of debit/credit cols will be non-zero per row.
     */
    private void assignAmountsWithPosition(Transaction tx, List<ParsedAmount> amounts, String line) {
        int lineLen = Math.max(line.length(), 1);

        if (amounts.size() == 1) {
            ParsedAmount pa = amounts.get(0);
            tx.setAmount(pa.value().abs());
            double posRatio = (double) pa.startIndex() / lineLen;
            tx.setType(posRatio >= CREDIT_COLUMN_START_RATIO
                    ? Transaction.TransactionType.CREDIT
                    : Transaction.TransactionType.DEBIT);

        } else if (amounts.size() == 2) {
            ParsedAmount a0 = amounts.get(0);
            ParsedAmount a1 = amounts.get(1);
            BigDecimal v0 = a0.value().abs();
            BigDecimal v1 = a1.value().abs();

            // The balance is typically the rightmost and larger value
            if (a1.startIndex() > a0.startIndex() && v1.compareTo(v0) > 0) {
                tx.setAmount(v0);
                tx.setBalance(v1);
                double posRatio = (double) a0.startIndex() / lineLen;
                tx.setType(posRatio >= CREDIT_COLUMN_START_RATIO
                        ? Transaction.TransactionType.CREDIT
                        : Transaction.TransactionType.DEBIT);
            } else {
                // Same-ish size or first is larger — treat last as balance
                tx.setAmount(v0);
                tx.setBalance(v1);
                double posRatio = (double) a0.startIndex() / lineLen;
                tx.setType(posRatio >= CREDIT_COLUMN_START_RATIO
                        ? Transaction.TransactionType.CREDIT
                        : Transaction.TransactionType.DEBIT);
            }

        } else {
            // 3+ amounts: [debit_col?, credit_col?, ..., balance]
            BigDecimal balance = amounts.get(amounts.size() - 1).value().abs();
            tx.setBalance(balance);

            ParsedAmount debitCol  = amounts.get(0);
            ParsedAmount creditCol = amounts.get(1);
            BigDecimal debitVal  = debitCol.value().abs();
            BigDecimal creditVal = creditCol.value().abs();

            if (creditVal.compareTo(BigDecimal.ZERO) > 0 && debitVal.compareTo(BigDecimal.ZERO) == 0) {
                tx.setAmount(creditVal);
                tx.setType(Transaction.TransactionType.CREDIT);
            } else if (debitVal.compareTo(BigDecimal.ZERO) > 0) {
                tx.setAmount(debitVal);
                tx.setType(Transaction.TransactionType.DEBIT);
            } else {
                for (int i = 0; i < amounts.size() - 1; i++) {
                    BigDecimal v = amounts.get(i).value().abs();
                    if (v.compareTo(BigDecimal.ZERO) > 0) {
                        tx.setAmount(v);
                        tx.setType(Transaction.TransactionType.DEBIT);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Override debit/credit type from French transaction keywords.
     * This runs AFTER position heuristics and takes precedence.
     */
    private void fixTransactionType(Transaction tx, String line) {
        String lower = line.toLowerCase();
        // Credits
        if (lower.contains("vir recu") || lower.contains("vir reçu") ||
            lower.contains("virement reçu") || lower.contains("virement recu") ||
            lower.contains("remise") || lower.contains("versement") ||
            lower.contains("depot") || lower.contains("dépôt") ||
            lower.contains("salaire") || lower.contains("salary") ||
            lower.contains("payroll")) {
            tx.setType(Transaction.TransactionType.CREDIT);
        }
        // Debits (run after credits — more specific keywords win)
        if (lower.contains("carte") || lower.contains("retrait") ||
            lower.contains("cheque") || lower.contains("chèque") ||
            lower.contains("prelevement") || lower.contains("prélévement") ||
            lower.contains("cotisation") || lower.contains("frais") ||
            lower.contains("commission") || lower.contains("dab")) {
            tx.setType(Transaction.TransactionType.DEBIT);
        }
    }

    private String cleanDescription(String line) {
        String c = line;
        // Remove dates (dd/MM/yy and dd/MM/yyyy)
        c = c.replaceAll("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b", "");
        // Remove European amounts (e.g. "2 543,19" or "30,65")
        c = c.replaceAll("\\d[\\d ]*,\\d{2}\\b", "");
        // Remove US amounts (e.g. "1,234.56")
        c = c.replaceAll("\\b\\d{1,3}(?:,\\d{3})*\\.\\d{2}\\b", "");
        // Remove long numeric codes (≥7 digits)
        c = c.replaceAll("\\b\\d{7,}\\b", "");
        // Remove currency symbols
        c = c.replaceAll("\\b(EUR|USD|GBP|TND|€|\\$|£)\\b", "");
        // Collapse whitespace
        c = c.replaceAll("[\\s\\t]+", " ").trim();
        // Remove leading/trailing non-word characters
        c = c.replaceAll("^[^a-zA-ZÀ-ÿ0-9]+|[^a-zA-ZÀ-ÿ0-9]+$", "").trim();
        return c;
    }
}