package ycscite.analysis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * GPT/LLM 聚类标注（原版 7.x "GPT Cluster Labeling"：gpt-4o/gpt-5-mini/deepseek/Ollama，8 语言）。
 * 纯 JDK HttpClient，可选启用：API key 取环境变量 YCSCITE_API_KEY 或 OPENAI_API_KEY；
 * Ollama 本地：--gpt-url http://localhost:11434/v1/chat/completions（无需 key）。
 * 失败安全：网络/key 缺失时静默跳过并返回空表。
 */
public final class GptLabeler {

    public static final class Options {
        public String url = "https://api.openai.com/v1/chat/completions";
        public String model = "gpt-4o-mini";
        public String apiKeyEnv = "YCSCITE_API_KEY";
        public String language = "中文";          // 原版 8 语言：EN/ZH/FR/DE/JP/PT/RU/ES
        public int maxClusters = 50;
        public int timeoutSec = 60;
    }

    /** 为每个聚类的 top-terms 请求 LLM 综述标签；返回 cluster → 标签文本。 */
    public static Map<Integer, String> label(Map<Integer, List<LLRLabeler.Scored>> clusterLabels, Options o) {
        Map<Integer, String> out = new TreeMap<>();
        String key = System.getenv(o.apiKeyEnv);
        if (key == null || key.isBlank()) key = System.getenv("OPENAI_API_KEY");
        boolean ollama = o.url.contains("localhost") || o.url.contains("127.0.0.1");
        if ((key == null || key.isBlank()) && !ollama) return out;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(o.timeoutSec)).build();
        int done = 0;
        for (Map.Entry<Integer, List<LLRLabeler.Scored>> en : clusterLabels.entrySet()) {
            if (done++ >= o.maxClusters) break;
            StringBuilder terms = new StringBuilder();
            for (int i = 0; i < Math.min(10, en.getValue().size()); i++)
                terms.append(en.getValue().get(i).term).append(i == Math.min(10, en.getValue().size()) - 1 ? "" : ", ");
            if (terms.isEmpty()) continue;
            String prompt = "You are a bibliometric analyst. Given the top terms of a cluster of publications, "
                    + "produce a concise thematic label (3-6 words, no quotes) in " + o.language
                    + ". Terms: " + terms;
            try {
                String body = "{\"model\":\"" + o.model + "\",\"messages\":[{\"role\":\"user\",\"content\":"
                        + jsonStr(prompt) + "}],\"max_tokens\":60,\"temperature\":0.2}";
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(o.url))
                        .timeout(Duration.ofSeconds(o.timeoutSec))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (key != null && !key.isBlank()) req.header("Authorization", "Bearer " + key);
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    String label = extractContent(resp.body());
                    if (!label.isBlank()) out.put(en.getKey(), label.trim());
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static void writeTsv(Map<Integer, String> labels, Path out) throws java.io.IOException {
        StringBuilder sb = new StringBuilder("cluster\tgpt_label\n");
        for (var en : labels.entrySet()) sb.append('#').append(en.getKey()).append('\t').append(en.getValue()).append('\n');
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    public static String extractContent(String json) {
        int i = json.indexOf("\"content\"");
        if (i < 0) return "";
        int s = json.indexOf('"', i + 9) + 1;
        StringBuilder out = new StringBuilder();
        for (int k = s; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) { out.append(json.charAt(k + 1)); k++; continue; }
            if (c == '"') break;
            out.append(c);
        }
        return out.toString().replace("\n", " ");
    }

    private static String jsonStr(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
