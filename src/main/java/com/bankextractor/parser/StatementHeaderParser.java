package com.bankextractor.parser;

import com.bankextractor.model.BankStatement;
import com.bankextractor.util.AmountParser;
import com.bankextractor.util.DateParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StatementHeaderParser
 *
 * Extracts every piece of structured metadata from a bank statement's header pages.
 * Designed against real Société Générale PDFs but generalised for French/English banks.
 *
 * Fields extracted
 * ────────────────
 * Bank:        name, head-office address, RCS registration, capital, BIC
 * Branch:      name, address, phone, fax
 * Advisor:     name, phone
 * Holder:      name, postal address, client code
 * Account:     number, IBAN, type, currency, RIB (banque / agence / clé)
 * Statement:   period (from/to), reference (envoi n°X p. Y/Z)
 * Balances:    opening, closing
 * Loyalty:     program name, member number, points breakdown
 */
public class StatementHeaderParser {

    private static final Logger log = LoggerFactory.getLogger(StatementHeaderParser.class);

    // ── Account number ────────────────────────────────────────────────────────
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile(
            "(?:n°|compte\\s*n°?|account\\s*(?:number|no\\.?|#)|a/c)[:\\s]+([\\d\\s]{6,35})",
            Pattern.CASE_INSENSITIVE);


    //
    // Strategy 2 — standalone IBAN (no label):
    //   Requires a newline, comma, or end-of-string after the last digit group
    //   via a lookahead so we don't over-capture into the next field.
    private static final Pattern IBAN_LABELED = Pattern.compile(
    	    "(?<![\\w])([A-Z]{2}\\d{2}[A-Z0-9 \\t\\u00A0]{11,33})(?=[,\\n]|$)",
    	    Pattern.CASE_INSENSITIVE);

    private static final Pattern IBAN_STANDALONE = Pattern.compile(
            "\\b([A-Z]{2}\\d{2}[A-Z0-9 \\t\\u00A0]{11,32})(?=[\\n\\r,;\"\\s]|$)",
            Pattern.CASE_INSENSITIVE);

