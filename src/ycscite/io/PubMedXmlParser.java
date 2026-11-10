package ycscite.io;

import ycscite.model.Record;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/** PubMed XML (eutils/medline 格式) 解析器（新增数据格式）。 */
public final class PubMedXmlParser {

    public static List<Record> parseFile(Path file) throws IOException {
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        List<Record> records = new ArrayList<>();
        // 按 <PubmedArticle> 分割
        String[] articles = xml.split("<PubmedArticle>");
        for (int i = 1; i < articles.length; i++) {
            String art = articles[i];
            Record r = new Record();
            r.pt = "J";
            r.title = tag(art, "ArticleTitle");
            r.abstractText = tag(art, "AbstractText");
            String py = tag(art, "PubDate", "Year");
            if (py.isEmpty()) py = tag(art, "ArticleDate", "Year");
            if (!py.isEmpty()) try { r.year = Integer.parseInt(py); } catch (NumberFormatException ignored) { }
            // 作者
            Matcher am = Pattern.compile("<Author[^>]*>.*?<LastName>([^<]+)</LastName>.*?<ForeName>([^<]+)</ForeName>", Pattern.DOTALL).matcher(art);
            while (am.find()) r.authors.add(am.group(2) + " " + am.group(1));
            // 期刊
            r.source = tag(art, "Journal", "Title");
            r.volume = tag(art, "JournalIssue", "Volume");
            String doi = tag(art, "ArticleId", "pubmed");
            if (!doi.isEmpty()) r.doi = doi;
            // 关键词
            Matcher km = Pattern.compile("<Keyword[^>]*>([^<]+)</Keyword>").matcher(art);
            while (km.find()) r.keywords.add(km.group(1).trim());
            if (!r.title.isBlank()) records.add(r);
        }
        return records;
    }

    private static String tag(String xml, String... path) {
        // 简化 XML 嵌套标签提取：第一个 path[0] > 第二个 path[1] 的文本
        if (path.length == 1) {
            Matcher m = Pattern.compile("<" + path[0] + "[^>]*>([^<]*)</" + path[0] + ">").matcher(xml);
            return m.find() ? m.group(1).trim() : "";
        }
        // 嵌套：先找外层，再在内层找
        Matcher outer = Pattern.compile("<" + path[0] + "[^>]*>(.*?)</" + path[0] + ">", Pattern.DOTALL).matcher(xml);
        if (!outer.find()) return "";
        return tag(outer.group(1), path.length > 2 ? Arrays.copyOfRange(path, 1, path.length) : new String[]{path[1]});
    }
}
