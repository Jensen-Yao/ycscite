package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Minimal Scopus CSV export parser (Authors;Title;Year;Source title;Author Keywords;Index Keywords;References). */
public final class ScopusCsvParser {

    public static List<Record> parseFile(Path file) throws IOException {
        String text = WoSParser.readText(file);
        List<List<String>> rows = parseCsv(text);
        List<Record> out = new ArrayList<>();
        if (rows.size() < 2) return out;
        List<String> header = rows.get(0);
        int iAuthors = idx(header, "Authors"), iTitle = idx(header, "Title"), iYear = idx(header, "Year"),
            iSource = idx(header, "Source title"), iAkw = idx(header, "Author Keywords"),
            iIkw = idx(header, "Index Keywords"), iRef = idx(header, "References"), iDoi = idx(header, "DOI");
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.isEmpty() || get(row, iTitle).isEmpty()) continue;
            Record rec = new Record();
            rec.pt = "J";
            for (String a : get(row, iAuthors).split(";\\s*")) if (!a.isBlank()) rec.authors.add(a.trim());
            rec.authorsFull = new ArrayList<>(rec.authors);
            rec.title = get(row, iTitle);
            rec.source = get(row, iSource);
            try { rec.year = Integer.parseInt(get(row, iYear).replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) { }
            for (String k : get(row, iAkw).split(";\\s*")) if (!k.isBlank()) rec.keywords.add(k.trim());
            for (String k : get(row, iIkw).split(";\\s*")) if (!k.isBlank()) rec.keywordsPlus.add(k.trim());
            for (String c : get(row, iRef).split(";\\s+")) if (!c.isBlank()) rec.citedRefs.add(c.trim());
            rec.doi = get(row, iDoi);
            out.add(rec);
        }
        return out;
    }

    private static int idx(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static String get(List<String> row, int i) { return i >= 0 && i < row.size() ? row.get(i) : ""; }

    /** Small quoted-CSV reader. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else inQ = false;
                } else cell.append(c);
            } else if (c == '"') inQ = true;
            else if (c == ',') { cur.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n') { cur.add(cell.toString()); cell.setLength(0); rows.add(cur); cur = new ArrayList<>(); }
            else if (c != '\r') cell.append(c);
        }
        if (cell.length() > 0 || !cur.isEmpty()) { cur.add(cell.toString()); rows.add(cur); }
        // drop empty trailing rows
        rows.removeIf(r -> r.stream().allMatch(String::isBlank));
        return rows;
    }
}
