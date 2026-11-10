package ycscite.analysis;

import ycscite.model.CitedRef;
import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Record;

import java.util.*;

/** Builds the CiteSpace network types from a record set. */
public final class NetworkBuilders {

    public static final String CO_AUTHOR = "Co-authorship";
    public static final String KEYWORD_COOCCURRENCE = "Keyword co-occurrence";
    public static final String TERM_COOCCURRENCE = "Term co-occurrence";
    public static final String CO_CITATION = "Document co-citation";
    public static final String BIB_COUPLING = "Bibliographic coupling";
    public static final String INSTITUTION = "Institution co-occurrence";
    /** ACA：被引作者共被引（原版 "ACA - Author Co-citation Analysis"，CiteSpace.java 854-861） */
    public static final String AUTHOR_COCITATION = "Author co-citation";
    /** JCA：被引期刊共被引（原版 "JCA - Journal Co-citation Analysis"） */
    public static final String JOURNAL_COCITATION = "Journal co-citation";
    /** WC 学科共现（原版 Fields of Study / Web of Science Category） */
    public static final String CATEGORY_COOCCURRENCE = "Category co-occurrence";
    /** 国家共现（原版 Node Type: Country，地址末段提取） */
    public static final String COUNTRY_COOCCURRENCE = "Country co-occurrence";

    public static Network build(String kind, List<Record> records) {
        switch (kind) {
            case CO_AUTHOR: return coAuthorship(records);
            case KEYWORD_COOCCURRENCE: return keywordCoOccurrence(records);
            case TERM_COOCCURRENCE: return termCoOccurrence(records);
            case CO_CITATION: return coCitation(records);
            case BIB_COUPLING: return bibCoupling(records);
            case INSTITUTION: return institutions(records);
            case AUTHOR_COCITATION: return authorCoCitation(records);
            case JOURNAL_COCITATION: return journalCoCitation(records);
            case CATEGORY_COOCCURRENCE: return categories(records);
            case COUNTRY_COOCCURRENCE: return countries(records);
            default: throw new IllegalArgumentException("unknown network kind: " + kind);
        }
    }

    /** ACA：同一记录引用的作者对（首作者，key=作者+年）。 */
    public static Network authorCoCitation(List<Record> records) {
        Network g = new Network(AUTHOR_COCITATION);
        for (Record r : records) {
            List<GraphNode> set = new ArrayList<>();
            for (String raw : r.citedRefs) {
                CitedRef cr = CitedRef.parse(raw);
                if (cr == null || cr.author.isBlank()) continue;
                String key = (cr.author + ", " + (cr.year > 0 ? cr.year : "?")).toUpperCase(Locale.ROOT);
                GraphNode n = g.node(key, cr.display(), "cited-author");
                n.weight++;
                n.addYear(r.year);
                set.add(n);
            }
            g.noteYear(r.year);
            for (int i = 0; i < set.size(); i++)
                for (int j = i + 1; j < set.size(); j++)
                    g.link(set.get(i), set.get(j));
        }
        return g;
    }

    /** JCA：同一记录引用的期刊对（CR 第三段）。 */
    public static Network journalCoCitation(List<Record> records) {
        Network g = new Network(JOURNAL_COCITATION);
        for (Record r : records) {
            List<GraphNode> set = new ArrayList<>();
            for (String raw : r.citedRefs) {
                CitedRef cr = CitedRef.parse(raw);
                if (cr == null || cr.source.isBlank()) continue;
                GraphNode n = g.node(cr.source.toUpperCase(Locale.ROOT), cr.source, "cited-journal");
                n.weight++;
                n.addYear(r.year);
                set.add(n);
            }
            g.noteYear(r.year);
            for (int i = 0; i < set.size(); i++)
                for (int j = i + 1; j < set.size(); j++)
                    g.link(set.get(i), set.get(j));
        }
        return g;
    }

