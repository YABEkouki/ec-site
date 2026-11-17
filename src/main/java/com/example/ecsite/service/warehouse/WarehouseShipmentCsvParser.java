package com.example.ecsite.service.warehouse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature132-B: reads the warehouse "shipped" CSV. Pure: no database, clock or Spring.
 *
 * Format: UTF-8 (BOM optional), CRLF or LF, header {@link #HEADER}, then one row per shipped order with the
 * five columns バッチID,注文番号,出荷日,配送業者,追跡番号. Fields may be quoted ("" escapes a quote); a quoted
 * field cannot span lines. Blank lines are ignored. A file that is not valid UTF-8, has another header, has
 * no data row or more than {@link #MAX_ROWS} rows is refused as a whole; a malformed row is returned with
 * {@code fields == null} so it can be recorded as rejected.
 */
public final class WarehouseShipmentCsvParser {

    public static final String HEADER = "バッチID,注文番号,出荷日,配送業者,追跡番号";
    public static final int COLUMNS = 5;
    public static final int MAX_ROWS = 1000;
    public static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_LINE_TEXT = 1000;

    /** {@code lineNo} is the physical line number (the header is line 1). */
    public record Row(int lineNo, String lineText, List<String> fields) {}

    public static class InvalidFileException extends RuntimeException {
        public InvalidFileException(String message) { super(message); }
    }

    private WarehouseShipmentCsvParser() {
    }

    public static List<Row> parse(byte[] content) {
        if (content == null || content.length == 0) throw new InvalidFileException("ファイルが空です。");
        if (content.length > MAX_BYTES) throw new InvalidFileException("ファイルが大きすぎます（上限 1MB）。");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidFileException("文字コードが UTF-8 ではありません。");
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\r\n|\n|\r", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0].strip())) {
            throw new InvalidFileException("1 行目（見出し）が「" + HEADER + "」ではありません。");
        }
        var rows = new ArrayList<Row>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            if (rows.size() == MAX_ROWS) throw new InvalidFileException("データ行が多すぎます（上限 " + MAX_ROWS + " 行）。");
            String shown = line.length() > MAX_LINE_TEXT ? line.substring(0, MAX_LINE_TEXT) : line;
            var fields = split(line);
            rows.add(new Row(i + 1, shown, fields == null || fields.size() != COLUMNS ? null : fields));
        }
        if (rows.isEmpty()) throw new InvalidFileException("データ行がありません。");
        return rows;
    }

    /** RFC 4180 fields of one line, each stripped; null when the quoting is broken. */
    static List<String> split(String line) {
        var fields = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false, wasQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == ',') {
                fields.add(wasQuoted ? field.toString() : field.toString().strip());
                field.setLength(0); wasQuoted = false;
            } else if (c == '"') {
                if (!field.toString().isBlank() || wasQuoted) return null;
                field.setLength(0); quoted = true; wasQuoted = true;
            } else if (wasQuoted) {
                if (!Character.isWhitespace(c)) return null;
            } else field.append(c);
        }
        if (quoted) return null;
        fields.add(wasQuoted ? field.toString() : field.toString().strip());
        return fields;
    }
}
