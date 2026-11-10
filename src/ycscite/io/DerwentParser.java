package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derwent Innovations Index (DWPI) 导入解析器：制表符分隔表格，
 * 首行为表头（PN/TI/AE/PA/AB/PY/IPC，或对应中文名），每行一条专利记录。
 * 发明人→作者，专利标题→题名，申请人→地址，IPC 分类号→关键词。
 */
public final class DerwentParser {

    private static final Map<String, Integer> ZH = Map.of(
            "专利号", 0, "标题", 1, "发明人", 2, "申请人", 3, "摘要", 4, "年份", 5, "分类号", 6);

    public static List<Record> parseFile(Path file) throws IOException {
        String text = WoSParser.readText(file);
        String[] lines = text.split("\r?\n", -1);
        List<Record> out = new ArrayList<>();
        Map<String, Integer> cols = null;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            if (cols == null) {
                cols = new HashMap<>();
                for (int i = 0; i < cells.length; i++) {
                    String h = cells[i].trim().toUpperCase();
                    Integer std = switch (h) {
                        case "PN", "专利号" -> 0;
                        case "TI", "标题" -> 1;
                        case "AE", "发明人" -> 2;
                        case "PA", "申请人" -> 3;
                        case "AB", "摘要" -> 4;
                        case "PY", "年份" -> 5;
                        case "IPC", "分类号" -> 6;
                        default -> null;
                    };
                    if (std != null) cols.put(stdName(std), i);
                }
                continue;
            }
            String pn = cell(cells, cols, 0), ti = cell(cells, cols, 1), ae = cell(cells, cols, 2),
                   pa = cell(cells, cols, 3), ab = cell(cells, cols, 4),
                   py = cell(cells, cols, 5), ipc = cell(cells, cols, 6);
            if (ti.isBlank() && pn.isBlank()) continue;
            Record r = new Record();
            r.pt = "P";
            r.ut = pn;
            r.title = ti;
            for (String a : ae.split(";\\s*")) if (!a.isBlank()) { r.authors.add(a.trim()); r.authorsFull.add(a.trim()); }
            if (!pa.isBlank()) r.addresses.add(pa);
            r.abstractText = ab;
            String y = py.replaceAll("[^0-9]", "");
            if (y.length() >= 4) r.year = Integer.parseInt(y.substring(0, 4));
            for (String k : ipc.split("[;,]\\s*")) if (!k.isBlank()) r.keywords.add(k.trim());
            out.add(r);
        }
        return out;
    }

    private static String stdName(int std) {
        return switch (std) { case 0 -> "PN"; case 1 -> "TI"; case 2 -> "AE"; case 3 -> "PA"; case 4 -> "AB"; case 5 -> "PY"; default -> "IPC"; };
    }

    private static String cell(String[] cells, Map<String, Integer> cols, int std) {
        Integer i = cols.get(stdName(std));
        return i != null && i < cells.length ? cells[i].trim() : "";
    }
}
