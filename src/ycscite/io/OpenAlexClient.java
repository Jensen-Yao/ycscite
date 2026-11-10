package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** OpenAlex API 客户端（新增在线数据源，超越原版）。 */
public final class OpenAlexClient {

    public static List<Record> search(String query, int limit) throws Exception {
        String url = "https://api.openalex.org/works?search=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&per-page=" + Math.min(limit, 200) + "&mailto=ycscite@example.com";
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) return List.of();
        return parseWorks(resp.body());
    }

    static List<Record> parseWorks(String json) {
        List<Record> records = new ArrayList<>();
        // 简化 JSON 解析（零依赖）：按 "id":"..." 分割结果条目
        String marker = new String(new char[]{123,34,105,100,34,58}); // {"id":
        String[] works = json.split(java.util.regex.Pattern.quote(marker));
            Record r = new Record();
        for (int i = 1; i < works.length; i++) {
            r.pt = "J";
            r.title = extract(works[i], "title");
            String py = extract(works[i], "publication_year");
            if (!py.isEmpty()) try { r.year = Integer.parseInt(py); } catch (NumberFormatException ignored) { }
            r.abstractText = extract(works[i], "abstract_inverted_index");  // 需要重建，此处取原始
            r.doi = extract(works[i], "doi");
            // 期刊
            r.source = extract(works[i], "display_name");
            // 关键词
            for (String k : extract(works[i], "keywords").split(","))
                if (k.length() > 2 && k.length() < 50) r.keywords.add(k.trim());
            if (!r.title.isBlank()) records.add(r);
        }
        return records;
    }

    private static String extract(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return "";
        int start = idx + key.length() + 3;
        char open = json.charAt(start);
        if (open == '"') {
            int end = json.indexOf('"', start + 1);
            return end > start ? json.substring(start + 1, end) : "";
        }
        // 非字符串值（数字/对象）
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}')
            end++;
        return json.substring(start, end).trim();
    }
}
