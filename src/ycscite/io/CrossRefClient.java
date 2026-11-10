package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CrossRef REST search (the "search and download from CrossRef" service CiteSpace ships),
 * mapped into our Record model. Uses the public api.crossref.org endpoint, no key required.
 */
public final class CrossRefClient {

    public static final String ENDPOINT = "https://api.crossref.org/works";

    /** Live search; throws IOException on network failure. */
    public static List<Record> search(String query, int rows) throws IOException, InterruptedException {
        String url = ENDPOINT + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&rows=" + Math.min(Math.max(rows, 1), 200)
                + "&select=DOI,title,author,issued,container-title,subject";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ycscite/1.0 (mailto:clone@example.org)")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("CrossRef HTTP " + resp.statusCode());
        return parseResponse(resp.body());
    }

    /** Parse a CrossRef /works?select=... response (public for tests). */
    public static List<Record> parseResponse(String json) {
        List<Record> out = new ArrayList<>();
        Object root = MiniJson.parse(json);
        Map<String, Object> message = MiniJson.obj(MiniJson.obj(root).get("message"));
        for (Object item : MiniJson.arr(message.get("items"))) {
            Map<String, Object> it = MiniJson.obj(item);
            Record r = new Record();
            r.pt = "J";
            r.doi = orEmpty(MiniJson.str(it.get("DOI")));
            List<Object> titles = MiniJson.arr(it.get("title"));
            if (!titles.isEmpty()) r.title = orEmpty(MiniJson.str(titles.get(0)));
            List<Object> cont = MiniJson.arr(it.get("container-title"));
            if (!cont.isEmpty()) r.source = orEmpty(MiniJson.str(cont.get(0)));
            for (Object a : MiniJson.arr(it.get("author"))) {
                Map<String, Object> am = MiniJson.obj(a);
                String family = orEmpty(MiniJson.str(am.get("family")));
                String given = orEmpty(MiniJson.str(am.get("given")));
                if (family.isEmpty() && given.isEmpty()) continue;
                // simple initials: first letters of the given tokens
                StringBuilder ini = new StringBuilder();
                for (String tok : given.split("[\\s.-]+"))
                    if (!tok.isEmpty()) ini.append(Character.toUpperCase(tok.charAt(0))).append(' ');
                r.authors.add((family + " " + ini).trim().toUpperCase(Locale.ROOT));
                r.authorsFull.add((family + " " + given).trim());
            }
            Map<String, Object> issued = MiniJson.obj(it.get("issued"));
            List<Object> dp = MiniJson.arr(issued.get("date-parts"));
            if (!dp.isEmpty()) {
                List<Object> parts = MiniJson.arr(dp.get(0));
                if (!parts.isEmpty()) r.year = (int) MiniJson.num(parts.get(0));
            }
            for (Object s : MiniJson.arr(it.get("subject"))) {
                String k = MiniJson.str(s);
                if (k != null && !k.isBlank()) r.keywords.add(k);
            }
            r.timesCited = (int) MiniJson.num(it.getOrDefault("is-referenced-by-count", Double.valueOf(0)));
            if (r.year > 0 || !r.title.isEmpty()) out.add(r);
        }
        return out;
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }
}
