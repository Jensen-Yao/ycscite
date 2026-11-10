package ycscite.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 旧格式→WoS 转换器（原版 CS:837-843 DialogWeb/ISI-XML/PILOT→WoS 的简化实现）。 */
public final class FormatConverter {

    /** 通用 CSV/制表符→WoS 转换：识别首行列名，映射到 WoS 标签。 */
    public static void csvToWos(Path in, Path out) throws IOException {
        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        if (lines.size() < 2) return;
        String[] headers = lines.get(0).split("[\t,;]");
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < headers.length; i++) col.put(headers[i].trim().toLowerCase(), i);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            String[] v = lines.get(i).split("[\t,;]", -1);
            java.util.function.Function<String, String> get = k -> {
                Integer c = col.get(k);
                return c != null && c < v.length ? v[c].trim() : "";
            };
            sb.append("PT  - J\n");
            String au = get.apply("authors"); if (!au.isEmpty()) sb.append("AU  - ").append(au.replace(";", "\nAU  - ")).append('\n');
            String ti = get.apply("title"); if (!ti.isEmpty()) sb.append("TI  - ").append(ti).append('\n');
            String so = get.apply("source"); if (!so.isEmpty()) sb.append("SO  - ").append(so).append('\n');
            String py = get.apply("year"); if (!py.isEmpty()) sb.append("PY  - ").append(py).append('\n');
            String ab = get.apply("abstract"); if (!ab.isEmpty()) sb.append("AB  - ").append(ab).append('\n');
            String de = get.apply("keywords"); if (!de.isEmpty()) sb.append("DE  - ").append(de).append('\n');
            sb.append("ER  - \n\n");
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** ISI-XML→WoS：提取 XML 标签值写入 WoS 行（正则流式近似）。 */
    public static void xmlToWos(Path in, Path out) throws IOException {
        String xml = Files.readString(in, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        var recs = xml.split("<record[ >]");
        for (int i = 1; i < recs.length; i++) {
            String r = recs[i];
            sb.append("PT  - J\n");
            appendTag(sb, r, "title", "TI");
            appendTag(sb, r, "author", "AU");
            appendTag(sb, r, "journal", "SO");
            appendTag(sb, r, "year", "PY");
            appendTag(sb, r, "abstract", "AB");
            appendTag(sb, r, "keyword", "DE");
            sb.append("ER  - \n\n");
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendTag(StringBuilder sb, String xml, String tag, String wosTag) {
        var m = java.util.regex.Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(xml);
        if (m.find()) sb.append(wosTag).append("  - ").append(m.group(1).trim()).append('\n');
    }
}
