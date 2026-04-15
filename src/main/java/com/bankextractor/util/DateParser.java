package com.bankextractor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateParser {

    private static final Logger log = LoggerFactory.getLogger(DateParser.class);

    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("\\b(\\d{1,2}[/\\-\\.](\\d{1,2}|[A-Za-z]{3})[/\\-\\.]\\d{2,4})\\b"),
            Pattern.compile("\\b(\\d{4}[/\\-\\.]\\d{1,2}[/\\-\\.]\\d{1,2})\\b"),
            Pattern.compile("\\b(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})\\b"),
            Pattern.compile("\\b([A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})\\b")
    );

    private final List<DateTimeFormatter> formatters;

    public DateParser(List<String> dateFormats) {
        this.formatters = dateFormats.stream()
                .map(fmt -> DateTimeFormatter.ofPattern(fmt, Locale.ENGLISH))
                .toList();
    }

    public LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ").replaceAll("[.,]$", "");
        for (DateTimeFormatter fmt : formatters) {
            try { return LocalDate.parse(cleaned, fmt); }
            catch (DateTimeParseException ignored) {}
        }
        log.debug("Could not parse date: '{}'", raw);
        return null;
    }

    public LocalDate extractFirstDate(String line) {
        if (line == null) return null;
        for (Pattern pattern : DATE_PATTERNS) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                LocalDate d = parse(m.group(1));
                if (d != null) return d;
            }
        }
        return null;
    }

    public List<LocalDate> extractAllDates(String line) {
        if (line == null) return List.of();
        List<LocalDate> found = new ArrayList<>();
        for (Pattern p : DATE_PATTERNS) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                LocalDate d = parse(m.group(1));
                if (d != null && !found.contains(d)) found.add(d);
            }
        }
        return found;
    }

    public boolean looksLikeDate(String token) {
        return extractFirstDate(token) != null;
    }
}