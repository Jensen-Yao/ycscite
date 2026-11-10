package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Web of Science "Plain Text" export parser.
 * Records are delimited by ER lines; each field line starts with a two-letter tag
 * followed by a space; continuation lines are indented. Handles UTF-8/UTF-16 BOMs.
 */
public final class WoSParser {

    private static final java.util.regex.Pattern FIELD = java.util.regex.Pattern.compile("^([A-Z0-9]{2})(?: (.*))?$");

    public static Project parse(List<Path> files) throws IOException {
        Project p = new Project();
        for (Path f : files) p.addAll(parseFile(f));
        if (p.name.equals("untitled") && !files.isEmpty())
            p.name = files.get(0).getFileName().toString().replaceAll("\\.[^.]+$", "");
        return p;
    }

    public static List<Record> parseFile(Path file) throws IOException {
        String text = readText(file);
        List<Record> out = new ArrayList<>();
        Record cur = null;
        String curTag = null;
        List<String> curSegs = new ArrayList<>();

        for (String rawLine : text.split("\r?\n", -1)) {
            String line = rawLine;
            java.util.regex.Matcher m = FIELD.matcher(line);
            boolean isField = m.matches() && !line.startsWith("  ");
            if (isField) {
                if (cur != null && curTag != null) flush(cur, curTag, curSegs);
                String tag = m.group(1);
                String val = m.group(2) == null ? "" : m.group(2).trim();
                if (tag.equals("ER")) {
                    if (cur != null) out.add(cur);
                    cur = null; curTag = null;
                    continue;
                }
                if (tag.equals("PT") && cur != null) out.add(cur); // tolerate missing ER
                if (tag.equals("PT")) cur = new Record();
                if (cur == null) { cur = new Record(); } // tolerate missing PT
                curTag = tag;
                curSegs = new ArrayList<>();
                if (!val.isEmpty()) curSegs.add(val);
            } else if (cur != null && curTag != null && !line.trim().isEmpty()) {
                // CR continuation lines are one reference per line; other tags accumulate text
                if (curTag.equals("CR") || line.startsWith(" ")) curSegs.add(line.trim());
                else if (!curSegs.isEmpty()) curSegs.set(curSegs.size() - 1, curSegs.get(curSegs.size() - 1) + " " + line.trim());
                else curSegs.add(line.trim());
            }
        }
        if (cur != null && curTag != null) flush(cur, curTag, curSegs);
        if (cur != null) out.add(cur);
        return out;
    }

    private static void flush(Record r, String tag, List<String> segs) {
        String joined = String.join(" ", segs).trim();
        switch (tag) {
            case "PT": r.pt = joined; break;
            case "AU": addAllSplit(r.authors, segs); break;
            case "AF": addAllSplit(r.authorsFull, segs); break;
            case "TI": r.title = joined; break;
            case "SO": r.source = joined; break;
            case "DE": addAllSplit(r.keywords, segs); break;
            case "ID": addAllSplit(r.keywordsPlus, segs); break;
            case "AB": r.abstractText = joined; break;
            case "WC": addAllSplit(r.categories, segs); break;
            case "C1": for (String s : segs) for (String a : s.split(";\\s*")) if (!a.trim().isEmpty()) r.addresses.add(a.trim()); break;
            case "CR": for (String s : segs) if (!s.isEmpty()) r.citedRefs.add(s); break;
            case "PY": r.year = parseIntSafe(joined.replaceAll("[^0-9]", "")); break;
            case "VL": r.volume = joined; break;
            case "BP": r.beginPage = joined; break;
            case "DI": r.doi = joined; break;
            case "UT": r.ut = joined; break;
            case "TC": r.timesCited = parseIntSafe(joined); break;
            default: break;
        }
    }

    private static void addAllSplit(List<String> into, List<String> segs) {
        for (String s : segs)
            for (String a : s.split(";\\s*"))
                if (!a.trim().isEmpty()) into.add(a.trim());
    }

    private static int parseIntSafe(String s) {
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Charset cs = StandardCharsets.UTF_8;
        int skip = 0;
        if (bytes.length >= 2) {
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) { cs = StandardCharsets.UTF_16LE; skip = 2; }
            else if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) { cs = StandardCharsets.UTF_16BE; skip = 2; }
            else if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) { skip = 3; }
        }
        if(skip>0)return new String(bytes,skip,bytes.length-skip,cs);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch(java.nio.charset.CharacterCodingException invalidUtf8) {
            try {
                return Charset.forName("GB18030").newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch(java.nio.charset.CharacterCodingException invalidChinese) {
                return new String(bytes,Charset.forName("windows-1252"));
            }
        }
    }
}
