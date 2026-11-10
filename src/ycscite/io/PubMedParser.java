package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PubMed MEDLINE/abstract format parser. Records start at "PMID- " lines; field
 * lines are "TAG- value"; continuation lines are indented. Mesh headings (MH)
 * and other terms (OT) become keywords, matching CiteSpace's PubMed handling.
 */
public final class PubMedParser {

    private static final Pattern TAG = Pattern.compile("^([A-Z]{2,4})\\s*- ?(.*)$");

    public static List<Record> parseFile(Path file) throws IOException {
        String text = WoSParser.readText(file);
        List<Record> out = new ArrayList<>();
        Record cur = null;
        String curTag = null;
        StringBuilder curVal = new StringBuilder();

        for (String line : text.split("\r?\n", -1)) {
            Matcher m = TAG.matcher(line);
            if (m.matches() && !line.startsWith(" ")) {
                if (cur != null && curTag != null) flush(cur, curTag, curVal.toString());
                String tag = m.group(1), val = m.group(2).trim();
                if (tag.equals("PMID")) {
                    if (cur != null) out.add(cur);
                    cur = new Record();
                    cur.ut = val;
                }
                if (cur == null) continue;
                curTag = tag;
                curVal = new StringBuilder(val);
            } else if (cur != null && curTag != null && !line.trim().isEmpty()) {
                curVal.append(' ').append(line.trim());
            }
        }
        if (cur != null && curTag != null) flush(cur, curTag, curVal.toString());
        if (cur != null) out.add(cur);
        return out;
    }

    private static void flush(Record r, String tag, String v) {
        switch (tag) {
            case "TI" -> { v = v.replaceAll("\\s*\\.$", ""); r.title = v; }
            case "AB" -> r.abstractText = v;
            case "FAU" -> r.authorsFull.add(v);
            case "AU" -> r.authors.add(v);
            case "MH", "OT" -> r.keywords.add(v);
            case "TA" -> r.source = v;
            case "DP" -> { String y = v.replaceAll("[^0-9]", ""); if (y.length() >= 4) r.year = Integer.parseInt(y.substring(0, 4)); }
            case "LID", "AID" -> { if (v.endsWith(" [doi]")) r.doi = v.substring(0, v.length() - 6).trim(); }
            default -> { }
        }
    }
}
