package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** BibTeX 导入解析器（新增数据格式，超越原版）。 */
public final class BibTeXParser {

    public static Project parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Project p = new Project();
        p.name = file.getFileName().toString().split("[.]")[0];
        String[] parts = text.split("@");
        for (String part : parts) {
            if (part.length() < 10) continue;
            String type = part.split("[{]")[0].trim().toLowerCase();
            if (type.equals("comment") || type.equals("preamble") || type.equals("string")) continue;
            Record r = new Record();
            r.pt = type.equals("article") ? "J" : type.equals("inproceedings") ? "C" : type.toUpperCase();
            parseFields(part, r);
            if (!r.title.isBlank()) p.records.add(r);
        }
        return p;
    }

    private static void parseFields(String body, Record r) {
        String[] lines = body.split(",");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 1) continue;
            String field = line.substring(0, eq).trim().toLowerCase();
            String value = line.substring(eq + 1).trim();
            value = value.replaceAll("[{}\"\n\r]", "").trim();
            if (value.length() < 1) continue;
            switch (field) {
                case "author" -> {
                    for (String a : value.split(" and "))
                        if (!a.isBlank()) r.authors.add(a.trim());
                }
                case "title" -> r.title = value;
                case "journal" -> r.source = value;
                case "year" -> { try { r.year = Integer.parseInt(value.replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) { } }
                case "abstract" -> r.abstractText = value;
                case "keywords", "keyword" -> {
                    for (String k : value.split("[;,]"))
                        if (!k.isBlank()) r.keywords.add(k.trim());
                }
                case "doi" -> r.doi = value;
                case "volume" -> r.volume = value;
                case "pages" -> {
                    int sp = value.indexOf('-');
                    r.beginPage = sp > 0 ? value.substring(0, sp).trim() : value;
                }
            }
        }
    }
}
