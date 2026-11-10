package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录级 TSV 往返（原版 "WoS ↔ TSV/CSV" 转换器，mF 5.x）：
 * 列 = PT/AU/AF/TI/SO/DE/ID/AB/C1/CR/PY/VL/BP/DI/UT/TC/WC（多值字段以 ';' 连接，制表符转义）。
 */
public final class RecordIo {

    static final String HEADER = "PT\tAU\tAF\tTI\tSO\tDE\tID\tAB\tC1\tCR\tPY\tVL\tBP\tDI\tUT\tTC\tWC";

    public static void writeTsv(Project p, Path out) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Record r : p.records)
            sb.append(j(r.pt)).append('\t').append(j(r.authors)).append('\t').append(j(r.authorsFull))
              .append('\t').append(j(r.title)).append('\t').append(j(r.source))
              .append('\t').append(j(r.keywords)).append('\t').append(j(r.keywordsPlus))
              .append('\t').append(j(r.abstractText)).append('\t').append(j(r.addresses))
              .append('\t').append(j(r.citedRefs)).append('\t').append(r.year)
              .append('\t').append(j(r.volume)).append('\t').append(j(r.beginPage))
              .append('\t').append(j(r.doi)).append('\t').append(j(r.ut)).append('\t').append(r.timesCited)
              .append('\t').append(j(r.categories)).append('\n');
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    public static Project readTsv(Path in) throws IOException {
        Project p = new Project();
        p.name = in.getFileName().toString().replaceAll("\\.[^.]+$", "");
        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] t = lines.get(i).split("\t", -1);
            Record r = new Record();
            if (t.length > 0) r.pt = u(t[0]);
            if (t.length > 1) addAll(r.authors, u(t[1]));
            if (t.length > 2) addAll(r.authorsFull, u(t[2]));
            if (t.length > 3) r.title = u(t[3]);
            if (t.length > 4) r.source = u(t[4]);
            if (t.length > 5) addAll(r.keywords, u(t[5]));
            if (t.length > 6) addAll(r.keywordsPlus, u(t[6]));
            if (t.length > 7) r.abstractText = u(t[7]);
            if (t.length > 8) addAll(r.addresses, u(t[8]));
            if (t.length > 9) addAll(r.citedRefs, u(t[9]));
            if (t.length > 10) try { r.year = Integer.parseInt(u(t[10]).replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) { }
            if (t.length > 11) r.volume = u(t[11]);
            if (t.length > 12) r.beginPage = u(t[12]);
            if (t.length > 13) r.doi = u(t[13]);
            if (t.length > 14) r.ut = u(t[14]);
            if (t.length > 15) try { r.timesCited = Integer.parseInt(u(t[15]).replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) { }
            if (t.length > 16) addAll(r.categories, u(t[16]));
            p.records.add(r);
        }
        return p;
    }

    private static String j(List<String> list) { return String.join(";", list); }
    private static String j(String s) { return s == null ? "" : s.replace("\t", " ").replace("\n", " ").replace("\r", ""); }
    private static String u(String s) { return s == null ? "" : s.replace("\\t", "\t").trim(); }

    private static void addAll(List<String> to, String joined) {
        if (joined == null || joined.isBlank()) return;
        for (String s : joined.split(";"))
            if (!s.isBlank()) to.add(s.trim());
    }
}
