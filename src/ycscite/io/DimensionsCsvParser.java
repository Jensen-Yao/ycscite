package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dimensions CSV 解析器（原版 "Data: Import/Export: Dimensions: Convert exported data in CSV"）。
 * 宽松匹配表头：Publication year / Title / Authors / Authors full name / Journal title /
 * DOI / Abstract / Keywords / Fields of Research。
 */
public final class DimensionsCsvParser {

    public static List<Record> parseFile(Path file) throws IOException {
        List<List<String>> rows = ScopusCsvParser.parseCsv(WoSParser.readText(file));
        List<Record> out = new ArrayList<>();
        if (rows.size() < 2) return out;
        List<String> header = rows.get(0);
        int iYear = idx(header, "publication year", "year"),
                iTitle = idx(header, "title"),
                iAuthors = idx(header, "authors", "authors full name", "author(s)"),
                iJournal = idx(header, "journal title", "source title", "journal"),
                iDoi = idx(header, "doi"),
                iAbstract = idx(header, "abstract"),
                iKeywords = idx(header, "keywords", "fields of research");
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String title = get(row, iTitle);
            if (title.isBlank()) continue;
            Record rec = new Record();
            rec.pt = "J";
            rec.title = title;
            String authors = get(row, iAuthors);
            for (String a : authors.split(";\\s*"))
                if (!a.isBlank()) { rec.authors.add(a.trim()); rec.authorsFull.add(a.trim()); }
            rec.source = get(row, iJournal);
            try {
                String y = get(row, iYear).replaceAll("[^0-9]", "");
                if (y.length() >= 4) rec.year = Integer.parseInt(y.substring(0, 4));
            } catch (NumberFormatException ignored) { }
            rec.doi = get(row, iDoi);
            rec.abstractText = get(row, iAbstract);
            for (String k : get(row, iKeywords).split(";\\s*"))
                if (!k.isBlank()) rec.keywords.add(k.trim());
            out.add(rec);
        }
        return out;
    }

    private static int idx(List<String> header, String... names) {
        for (String name : names)
            for (int i = 0; i < header.size(); i++)
                if (header.get(i).toLowerCase(Locale.ROOT).trim().equals(name)) return i;
        for (String name : names)
            for (int i = 0; i < header.size(); i++)
                if (header.get(i).toLowerCase(Locale.ROOT).contains(name)) return i;
        return -1;
    }

    private static String get(List<String> row, int i) { return i >= 0 && i < row.size() ? row.get(i) : ""; }
}
