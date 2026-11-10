package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ProQuest RIS 解析器（原版 "Convert RIS files from ProQuest to the Web of Science format"）：
 * 每条记录由 "TY  - ..." 开始，字段 "XX  - value"，空行分隔。
 */
public final class ProQuestRisParser {

    private static final Pattern TAG = Pattern.compile("^([A-Z][A-Z0-9])  - ?(.*)$");

    public static List<Record> parseFile(Path file) throws IOException {
        String text = WoSParser.readText(file);
        List<Record> out = new ArrayList<>();
        Record cur = null;
        List<String> authors = new ArrayList<>();
        List<String> kws = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            Matcher m = TAG.matcher(line);
            if (m.matches()) {
                String tag = m.group(1), val = m.group(2).trim();
                if (tag.equals("TY")) {
                    if (cur != null) { finish(cur, authors, kws); out.add(cur); }
                    cur = new Record();
                    authors = new ArrayList<>();
                    kws = new ArrayList<>();
                    cur.pt = val;
                } else if (cur != null) {
                    switch (tag) {
                        case "AU", "A1" -> authors.add(val);
                        case "TI", "T1" -> cur.title = val;
                        case "JO", "JF", "T2" -> cur.source = val;
                        case "PY", "Y1" -> { String y = val.replaceAll("[^0-9]", ""); if (y.length() >= 4) cur.year = Integer.parseInt(y.substring(0, 4)); }
                        case "KW" -> kws.add(val);
                        case "AB", "N2" -> cur.abstractText = val;
                        case "DO" -> cur.doi = val;
                        case "AN" -> cur.ut = val;
                        default -> { }
                    }
                }
            }
        }
        if (cur != null) { finish(cur, authors, kws); out.add(cur); }
        return out;
    }

    private static void finish(Record cur, List<String> authors, List<String> kws) {
        cur.authors = new ArrayList<>(authors);
        cur.authorsFull = new ArrayList<>(authors);
        cur.keywords = new ArrayList<>(kws);
    }
}