    /** WC 学科共现：同一记录的学科类别两两共现。 */
    public static Network categories(List<Record> records) {
        Network g = new Network(CATEGORY_COOCCURRENCE);
        for (Record r : records) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String c : r.categories) {
                String s = c.trim().toUpperCase(Locale.ROOT);
                if (!s.isEmpty()) seen.add(s);
            }
            List<GraphNode> set = new ArrayList<>();
            for (String c : seen) {
                GraphNode n = g.node(c, c, "category");
                n.weight++;
                n.addYear(r.year);
                set.add(n);
            }
            g.noteYear(r.year);
            for (int i = 0; i < set.size(); i++)
                for (int j = i + 1; j < set.size(); j++)
                    g.link(set.get(i), set.get(j));
        }
        return g;
    }

    /** 国家共现：地址末段（逗号后）提取国家，同一记录的国家对共现。 */
    public static Network countries(List<Record> records) {
        Network g = new Network(COUNTRY_COOCCURRENCE);
        for (Record r : records) {
            LinkedHashSet<String> set0 = new LinkedHashSet<>();
            for (String addr : r.addresses) {
                String s = addr.replaceAll("\\[?\\d*\\]?\\s*", "").trim();
                int last = s.lastIndexOf(',');
                String c = last > 0 ? s.substring(last + 1) : s;
                c = c.replaceAll("[0-9\\-]+", "").trim().toUpperCase(Locale.ROOT);
                if (c.isEmpty()) continue;
                set0.add(normalizeCountry(c));
            }
            List<GraphNode> set = new ArrayList<>();
            for (String c : set0) {
                GraphNode n = g.node(c, c, "country");
                n.weight++;
                n.addYear(r.year);
                set.add(n);
            }
            g.noteYear(r.year);
            for (int i = 0; i < set.size(); i++)
                for (int j = i + 1; j < set.size(); j++)
                    g.link(set.get(i), set.get(j));
        }
        return g;
    }

    /** 中国省市区县归并为"中国"；英文国名常用形态归并。 */
    static String normalizeCountry(String raw) {
        String[] provinces = {"PEOPLES R CHINA", "PEOPLE R CHINA", "P R CHINA", "CHINA", "BEIJING", "SHANGHAI",
                "HUNAN", "HUBEI", "GUANGDONG", "JIANGSU", "ZHEJIANG", "SICHUAN", "SHANDONG", "HENAN",
                "FUJIAN", "ANHUI", "SHAANXI", "LIAONING", "TIANJIN", "CHONGQING", "YUNNAN", "GUANGXI",
                "GUIZHOU", "HEBEI", "SHANXI", "JILIN", "HEILONGJIANG", "JIANGXI", "GANSU", "QINGHAI",
                "HAINAN", "NINGXIA", "XINJIANG", "XIZANG", "INNER MONGOLIA", "TAIWAN", "HONG KONG", "MACAU",
                "北京", "上海", "湖南", "湖北", "广东", "江苏", "浙江", "四川", "山东", "河南", "福建", "安徽",
                "陕西", "辽宁", "天津", "重庆", "云南", "广西", "贵州", "河北", "山西", "吉林", "黑龙江",
                "江西", "甘肃", "青海", "海南", "宁夏", "新疆", "西藏", "内蒙古", "台湾", "香港", "澳门"};
        for (String p : provinces) if (raw.contains(p)) return "PEOPLES R CHINA";
        if (raw.contains("USA") || raw.contains("UNITED STATES") || raw.contains("AMERICA")) return "USA";
        if (raw.contains("UK") || raw.contains("ENGLAND") || raw.contains("SCOTLAND")
                || raw.contains("BRITAIN") || raw.contains("WALES")) return "UK";
        return raw;
    }

    /** Term co-occurrence: noun-phrase terms extracted from titles/abstracts. */
    public static Network termCoOccurrence(List<Record> records) {
        Network g = new Network(TERM_COOCCURRENCE);
        for (Record r : records) {
            g.noteYear(r.year);
            List<String> terms = TermExtractor.extract(r);
            for (int i = 0; i < terms.size(); i++) {
                GraphNode ti = g.node(terms.get(i), terms.get(i), "term");
                ti.weight++;
                ti.addYear(r.year);
                for (int j = i + 1; j < terms.size(); j++)
                    g.link(ti, g.node(terms.get(j), terms.get(j), "term"));
            }
        }
        return g;
    }

    /** Co-authorship: every author pair within each record. */
    static Network coAuthorship(List<Record> records) {
        Network g = new Network(CO_AUTHOR);
        for (Record r : records) {
            g.noteYear(r.year);
            List<String> as = new ArrayList<>();
            for (String a : r.allAuthors()) {
                // 原版作者 id 规范化（1943.graphml 边差分验证）：", "→"_"（JIANG, MIN→JIANG_MIN），UNKNOWN 过滤
                String norm = a.trim().toUpperCase(Locale.ROOT).replace(", ", "_");
                if (norm.startsWith("UNKNOWN")) continue;
                if (!norm.isEmpty()) as.add(norm);
            }
            for (int i = 0; i < as.size(); i++) {
                GraphNode ai = g.node(as.get(i), as.get(i), "author");   // label=id（原版 graphml label 同为下划线版）
                ai.weight++;
                ai.addYear(r.year);
                for (int j = i + 1; j < as.size(); j++)
                    g.linkWeighted(ai, g.node(as.get(j), as.get(j), "author"), 1, r.year);
            }
        }
        return g;
    }

    /** Keyword co-occurrence: DE + ID keyword pairs within each record. */
    /** 原版词长口径（边差分验证 AIGC/IAP 案例）：含空格=按词数；纯 ASCII 单词=1 词（必滤）；中文=字数。 */
    private static int npLen(String k) {
        if (k.indexOf(' ') >= 0) return k.trim().split("\s+").length;
        boolean ascii = true;
        for (int i = 0; i < k.length(); i++)
            if (k.charAt(i) > 127) { ascii = false; break; }
        return ascii ? 1 : k.length();
    }

    public static Network keywordCoOccurrence(List<Record> records) {
        return keywordCoOccurrence(records, 0, 0);
    }

    /** 词长过滤变体（原版 NounPhraseWords Min/Max：中文=字数；候选词表口径，2026-11 逐片差分验证 54=54/10=10/23=23）。 */
    public static Network keywordCoOccurrence(List<Record> records, int minLen, int maxLen) {
        Network g = new Network(KEYWORD_COOCCURRENCE);
        for (Record r : records) {
            g.noteYear(r.year);
            List<String> ks = dedupLower(r.allKeywords());
            if (minLen > 0 && maxLen > 0)
                ks.removeIf(k -> npLen(k) < minLen || npLen(k) > maxLen);
            for (int i = 0; i < ks.size(); i++) {
                GraphNode ki = g.node(ks.get(i).toUpperCase(Locale.ROOT), ks.get(i), "keyword");
                ki.weight++;
                ki.addYear(r.year);
                for (int j = i + 1; j < ks.size(); j++)
                    g.linkWeighted(ki, g.node(ks.get(j).toUpperCase(Locale.ROOT), ks.get(j), "keyword"), 1, r.year);
            }
        }
        return g;
    }

    /** Document co-citation: pairs of references cited by the same record. */
    public static Network coCitation(List<Record> records) {
        Network g = new Network(CO_CITATION);
        for (Record r : records) {
            List<GraphNode> refs = new ArrayList<>();
            for (String raw : r.citedRefs) {
                CitedRef cr = CitedRef.parse(raw);
                if (cr == null) continue;
                GraphNode n = g.node(cr.key, cr.display(), "cited-ref");
                n.weight++;
                n.addYear(r.year);
                refs.add(n);
            }
            g.noteYear(r.year);
            for (int i = 0; i < refs.size(); i++)
                for (int j = i + 1; j < refs.size(); j++)
                    g.link(refs.get(i), refs.get(j));
        }
        return g;
    }

    /** Bibliographic coupling: documents linked by shared references. */
    static Network bibCoupling(List<Record> records) {
        Network g = new Network(BIB_COUPLING);
        List<Map<String, Integer>> refSets = new ArrayList<>();
        List<GraphNode> docs = new ArrayList<>();
        int i = 0;
        for (Record r : records) {
            String id = !r.ut.isEmpty() ? r.ut : (r.doi.isEmpty() ? "DOC" + (++i) : r.doi);
            GraphNode d = g.node(id, !r.title.isEmpty() && r.title.length() > 60 ? r.title.substring(0, 60) + "…" : r.title, "document");
            d.weight = 1 + r.timesCited / 10;
            d.addYear(r.year);
            docs.add(d);
            g.noteYear(r.year);
            Map<String, Integer> set = new HashMap<>();
            for (String raw : r.citedRefs) {
                CitedRef cr = CitedRef.parse(raw);
                if (cr != null) set.merge(cr.key, 1, Integer::sum);
            }
            refSets.add(set);
        }
        for (int a = 0; a < docs.size(); a++)
            for (int b = a + 1; b < docs.size(); b++) {
                int shared = 0;
                Map<String, Integer> small = refSets.get(a).size() < refSets.get(b).size() ? refSets.get(a) : refSets.get(b);
                Map<String, Integer> big = small == refSets.get(a) ? refSets.get(b) : refSets.get(a);
                for (String k : small.keySet()) if (big.containsKey(k)) shared++;
                if (shared >= 2) g.link(docs.get(a), docs.get(b));
            }
        return g;
    }

    /** Institution co-occurrence: first institution segment of each C1 address. */
    static Network institutions(List<Record> records) {
        Network g = new Network(INSTITUTION);
        for (Record r : records) {
            g.noteYear(r.year);
            LinkedHashSet<String> insts = new LinkedHashSet<>();
            for (String addr : r.addresses) {
                String s = addr.replaceAll("^\\[?\\d*\\]?\\s*", "").trim();
                int cut = s.indexOf(',', 0);
                String inst = (cut > 0 ? s.substring(0, cut) : s).trim();
                if (!inst.isEmpty()) insts.add(inst.toUpperCase(Locale.ROOT));
            }
            List<String> list = new ArrayList<>(insts);
            for (int i = 0; i < list.size(); i++) {
                GraphNode n = g.node(list.get(i), list.get(i), "institution");
                n.weight++;
                n.addYear(r.year);
                for (int j = i + 1; j < list.size(); j++)
                    g.linkWeighted(n, g.node(list.get(j), list.get(j), "institution"), 1, r.year);
            }
        }
        return g;
    }

    private static List<String> dedupLower(List<String> in) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String s : in) {
            String t = s.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        return new ArrayList<>(seen);
    }
}