    // ── BIC/SWIFT ─────────────────────────────────────────────────────────────
    // e.g. "bic SOGEFRPP"  or  "BIC: BNPAFRPP"
    private static final Pattern BIC_PATTERN = Pattern.compile(
            "\\bbic[:\\s]+([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── RIB row: Banque  Agence  Numéro de compte  Clé ────────────────────────
    // The line after the "Banque Agence Numéro de compte Clé" header contains
    // the actual values, e.g.  "30003  01234  00012345678  12"
    private static final Pattern RIB_VALUES = Pattern.compile(
            "^\\s*(\\d{5})\\s+(\\d{5})\\s+(\\d{10,14})\\s+(\\d{2})\\s*$");

    // ── Account type ──────────────────────────────────────────────────────────
    // "COMPTE DE PARTICULIER"  /  "COMPTE COURANT"  /  "CURRENT ACCOUNT"
    private static final Pattern ACCOUNT_TYPE = Pattern.compile(
            "(COMPTE\\s+(?:DE\\s+)?(?:PARTICULIER|COURANT|EPARGNE|PROFESSIONNEL)|" +
            "CURRENT\\s+ACCOUNT|SAVINGS\\s+ACCOUNT|CHECKING\\s+ACCOUNT)",
            Pattern.CASE_INSENSITIVE);

    // ── Account holder (from RIB section) ─────────────────────────────────────
    // "titulaire du compte Mme Barbara MARTINON"
    private static final Pattern ACCOUNT_HOLDER = Pattern.compile(
            "(?:titulaire(?:\\s*du\\s*compte)?|name|holder|client|customer|prepared\\s*for)" +
            "[:\\s]+(?:M(?:me?|r\\.?)?[\\s\\.]+)?([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ\\s\\.,'\\-]{2,60})",
            Pattern.CASE_INSENSITIVE);

    // ── Client code ───────────────────────────────────────────────────────────
    // "Barbara MARTINON : 12345678"  or  "Code client ... 12345678"
    private static final Pattern CLIENT_CODE = Pattern.compile(
            "(?:code\\s+client|client\\s+(?:code|id|number)|" +
            "[A-Za-zÀ-ÿ]+\\s+[A-Za-zÀ-ÿ]+\\s*:\\s*)(\\d{6,12})",
            Pattern.CASE_INSENSITIVE);

    // ── Statement period — French: "du 10 02 2011 au 09 03 2011" ─────────────
    private static final Pattern PERIOD_FRENCH = Pattern.compile(
            "du\\s+(\\d{1,2}[\\s/\\-\\.](\\d{1,2}|[A-Za-z]{3,9})[\\s/\\-\\.]\\d{2,4})" +
            "\\s+au\\s+(\\d{1,2}[\\s/\\-\\.](\\d{1,2}|[A-Za-z]{3,9})[\\s/\\-\\.]\\d{2,4})",
            Pattern.CASE_INSENSITIVE);

    // English: "from ... to ..."
    private static final Pattern PERIOD_ENGLISH = Pattern.compile(
            "(?:period|from)[:\\s]+([\\d/\\-\\.\\w]+)\\s*(?:to|through|\\-|–)\\s*([\\d/\\-\\.\\w]+)",
            Pattern.CASE_INSENSITIVE);

    // ── Statement reference: "envoi n°7 p. 1/3" ──────────────────────────────
    private static final Pattern STMT_REFERENCE = Pattern.compile(
            "envoi\\s+n°\\s*\\d+\\s+p\\.?\\s*\\d+/\\d+",
            Pattern.CASE_INSENSITIVE);

    // ── Opening balance ───────────────────────────────────────────────────────
    private static final Pattern OPENING_BALANCE_FR = Pattern.compile(
            "SOLDE\\s*(?:PR[ÉE]C[ÉE]DENT|ANTERIOR|INITIAL|REPORT[ÉE])[^\\d]*([\\d\\s]+[,\\.][\\d]{2})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OPENING_BALANCE_EN = Pattern.compile(
            "(?:opening\\s*balance|brought\\s*forward|balance\\s*b/?f)[:\\s]+([\\-+]?[\\d,\\.]+)",
            Pattern.CASE_INSENSITIVE);

    // ── Closing balance ───────────────────────────────────────────────────────
    // "NOUVEAU SOLDE AU 09/03/2011 + 1 631"  or  "*** SOLDE AU ... + X XXX,XX ***"
    private static final Pattern CLOSING_BALANCE_FR = Pattern.compile(
            "(?:NOUVEAU\\s*SOLDE|SOLDE\\s*AU)[^\\d+\\-]*([+\\-]?[\\d\\s]+[,\\.][\\d]{2}|[+\\-]?[\\d\\s]{1,12})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLOSING_BALANCE_EN = Pattern.compile(
            "(?:closing\\s*balance|carried\\s*forward|balance\\s*c/?f|ending\\s*balance)[:\\s]+([\\-+]?[\\d,\\.]+)",
            Pattern.CASE_INSENSITIVE);

    // ── Currency ──────────────────────────────────────────────────────────────
    private static final Pattern CURRENCY_LABEL = Pattern.compile(
            "(?:en\\s+)?(euros?|EUR|USD|GBP|TND|MAD|DZD)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── Branch name: "Votre agence Château" ───────────────────────────────────
    private static final Pattern BRANCH_NAME = Pattern.compile(
            "(?:votre\\s+agence|agence)[:\\s]+([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ\\s'\\-]{1,50})",
            Pattern.CASE_INSENSITIVE);

    // ── Phone numbers — French format: "01 23 45 67 89" or "+33 1 23 45 67 89" ─
    private static final Pattern PHONE = Pattern.compile(
            "(?:t[eé]l[eé]phone?|t[eé]l\\.|phone|tel)[:\\s]+([\\d\\s\\.\\+\\(\\)]{8,20})",
            Pattern.CASE_INSENSITIVE);

    // ── Fax ───────────────────────────────────────────────────────────────────
    private static final Pattern FAX = Pattern.compile(
            "fax[:\\s]+([\\d\\s\\.\\+\\(\\)]{8,20})",
            Pattern.CASE_INSENSITIVE);

    // ── Advisor: "M. Nicolas DUPONDT" ─────────────────────────────────────────
    // Appears after "Votre Conseiller en agence"
    private static final Pattern ADVISOR_LABEL = Pattern.compile(
            "(?:votre\\s+conseiller|conseiller\\s+en\\s+agence|your\\s+advisor)[^\\n]*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ADVISOR_NAME = Pattern.compile(
            "^\\s*(M(?:me?|r\\.?)?[\\s\\.]+[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ\\s\\.\\-]{2,50})\\s*$");

    // ── Bank head-office: "29, bd Haussmann 75009 Paris" ─────────────────────
    private static final Pattern BANK_ADDRESS = Pattern.compile(
            "(\\d+[,\\s]+(?:bd|boulevard|rue|av(?:enue)?|place)[\\s,]+[A-Za-zÀ-ÿ\\s\\d,]+\\d{5}\\s+[A-Za-zÀ-ÿ]+)",
            Pattern.CASE_INSENSITIVE);

    // ── RCS registration: "552 120 222 RCS Paris" ────────────────────────────
    private static final Pattern RCS = Pattern.compile(
            "(\\d[\\d\\s]{5,15}RCS\\s+[A-Za-zÀ-ÿ]+)",
            Pattern.CASE_INSENSITIVE);

    // ── Capital: "S.A. au capital de 933 027 038,75 Eur" ─────────────────────
    private static final Pattern CAPITAL = Pattern.compile(
            "capital\\s+de\\s+([\\d\\s,\\.]+(?:Eur|EUR|€|USD|GBP))",
            Pattern.CASE_INSENSITIVE);

    // ── Loyalty program ───────────────────────────────────────────────────────
    // "N° d'adhérent JAZZ : 0000000001234567"
    private static final Pattern LOYALTY_MEMBER = Pattern.compile(
            "n°\\s+d'adh[eé]rent\\s+(\\w+)[:\\s]+([\\d]+)",
            Pattern.CASE_INSENSITIVE);

    // Loyalty score line: "3 000\nsolde précédent\n650\npoints acquis..."
    // We parse this from a multi-line block: look for integer-only lines near keywords
    private static final Pattern LOYALTY_POINTS_LINE = Pattern.compile(
            "(\\d[\\d\\s]*)\\s*\\n\\s*(solde\\s+pr[eé]c[eé]dent|points\\s+acquis|points\\s+utilis[eé]s|points\\s+annul[eé]s|nouveau\\s+solde)",
            Pattern.CASE_INSENSITIVE);

    // ── Known banks ───────────────────────────────────────────────────────────
    private static final List<String[]> BANK_NAMES = List.of(
            new String[]{"Société Générale",  "Société Générale"},
            new String[]{"Societe Generale",  "Société Générale"},
            new String[]{"BNP Paribas",       "BNP Paribas"},
            new String[]{"BNP",               "BNP Paribas"},
            new String[]{"Crédit Agricole",   "Crédit Agricole"},
            new String[]{"Credit Agricole",   "Crédit Agricole"},
            new String[]{"Caisse d'Epargne",  "Caisse d'Épargne"},
            new String[]{"La Banque Postale", "La Banque Postale"},
            new String[]{"LCL",               "LCL"},
            new String[]{"Banque Populaire",  "Banque Populaire"},
            new String[]{"CIC",               "CIC"},
            new String[]{"HSBC",              "HSBC"},
            new String[]{"BNA",               "BNA"},
            new String[]{"STB",               "STB"},
            new String[]{"BIAT",              "BIAT"},
            new String[]{"Attijari",          "Attijari Bank"},
            new String[]{"UBCI",              "UBCI"},
            new String[]{"Amen Bank",         "Amen Bank"},
            new String[]{"Zitouna",           "Banque Zitouna"},
            new String[]{"Chase",             "Chase"},
            new String[]{"Wells Fargo",       "Wells Fargo"},
            new String[]{"Barclays",          "Barclays"},
            new String[]{"Deutsche",          "Deutsche Bank"},
            new String[]{"Bank of America",   "Bank of America"}
    );

    private final DateParser dateParser;
    private final AmountParser amountParser;

    public StatementHeaderParser(DateParser dateParser, AmountParser amountParser) {
        this.dateParser   = dateParser;
        this.amountParser = amountParser;
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Parse all available metadata from the header/footer lines of the statement.
     * Call with ALL page lines (not just page 1) so the RIB block on the last page
     * and the JAZZ loyalty block are also captured.
     */
    public void parseHeader(BankStatement stmt, List<String> lines) {
        String fullText = String.join("\n", lines);

        parseBankName(stmt, fullText, lines);
        parseBankDetails(stmt, fullText);
        parseBic(stmt, fullText);
        parseBranch(stmt, fullText, lines);
        parseAdvisor(stmt, lines);
        parseAccountHolder(stmt, fullText);
        parseClientCode(stmt, fullText);
        parseAccountHolderAddress(stmt, lines);
        parseAccountNumber(stmt, fullText);
        parseIban(stmt, fullText);
        parseRib(stmt, lines);
        parseAccountType(stmt, fullText);
        parseStatementPeriod(stmt, fullText);
        parseStatementReference(stmt, fullText);
        parseOpeningBalance(stmt, fullText);
        parseClosingBalance(stmt, fullText);
        parseCurrency(stmt, fullText);
        parseLoyalty(stmt, fullText);

        log.info("Header → bank='{}', bic='{}', holder='{}', address='{}', " +
                 "account='{}', iban='{}', period={}→{}, currency='{}', " +
                 "branch='{}', advisor='{}'",
                stmt.getBankName(), stmt.getBic(),
                stmt.getAccountHolder(), stmt.getAccountHolderAddress(),
                stmt.getAccountNumber(), stmt.getIban(),
                stmt.getStatementFrom(), stmt.getStatementTo(),
                stmt.getCurrency(), stmt.getBranch(), stmt.getAdvisorName());
    }

    // ── Bank ──────────────────────────────────────────────────────────────────

    private void parseBankName(BankStatement stmt, String text, List<String> lines) {
        for (String[] entry : BANK_NAMES) {
            if (text.contains(entry[0])) {
                stmt.setBankName(entry[1]);
                return;
            }
        }
        for (String line : lines) {
            String t = line.trim();
            if ((t.toLowerCase().contains("bank") || t.toLowerCase().contains("banque"))
                    && t.length() < 60) {
                stmt.setBankName(t);
                return;
            }
        }
    }

    private void parseBankDetails(BankStatement stmt, String text) {
        Matcher mAddr = BANK_ADDRESS.matcher(text);
        if (mAddr.find()) stmt.setBankAddress(mAddr.group(1).replaceAll("\\s+", " ").trim());

        Matcher mRcs = RCS.matcher(text);
        if (mRcs.find()) stmt.setBankRegistration(mRcs.group(1).replaceAll("\\s+", " ").trim());

        Matcher mCap = CAPITAL.matcher(text);
        if (mCap.find()) stmt.setBankCapital(mCap.group(1).replaceAll("\\s+", " ").trim());
    }

    private void parseBic(BankStatement stmt, String text) {
        Matcher m = BIC_PATTERN.matcher(text);
        if (m.find()) stmt.setBic(m.group(1).toUpperCase().trim());
    }

    // ── Branch ────────────────────────────────────────────────────────────────

    private void parseBranch(BankStatement stmt, String text, List<String> lines) {
        // Branch name: "Votre agence Château" or "AGENCE CHÂTEAU"
        Matcher mName = BRANCH_NAME.matcher(text);
        if (mName.find()) {
            String raw = mName.group(1).trim().replaceAll("\\s+", " ");
            // Remove trailing noise like phone numbers
            raw = raw.replaceAll("[\\d\\s/]+$", "").trim();
            if (!raw.isBlank()) stmt.setBranch(raw);
        }

        // Branch phone and fax — iterate lines looking for Phone/Fax after branch section
        boolean inBranchSection = false;
        String pendingPhone = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            String lower = line.toLowerCase();

            if (lower.contains("votre agence") || lower.startsWith("agence")) {
                inBranchSection = true;
            }
            if (lower.contains("votre conseiller") || lower.contains("your advisor")) {
                inBranchSection = false;
            }

            if (inBranchSection) {
                Matcher mPhone = PHONE.matcher(line);
                if (mPhone.find()) {
                    String phone = mPhone.group(1).trim();
                    if (stmt.getBranchPhone() == null) stmt.setBranchPhone(phone);
                    else pendingPhone = phone;
                }
                Matcher mFax = FAX.matcher(line);
                if (mFax.find() && stmt.getBranchFax() == null)
                    stmt.setBranchFax(mFax.group(1).trim());
            }
        }

        // Branch address: appears after "domiciliation agence société générale" in RIB block
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase();
            if (lower.contains("domiciliation") || lower.contains("agence château") ||
                lower.matches(".*agence\\s+[A-Za-zÀ-ÿ]+.*")) {
                // Next 2-3 lines may be the address
                StringBuilder addr = new StringBuilder();
                for (int j = i + 1; j < Math.min(i + 4, lines.size()); j++) {
                    String l = lines.get(j).trim();
                    // Address lines contain street numbers or postal codes
                    if (l.matches(".*\\d+.*") && l.length() < 80 && !l.matches(".*bic.*")
                            && !l.matches(".*iban.*") && !l.matches(".*t[eé]l.*")) {
                        if (addr.length() > 0) addr.append(", ");
                        addr.append(l);
                    } else if (l.matches("[A-Za-zÀ-ÿ\\s'\\-]{3,40}") && addr.length() > 0) {
                        // Town name after postal code
                        addr.append(" ").append(l);
                        break;
                    }
                }
                if (addr.length() > 5) {
                    stmt.setBranchAddress(addr.toString().trim());
                    break;
                }
            }
        }
    }

    // ── Advisor ───────────────────────────────────────────────────────────────

    private void parseAdvisor(BankStatement stmt, List<String> lines) {
        boolean nextIsAdvisor = false;
        boolean nextIsAdvisorPhone = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            String lower = line.toLowerCase();

            if (lower.contains("votre conseiller") || lower.contains("your advisor")) {
                nextIsAdvisor = true;
                continue;
            }

            if (nextIsAdvisor && !line.isBlank()) {
                // Should be "M. Nicolas DUPONDT"
                Matcher m = ADVISOR_NAME.matcher(line);
                if (m.matches()) {
                    stmt.setAdvisorName(m.group(1).trim());
                    nextIsAdvisor = false;
                    nextIsAdvisorPhone = true;
                } else {
                    nextIsAdvisor = false;
                }
                continue;
            }

            if (nextIsAdvisorPhone) {
                Matcher m = PHONE.matcher(line);
                if (m.find()) {
                    stmt.setAdvisorPhone(m.group(1).trim());
                    nextIsAdvisorPhone = false;
                } else if (!line.isBlank()) {
                    nextIsAdvisorPhone = false;
                }
            }
        }
    }

