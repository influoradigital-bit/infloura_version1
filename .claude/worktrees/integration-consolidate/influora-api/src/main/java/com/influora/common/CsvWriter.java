package com.influora.common;

import java.io.StringWriter;
import java.util.List;

/**
 * Tiny in-house RFC-4180 CSV writer — A4 Report Export (E2). No new dependency (opencsv
 * intentionally not added, see wiki/tech/approved-deps.md discipline / A4-REPORT-EXPORT-WORKFLOW.md
 * §2). Quotes a field whenever it contains a comma, quote, or newline; doubles embedded quotes.
 */
public final class CsvWriter {

    private CsvWriter() {}

    public static String write(List<String> headers, List<List<String>> rows) {
        StringWriter out = new StringWriter();
        writeRow(out, headers);
        for (List<String> row : rows) {
            writeRow(out, row);
        }
        return out.toString();
    }

    private static void writeRow(StringWriter out, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.write(",");
            }
            out.write(quoteIfNeeded(fields.get(i)));
        }
        out.write("\r\n");
    }

    private static String quoteIfNeeded(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
