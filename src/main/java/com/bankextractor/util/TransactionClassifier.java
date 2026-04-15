package com.bankextractor.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransactionClassifier {

    private static final Map<String, List<String>> CATEGORY_KEYWORDS = new LinkedHashMap<>() {{
        // ── Income / Credits ──────────────────────────────────────────────────
        put("Salary",        List.of("salary", "payroll", "wages", "salaire", "paie",
                                     "virement net", "vir net", "net a payer"));
        put("Transfer In",   List.of("vir recu", "virement reçu", "virement recu",
                                     "transfer in", "remise", "versement", "depot"));

        // ── Cash ──────────────────────────────────────────────────────────────
        put("ATM",           List.of("retrait", "retrait dab", "dab", "atm", "cash",
                                     "withdrawal", "guichet"));

        // ── Card Payments ─────────────────────────────────────────────────────
        put("Groceries",     List.of("carrefour", "carrefourmarket", "monoprix", "leclerc",
                                     "intermarché", "intermarche", "auchan", "lidl", "aldi",
                                     "supermarket", "grocery", "market", "aziza", "geant"));
        put("Dining",        List.of("restaurant", "café", "cafe", "grenier gourmand",
                                     "mac donald", "mcdonald", "kentucky fried", "kfc",
                                     "pizza", "burger", "brasserie", "bistro", "traiteur",
                                     "crep", "patisserie"));
        put("Fuel",          List.of("fuel", "petrol", "essence", "total", "shell",
                                     "bp ", "station service", "autoroute", "aprr", "vinci"));
        put("Healthcare",    List.of("pharmacy", "pharmacie", "pharma", "hospital", "clinic",
                                     "docteur", "doctor", "medical", "medecin", "cnss",
                                     "mutuelle", "sante", "optique"));
        put("Shopping",      List.of("amazon", "ebay", "douglas", "camille albane",
                                     "zara", "h&m", "fnac", "darty", "boulanger",
                                     "shop", "boutique", "achat", "purchase"));
        put("Travel",        List.of("flight", "hotel", "airline", "sncf", "ratp",
                                     "booking", "airbnb", "taxi", "uber", "train",
                                     "autoroute", "aprr", "parking"));
        put("Entertainment", List.of("netflix", "spotify", "deezer", "gaming", "cinema",
                                     "theatre", "concert", "ticket", "apple", "google",
                                     "amazon prime"));

        // ── Bills / Fixed Charges ─────────────────────────────────────────────
        put("Utilities",     List.of("edf", "gdf", "engie", "electricity", "electricite",
                                     "gaz", "eau", "water", "sonede", "steg",
                                     "internet", "telecom", "orange", "sfr", "bouygues",
                                     "free", "phone", "mobile", "ooredoo", "messalia"));
        put("Rent",          List.of("rent", "loyer", "lease", "apartment", "logement"));
        put("Insurance",     List.of("insurance", "assurance", "mutuelle", "prevoyance",
                                     "star assurance", "axa", "mma", "maif", "macif"));
        put("Bank Fees",     List.of("cotisation", "frais", "commission", "agios",
                                     "interest", "interet", "fee", "charge",
                                     "cotisation carte", "cotisation jazz", "abonnement mensuel"));
        put("Education",     List.of("school", "ecole", "university", "tuition",
                                     "formation", "cours", "scolarite"));
        put("Government",    List.of("impot", "tax", "douane", "tva", "cnss", "cnrps",
                                     "tresor", "finances publiques", "prelevement"));

        // ── Direct Debits ─────────────────────────────────────────────────────
        put("Direct Debit",  List.of("prelevement", "prélévement", "prelev"));

        // ── Cheques ───────────────────────────────────────────────────────────
        put("Cheque",        List.of("cheque", "chèque", "chq"));
    }};

    public static String classify(String description) {
        if (description == null || description.isBlank()) return "Other";
        String lower = description.toLowerCase();
        for (Map.Entry<String, List<String>> entry : CATEGORY_KEYWORDS.entrySet())
            for (String keyword : entry.getValue())
                if (lower.contains(keyword.toLowerCase())) return entry.getKey();
        return "Other";
    }

    public static void addCategory(String category, List<String> keywords) {
        CATEGORY_KEYWORDS.put(category, keywords);
    }
}