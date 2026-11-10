package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** KML 2.2 导出（原版 "Generate Google Earth Maps (KML 2.0/2.2)" + CSV for Tableau Public）。 */
public final class KmlExporter {

    /** 内置常用国家/城市坐标（离线地理编码；原版用 Yahoo/Nominatim 在线 API）。 */
    static final Map<String, double[]> GEOCODE = Map.ofEntries(
            Map.entry("PEOPLES R CHINA", new double[]{35.0, 105.0}),
            Map.entry("CHINA", new double[]{35.0, 105.0}),
            Map.entry("USA", new double[]{39.8, -98.5}),
            Map.entry("UK", new double[]{54.0, -2.0}),
            Map.entry("ENGLAND", new double[]{52.0, -1.0}),
            Map.entry("JAPAN", new double[]{36.0, 138.0}),
            Map.entry("GERMANY", new double[]{51.0, 10.0}),
            Map.entry("FRANCE", new double[]{46.0, 2.0}),
            Map.entry("CANADA", new double[]{60.0, -96.0}),
            Map.entry("AUSTRALIA", new double[]{-27.0, 133.0}),
            Map.entry("NETHERLANDS", new double[]{52.0, 5.0}),
            Map.entry("SPAIN", new double[]{40.0, -4.0}),
            Map.entry("ITALY", new double[]{42.0, 12.0}),
            Map.entry("SOUTH KOREA", new double[]{37.0, 127.5}),
            Map.entry("INDIA", new double[]{22.0, 78.0}),
            Map.entry("BRAZIL", new double[]{-10.0, -52.0}),
            Map.entry("BEIJING", new double[]{39.9, 116.4}),
            Map.entry("SHANGHAI", new double[]{31.2, 121.5}),
            Map.entry("HONG KONG", new double[]{22.3, 114.2}),
            Map.entry("TAIWAN", new double[]{23.7, 121.0}));

    /** 导出 KML（机构/国家 → 地理标记，气泡含论文计数）+ Tableau CSV。 */
    public static void export(Project p, Path kmlOut, Path csvOut) throws IOException {
        Map<String, Integer> locationCount = new TreeMap<>();
        Map<String, Set<String>> locationPubs = new HashMap<>();
        for (Record r : p.records) {
            Set<String> locs = new HashSet<>();
            for (String addr : r.addresses) {
                String c = extractCountry(addr);
                if (!c.isEmpty()) locs.add(c);
            }
            for (String loc : locs) {
                locationCount.merge(loc, 1, Integer::sum);
                locationPubs.computeIfAbsent(loc, k -> new HashSet<>())
                           .add(r.title.isEmpty() ? "Untitled" : r.title);
            }
        }
        // KML
        StringBuilder kml = new StringBuilder();
        kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n");
        for (var en : locationCount.entrySet()) {
            double[] geo = GEOCODE.get(en.getKey());
            if (geo == null) continue;   // 无坐标的跳过（原版行为：Failed to get geocode）
            kml.append("<Placemark>\n  <name>").append(esc(en.getKey())).append("</name>\n");
            kml.append("  <description><![CDATA[").append(en.getValue()).append(" 篇论文]]></description>\n");
            kml.append("  <Point><coordinates>").append(geo[1]).append(',').append(geo[0]).append(",0</coordinates></Point>\n");
            kml.append("</Placemark>\n");
        }
        kml.append("</Document>\n</kml>\n");
        Files.writeString(kmlOut, kml.toString(), StandardCharsets.UTF_8);
        // Tableau CSV
        if (csvOut != null) {
            StringBuilder csv = new StringBuilder("location,count,latitude,longitude\n");
            for (var en : locationCount.entrySet()) {
                double[] geo = GEOCODE.get(en.getKey());
                if (geo == null) continue;
                csv.append(csvq(en.getKey())).append(',').append(en.getValue()).append(',')
                   .append(geo[0]).append(',').append(geo[1]).append('\n');
            }
            Files.writeString(csvOut, csv.toString(), StandardCharsets.UTF_8);
        }
    }

    static String extractCountry(String addr) {
        String s = addr.replaceAll("\\[?\\d*\\]?", "").trim();
        int last = s.lastIndexOf(',');
        String c = last > 0 ? s.substring(last + 1).trim() : s;
        c = c.replaceAll("[0-9\\-]+", "").trim().toUpperCase();
        if (c.isEmpty()) return "";
        for (var en : GEOCODE.entrySet())
            if (c.contains(en.getKey()) || en.getKey().contains(c)) return en.getKey();
        return c;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String csvq(String s) { return s.contains(",") ? "\"" + s + "\"" : s; }
}
