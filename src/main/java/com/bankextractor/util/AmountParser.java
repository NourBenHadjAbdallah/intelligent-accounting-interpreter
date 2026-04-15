package com.bankextractor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AmountParser {

    private static final Logger log = LoggerFactory.getLogger(AmountParser.class);

    // US format:  1,234.56  or  1234.56  or  $1,234.56
    private static final Pattern US_AMOUNT_PATTERN = Pattern.compile(
            "(?<![\\d,\\.])([\\-+]?\\(?[\\$€£]?)(" +
            "\\d{1,3}(?:,\\d{3})*\\.\\d{1,2}" +   // 1,234.56
            "|\\d+\\.\\d{1,2}" +                    // 1234.56
            ")\\)?(?![\\d,\\.])"
    );

    /**
     * European format:  1.234,56  or  1 234,56  or  30,65
     *
     * FIX: The original pattern used [\s\.] which relies on \s matching a regular
     * space — but in PDFBox output the thousands separator is often a plain ASCII
     * space (0x20), while \u00A0 (non-breaking space) is rarer than expected.
     * The pattern now explicitly lists the three possible separators:
     *   - regular space    (0x20)
     *   - non-breaking     (\u00A0)
     *   - narrow nb-space  (\u202F)  — used in some French PDFs
     *   - dot              (.)       — "1.234,56" style
     *
     * Lookahead (?![\d,]) prevents matching inside longer numbers.
     * Lookbehind (?<![\d]) prevents partial-matching "123 456,78" as "456,78".
     */
    private static final Pattern EU_AMOUNT_PATTERN = Pattern.compile(
            "(?<![\\d])([\\-+]?\\()?" +
            "(" +
            "\\d{1,3}(?:[ \\u00A0\\u202F\\.]\\d{3})+,\\d{1,2}" +  // 1 234,56 / 1.234,56
            "|\\d+,\\d{2}" +                                         // 30,65
            ")" +
            "\\)?(?![\\d,])"
    );

    private final boolean europeanFormat;

    public AmountParser(boolean europeanFormat) {
        this.europeanFormat = europeanFormat;
    }

    /**
     * Parse a single raw string into a BigDecimal.
     *
     * Normalisation steps (European mode):
     *   1. Replace all Unicode spaces (NBSP, narrow NBSP) with regular space.
     *   2. Strip everything except digits, space, . , ( ) - +
     *   3. Remove spaces (French thousands separator).
     *   4. Remove dots (dot thousands separator, e.g. "1.234,56").
     *   5. Replace comma with dot (decimal separator).
     *
     * This correctly handles all of:
     *   "2 543,19"  → 2543.19
     *   "1 631"     → 1631.00  (no decimal — treated as whole number)
     *   "1.954,00"  → 1954.00
     *   "30,65"     → 30.65
     *   "109,43"    → 109.43
     */
    public BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim()
                .replaceAll("[\\u00A0\\u202F\\u2009]", " ")  // Unicode spaces → regular space
                .replaceAll("[^\\d .,()\\-+]", "")
                .trim();
        if (cleaned.isEmpty()) return null;

        boolean negative = cleaned.startsWith("-") || cleaned.startsWith("(");
        cleaned = cleaned.replaceAll("[()\\-+]", "").trim();

        try {
            String normalized;
            if (europeanFormat) {
                normalized = cleaned
                        .replaceAll("\\s+", "")   // remove spaces (French thousands)
                        .replace(".", "")          // remove dot thousands separator
                        .replace(",", ".");         // comma decimal → dot decimal
            } else {
                normalized = cleaned
                        .replaceAll("\\s+", "")
                        .replace(",", "");
            }
            if (normalized.isEmpty()) return null;

            // Handle whole-number amounts like "1 631" (no decimal part after normalisation)
            // BigDecimal handles these fine — "1631" parses as 1631
            BigDecimal value = new BigDecimal(normalized);
            return negative ? value.negate() : value;
        } catch (NumberFormatException e) {
            log.debug("Could not parse amount: '{}'", raw);
            return null;
        }
    }

    /**
     * Extract all amounts from a line of text.
     *
     * Group capture fix: EU_AMOUNT_PATTERN group 1 is the optional sign/paren,
     * group 2 is the numeric part. We concatenate them before passing to parse().
     * If group 1 is null (no sign), we use empty string.
     */
    public List<ParsedAmount> extractAmounts(String line) {
        List<ParsedAmount> result = new ArrayList<>();
        if (line == null || line.isBlank()) return result;

        // Normalise Unicode spaces in the line before matching so the pattern
        // can see regular spaces as thousands separators.
        String normalised = line.replaceAll("[\\u00A0\\u202F\\u2009]", " ");

        Pattern pattern = europeanFormat ? EU_AMOUNT_PATTERN : US_AMOUNT_PATTERN;
        Matcher m = pattern.matcher(normalised);
        while (m.find()) {
            String sign    = m.group(1) != null ? m.group(1) : "";
            String numeric = m.group(2);
            BigDecimal amount = parse(sign + numeric);
            if (amount != null && amount.abs().compareTo(BigDecimal.ZERO) != 0)
                result.add(new ParsedAmount(amount, m.start(), m.end(), m.group()));
        }
        return result;
    }

    /**
     * Detect whether the sample text uses European number formatting.
     *
     * Improvement: also count space-separated thousands ("2 543,19") which
     * the original pattern missed when only looking for \d+,\d{2}.
     */
    public static boolean detectEuropeanFormat(String sampleText) {
        if (sampleText == null) return false;
        // European: comma as decimal separator — "30,65" or "2 543,19"
        long eu = Pattern.compile("\\b\\d[\\d ]*,\\d{2}\\b").matcher(sampleText).results().count();
        // US: dot as decimal separator — "30.65" or "1,234.56"
        long us = Pattern.compile("\\b\\d+\\.\\d{2}\\b").matcher(sampleText).results().count();
        return eu > us;
    }

    public static String detectCurrency(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\b(USD|EUR|GBP|TND|MAD|DZD|CAD|AUD|CHF|JPY|CNY|SAR|AED)\\b")
                .matcher(text.toUpperCase());
        if (m.find()) return m.group(1);
        if (text.contains("€"))  return "EUR";
        if (text.contains("$"))  return "USD";
        if (text.contains("£"))  return "GBP";
        return null;
    }

    public record ParsedAmount(BigDecimal value, int startIndex, int endIndex, String raw) {
        public boolean isNegative() { return value.compareTo(BigDecimal.ZERO) < 0; }
    }
}