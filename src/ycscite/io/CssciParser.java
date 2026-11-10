package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSSCI 导入解析器（RefWorks / NoteExpress 导出格式）：每条记录以 "RT" 行开始，
 * 字段行为 "XX value"（两位标签 + 空格 + 值），支持 A1/T1/JF/YR/K1/AB/DO 等标签。
 */
public final class CssciParser {

    public static List<Record> parseFile(Path file) throws IOException {
        String text = WoSParser.readText(file);
        boolean cnkiRefWorks = text.lines().limit(12).anyMatch(l -> l.startsWith("SR "));
        List<Record> out = new ArrayList<>();
        Record cur = null;
        List<String> authors = new ArrayList<>();
        for (String raw : text.split("\r?\n", -1)) {
            String line = raw;
            if (line.length() >= 2 && !line.startsWith(" ") && Character.isLetter(line.charAt(0))
                    && Character.isLetterOrDigit(line.charAt(1))
                    && (line.length() == 2 || line.charAt(2) == ' ')) {
                String tag = line.substring(0, 2);
                String val = line.length() > 3 ? line.substring(3).trim() : "";
                if (tag.equals("RT")) {
                    if (cur != null) { cur.authors = new ArrayList<>(authors); out.add(cur); }
                    cur = new Record();
                    authors.clear();
                    cur.pt = val;
                } else if (cur != null) {
                    switch (tag) {
                        case "A1", "AU", "FA" -> {
                            String names = cnkiRefWorks && tag.equals("A1")
                                    ? val.replace(',', ';').replace(".", "") : val;
                            for (String a : names.split(";\\s*"))
                                if (!a.isBlank()) authors.add(cnkiRefWorks && tag.equals("A1")
                                        ? refWorksAuthor(a.trim()) : a.trim());
                        }
                        case "T1", "TI" -> cur.title = val;
                        case "JF", "JO", "T2" -> cur.source = val;
                        case "YR", "PY", "DA" -> {
                            String y = val.replaceAll("[^0-9]", "");
                            if (y.length() >= 4) cur.year = Integer.parseInt(y.substring(0, 4));
                        }
                        case "K1", "KW" -> { for (String k : val.split(";\\s*")) if (!k.isBlank()) cur.keywords.add(k.trim()); }
                        case "AB", "N2" -> cur.abstractText = val;
                        case "DO" -> cur.doi = val;
                        case "AD", "PP" -> { for (String a : val.split(";\\s*")) if (!a.isBlank()) cur.addresses.add(a.trim()); }
                        case "VO", "vo" -> cur.volume = val;
                        case "OP" -> cur.beginPage = val;
                        default -> { }
                    }
                }
            }
        }
        if (cur != null) { cur.authors = new ArrayList<>(authors); cur.authorsFull = new ArrayList<>(authors); out.add(cur); }
        return out;
    }

    /** CNKI RefWorks converter convention: western names are written surname, given name. */
    private static String refWorksAuthor(String name) {
        String[] parts = name.split("[·\\s+]+");
        if (parts.length < 2) return name;
        if (parts.length == 2 && parts[1].length() == 1) return parts[0] + ", " + parts[1];
        return parts[parts.length - 1] + ", " + String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
    }
}