    // ── Account holder ────────────────────────────────────────────────────────

    private void parseAccountHolder(BankStatement stmt, String text) {
        Matcher m = ACCOUNT_HOLDER.matcher(text);
        if (m.find()) {
            String name = m.group(1).trim()
                    .replaceAll("\\s+", " ")
                    .replaceAll("[,\\.]+$", "");
            if (!name.isBlank() && name.length() > 2)
                stmt.setAccountHolder(name);
        }
    }

    private void parseClientCode(BankStatement stmt, String text) {
        Matcher m = CLIENT_CODE.matcher(text);
        if (m.find()) {
            String code = m.group(1).trim();
            // Must be 6-12 digits and NOT look like a phone or account number
            if (code.length() >= 6 && code.length() <= 12)
                stmt.setClientCode(code);
        }
    }

    /**
     * Account holder postal address.
     *
     * On Société Générale statements the holder's address appears in the top-right
     * block, directly below the holder's name (in ALL CAPS):
     *
     *   BARBARA MARTINON
     *   2, RUE PABLO PICASSO
     *   12345 MAVILLE
     *
     * We find the holder's name line (case-insensitive) and collect the 2 lines
     * that follow it, stopping at blank lines or lines that look like account info.
     */
    private void parseAccountHolderAddress(BankStatement stmt, List<String> lines) {
        if (stmt.getAccountHolder() == null) return;
        String holderUpper = stmt.getAccountHolder().toUpperCase().replaceAll("\\s+", " ").trim();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim().toUpperCase().replaceAll("\\s+", " ");
            if (line.equals(holderUpper) || line.contains(holderUpper)) {
                // Collect up to 3 address lines
                StringBuilder addr = new StringBuilder();
                for (int j = i + 1; j < Math.min(i + 4, lines.size()); j++) {
                    String next = lines.get(j).trim();
                    if (next.isBlank()) break;
                    // Stop at lines that look like account numbers, URLs, phone numbers
                    if (next.matches(".*\\.fr.*") || next.matches(".*\\.mobi.*")) break;
                    if (next.matches("\\d{10,}")) break;
                    if (addr.length() > 0) addr.append(", ");
                    addr.append(next);
                }
                if (addr.length() > 5) {
                    stmt.setAccountHolderAddress(addr.toString());
                    return;
                }
            }
        }
    }

    // ── Account ───────────────────────────────────────────────────────────────

    private void parseAccountNumber(BankStatement stmt, String text) {
        Matcher m = ACCOUNT_NUMBER.matcher(text);
        if (m.find()) {
            String raw = m.group(1).replaceAll("\\s+", " ").trim();
            if (!raw.isBlank()) stmt.setAccountNumber(raw);
        }
    }

    private void parseIban(BankStatement stmt, String text) {
        // Strategy 1: look for explicit "iban" keyword label — most reliable
        Matcher m1 = IBAN_LABELED.matcher(text);
        if (m1.find()) {
            String candidate = m1.group(1)
                    .replaceAll("[\\s\\u00A0 \\t]+", "")  // strip spaces and non-breaking spaces
                    .toUpperCase().trim();
            if (candidate.length() >= 15 && candidate.length() <= 34) {
                stmt.setIban(candidate);
                log.debug("IBAN found via label: {}", candidate);
                return;
            }
        }
        // Strategy 2: standalone IBAN (no label) — uses lookahead to avoid over-capture
        Matcher m2 = IBAN_STANDALONE.matcher(text);
        while (m2.find()) {
            String candidate = m2.group(1)
                    .replaceAll("[\\s\\u00A0 \\t]+", "")
                    .toUpperCase().trim();
            if (candidate.length() >= 15 && candidate.length() <= 34) {
                stmt.setIban(candidate);
                log.debug("IBAN found standalone: {}", candidate);
                return;
            }
        }
    }

    /**
     * Parse RIB components from the table that looks like:
     *
     *   Banque  Agence  Numéro de compte  Clé
     *   30003   01234   00012345678       12
     */
    private void parseRib(BankStatement stmt, List<String> lines) {
        boolean nextIsRibValues = false;
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("banque") && lower.contains("agence") && lower.contains("cl")) {
                nextIsRibValues = true;
                continue;
            }
            if (nextIsRibValues) {
                Matcher m = RIB_VALUES.matcher(line);
                if (m.matches()) {
                    stmt.setRibBanque(m.group(1));
                    stmt.setRibAgence(m.group(2));
                    // group(3) is the account number — set it if not already parsed
                    if (stmt.getAccountNumber() == null)
                        stmt.setAccountNumber(m.group(1) + " " + m.group(2) + " " + m.group(3) + " " + m.group(4));
                    stmt.setRibCle(m.group(4));
                    return;
                }
                // Allow one blank/non-matching line then give up
                if (!line.trim().isBlank()) nextIsRibValues = false;
            }
        }
    }

    private void parseAccountType(BankStatement stmt, String text) {
        Matcher m = ACCOUNT_TYPE.matcher(text);
        if (m.find()) stmt.setAccountType(m.group(1).trim().toUpperCase());
    }

    // ── Statement period & reference ──────────────────────────────────────────

    private void parseStatementPeriod(BankStatement stmt, String text) {
        Matcher mFr = PERIOD_FRENCH.matcher(text);
        if (mFr.find()) {
            LocalDate from = dateParser.parse(mFr.group(1).trim().replaceAll("\\s+", "/"));
            LocalDate to   = dateParser.parse(mFr.group(3).trim().replaceAll("\\s+", "/"));
            if (from != null) stmt.setStatementFrom(from);
            if (to   != null) stmt.setStatementTo(to);
            return;
        }
        Matcher mEn = PERIOD_ENGLISH.matcher(text);
        if (mEn.find()) {
            LocalDate from = dateParser.parse(mEn.group(1));
            LocalDate to   = dateParser.parse(mEn.group(2));
            if (from != null) stmt.setStatementFrom(from);
            if (to   != null) stmt.setStatementTo(to);
        }
    }

    private void parseStatementReference(BankStatement stmt, String text) {
        Matcher m = STMT_REFERENCE.matcher(text);
        if (m.find()) stmt.setStatementReference(m.group().trim());
    }

    // ── Balances ──────────────────────────────────────────────────────────────

    private void parseOpeningBalance(BankStatement stmt, String text) {
        Matcher mFr = OPENING_BALANCE_FR.matcher(text);
        if (mFr.find()) {
            BigDecimal b = amountParser.parse(mFr.group(1).replaceAll("\\s+", ""));
            if (b != null) { stmt.setOpeningBalance(b); return; }
        }
        Matcher mEn = OPENING_BALANCE_EN.matcher(text);
        if (mEn.find()) {
            BigDecimal b = amountParser.parse(mEn.group(1));
            if (b != null) stmt.setOpeningBalance(b);
        }
    }

    private void parseClosingBalance(BankStatement stmt, String text) {
        Matcher mFr = CLOSING_BALANCE_FR.matcher(text);
        if (mFr.find()) {
            String raw = mFr.group(1).replaceAll("\\s+", "").replaceAll("^\\+", "");
            BigDecimal b = amountParser.parse(raw);
            if (b != null) { stmt.setClosingBalance(b); return; }
        }
        Matcher mEn = CLOSING_BALANCE_EN.matcher(text);
        if (mEn.find()) {
            BigDecimal b = amountParser.parse(mEn.group(1));
            if (b != null) stmt.setClosingBalance(b);
        }
    }

    // ── Currency ──────────────────────────────────────────────────────────────

    private void parseCurrency(BankStatement stmt, String text) {
        Matcher m = CURRENCY_LABEL.matcher(text);
        if (m.find()) {
            String raw = m.group(1).toLowerCase();
            if (raw.startsWith("euro")) stmt.setCurrency("EUR");
            else                        stmt.setCurrency(raw.toUpperCase());
            return;
        }
        if (text.contains("€")) { stmt.setCurrency("EUR"); return; }
        if (text.contains("$")) { stmt.setCurrency("USD"); return; }
        if (text.contains("£")) { stmt.setCurrency("GBP"); return; }
        stmt.setCurrency("EUR");
    }

    // ── Loyalty program ───────────────────────────────────────────────────────

    /**
     * Parse JAZZ loyalty block:
     *
     *   N° d'adhérent JAZZ : 0000000001234567  Votre situation au : 09/03/2011
     *   3 000
     *   solde précédent
     *   650
     *   points acquis
     *   0
     *   points utilisés
     *   0
     *   points annulés
     *   3 650*
     *   nouveau solde
     */
    private void parseLoyalty(BankStatement stmt, String text) {
        Matcher mMember = LOYALTY_MEMBER.matcher(text);
        if (mMember.find()) {
            stmt.setLoyaltyProgramName(mMember.group(1).toUpperCase());
            stmt.setLoyaltyMemberNumber(mMember.group(2));
        }

        // Find points by scanning pairs: number line → label line
        String[] textLines = text.split("\n");
        for (int i = 0; i < textLines.length - 1; i++) {
            String valueLine = textLines[i].trim().replaceAll("[*\\s]", "");
            String labelLine = textLines[i + 1].trim().toLowerCase();

            if (!valueLine.matches("\\d+")) continue;
            int pts;
            try { pts = Integer.parseInt(valueLine); }
            catch (NumberFormatException e) { continue; }

            if (labelLine.contains("solde pr") || labelLine.contains("previous")) {
                stmt.setLoyaltyPointsPrevious(pts);
            } else if (labelLine.contains("acquis") || labelLine.contains("earned")) {
                stmt.setLoyaltyPointsEarned(pts);
            } else if (labelLine.contains("utilis") || labelLine.contains("used")) {
                stmt.setLoyaltyPointsUsed(pts);
            } else if (labelLine.contains("annul") || labelLine.contains("cancel")) {
                // skip cancelled points — not commonly surfaced
            } else if (labelLine.contains("nouveau solde") || labelLine.contains("new balance")) {
                stmt.setLoyaltyPointsNew(pts);
            }
        }
    }
}