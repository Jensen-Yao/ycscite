package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CNKI export parser (EndNote tag format): each record starts with "%0"; fields are
 * "%A author", "%T title", "%J source", "%D year", "%K keywords (;-separated)",
 * "%R doi", "%X abstract"; records separated by blank lines.
 */
public final class CnkiParser {

    public static List<Record> parseFile(Path file) throws IOException {
        String text = WoSParser.readText(file);
        List<Record> out = new ArrayList<>();
        Record cur = null;
        List<String> authors = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith("%0")) {
                if (cur != null) { cur.authors = new ArrayList<>(authors); out.add(cur); }
                cur = new Record();
                authors.clear();
                cur.pt = t.substring(2).trim();
            } else if (cur != null && t.startsWith("%")) {
                char tag = t.length() > 1 ? t.charAt(1) : ' ';
                String val = t.length() > 3 ? t.substring(3).trim() : t.substring(2).trim();
                switch (tag) {
                    case 'A' -> authors.add(val);
                    case 'T' -> cur.title = val;
                    case 'J' -> cur.source = val;
                    case 'D' -> { String y = val.replaceAll("[^0-9]", ""); if (y.length() >= 4) cur.year = Integer.parseInt(y.substring(0, 4)); }
                    case 'K' -> { for (String k : val.split(";\\s*")) if (!k.isBlank()) cur.keywords.add(k.trim()); }
                    case 'R' -> cur.doi = val;
                    case 'X' -> cur.abstractText = val;
                    default -> { }
                }
            }
        }
        if (cur != null) { cur.authors = new ArrayList<>(authors); cur.authorsFull = new ArrayList<>(authors); out.add(cur); }
        return out;
    }
}
