package com.linguabridge.memory;

import java.util.ArrayList;
import java.util.List;

/** Small RFC 4180-style reader, also used for tab-separated files. */
final class CsvWordbookParser {
    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            if (rows.size() > WordbookImporter.MAX_ITEMS + 1) throw new IllegalArgumentException("词条超过 100000 条，请拆分文件。");
            if (row.size() > 128 || field.length() > 16384) throw new IllegalArgumentException("字段或列数过大，请检查分隔符与文件格式。");
            char ch = text.charAt(i);
            if (ch == '#' && !quoted && !closed && row.isEmpty() && field.toString().trim().isEmpty()) {
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                field.setLength(0);
                continue;
            }
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else field.append(ch);
            } else if (ch == delimiter || ch == '\r' || ch == '\n') {
                row.add(field.toString().trim()); field.setLength(0); closed = false;
                if (ch != delimiter) {
                    if (row.stream().anyMatch(value -> !value.isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else if (ch == '"' && field.toString().trim().isEmpty() && !closed) {
                field.setLength(0); quoted = true;
            } else {
                if (ch == '"' || (closed && !Character.isWhitespace(ch))) throw new IllegalArgumentException("CSV 引号格式错误，请检查双引号是否成对，内部引号应写成两个双引号。");
                field.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV 引号未闭合，请检查文件格式。");
        row.add(field.toString().trim());
        if (row.stream().anyMatch(value -> !value.isEmpty())) rows.add(row);
        return rows;
    }
}
