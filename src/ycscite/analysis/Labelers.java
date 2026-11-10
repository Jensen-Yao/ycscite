package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * Cluster-labeling variants aligned with CiteSpace's label sources:
 *   LLR   — log-likelihood ratio (Dunning 1993), the default (existing LLRLabeler);
 *   LSI   — latent-semantic style: cosine between a term's cluster-distribution vector
 *           and the cluster's indicator vector;
 *   ISI-KW — cluster named by its most frequent member labels (the "isi-kw" keyword style).
 */
public final class Labelers {

    public static final String LLR = "LLR";
    public static final String LSI = "LSI";
    public static final String ISI_KW = "ISI-KW";
    public static final String TFIDF = "TFIDF";

    /** 标注词源（原版 "Label: Use Titles / Keywords / Abstracts / Fields of Study / Reference Titles / T+K+A"）。 */
    public static final String SRC_K = "K";      // 关键词（节点标签口径；CNKI/CSSCI 数据原版自动降级至此）
    public static final String SRC_T = "T";      // 题名词
    public static final String SRC_A = "A";      // 摘要词
    public static final String SRC_WC = "WC";    // 学科类别
    public static final String SRC_R = "R";      // 参考文献题名
    public static final String SRC_TKA = "TKA";  // 题名+关键词+摘要

    public static Map<Integer, List<LLRLabeler.Scored>> label(Network g, String method, int topPerCluster) {
        return label(g, method, topPerCluster, SRC_K, null);
    }

    /**
     * 带词源的标注：K=节点标签口径（网络术语自身）；T/A/WC/R/TKA=文档级口径 ——
     * 簇的"施引文献集"=含该簇任一术语的记录，词频为文档级出现率，LLR 检验词在簇内外的集中度。
     */
    public static Map<Integer, List<LLRLabeler.Scored>> label(Network g, String method, int topPerCluster,
                                                              String source, List<ycscite.model.Record> records) {
        boolean textSource = records != null && !records.isEmpty()
                && !SRC_K.equalsIgnoreCase(source) && source != null;
        if (textSource) return docLlr(g, topPerCluster, source, records);
        if (LSI.equalsIgnoreCase(method)) return lsi(g, topPerCluster);
        if (ISI_KW.equalsIgnoreCase(method)) return isiKw(g, topPerCluster);
        if (TFIDF.equalsIgnoreCase(method)) return tfidf(g, topPerCluster);
        if (KCORE.equalsIgnoreCase(method)) return kCore(g, topPerCluster);
        if (MI.equalsIgnoreCase(method)) return mi(g, topPerCluster);
        return LLRLabeler.compute(g, topPerCluster);
    }

    public static final String KCORE = "KCORE";
    public static final String MI = "MI";

    /** k-core 标注（原版 "Labeling with k-Core Citers"）：仅用核数≥k 的骨干成员的频次标签。 */
    static Map<Integer, List<LLRLabeler.Scored>> kCore(Network g, int topPerCluster) {
        Map<GraphNode, Integer> core = KCore.compute(g);
        int median = 1;
        List<Integer> cs = new ArrayList<>(core.values());
        Collections.sort(cs);
        if (!cs.isEmpty()) median = Math.max(1, cs.get(cs.size() / 2));
        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        Map<Integer, List<GraphNode>> byCluster = clusters(g);
        for (Map.Entry<Integer, List<GraphNode>> en : byCluster.entrySet()) {
            List<GraphNode> members = new ArrayList<>();
            for (GraphNode n : en.getValue()) if (core.getOrDefault(n, 0) >= median) members.add(n);
            if (members.isEmpty()) members = en.getValue();
            members.sort((a, b) -> Integer.compare(b.weight, a.weight));
            List<LLRLabeler.Scored> labels = new ArrayList<>();
            for (int i = 0; i < Math.min(topPerCluster, members.size()); i++)
                labels.add(new LLRLabeler.Scored(members.get(i).label, members.get(i).weight));
            out.put(en.getKey(), labels);
        }
        return out;
    }

    /** 互信息标注（原版 "Show Terms by MI"，ph.java:657）：MI(t,c) = p(t,c)·log[p(t,c)/(p(t)·p(c))]。 */
    static Map<Integer, List<LLRLabeler.Scored>> mi(Network g, int topPerCluster) {
        Map<Integer, List<GraphNode>> byCluster = clusters(g);
        long total = 0;
        for (GraphNode n : g.nodeCollection()) total += n.weight;
        if (total <= 0) return LLRLabeler.compute(g, topPerCluster);
        // term → cluster → count
        Map<String, Map<Integer, Long>> termVec = new LinkedHashMap<>();
        Map<Integer, Long> clusterSize = new HashMap<>();
        for (var en : byCluster.entrySet()) {
            long sz = 0;
            for (GraphNode n : en.getValue()) {
                sz += n.weight;
                for (String t : LLRLabeler.terms(n))
                    termVec.computeIfAbsent(t, k -> new HashMap<>()).merge(en.getKey(), (long) n.weight, Long::sum);
            }
            clusterSize.put(en.getKey(), sz);
        }
        Map<String, Long> termTotal = new HashMap<>();
        for (var en : termVec.entrySet()) {
            long s = 0;
            for (long v : en.getValue().values()) s += v;
            termTotal.put(en.getKey(), s);
        }
        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        for (var en : byCluster.entrySet()) {
            int c = en.getKey();
            double pc = (double) clusterSize.get(c) / total;
            List<LLRLabeler.Scored> scored = new ArrayList<>();
            for (var te : termVec.entrySet()) {
                Long inC = te.getValue().get(c);
                if (inC == null || inC == 0) continue;
                double ptc = inC / (double) total;
                double pt = termTotal.get(te.getKey()) / (double) total;
                double mi = ptc * Math.log(ptc / (pt * pc));
                if (mi > 0) scored.add(new LLRLabeler.Scored(te.getKey(), mi));
            }
            scored.sort((x, y) -> Double.compare(y.g2, x.g2));
            out.put(c, scored.subList(0, Math.min(topPerCluster, scored.size())));
        }
        return out;
    }

    /** 文档级 LLR：a=簇内含词文档数，b=簇外含词文档数，2×2 G² 检验（Dunning）。 */
    static Map<Integer, List<LLRLabeler.Scored>> docLlr(Network g, int topPerCluster,
                                                        String source, List<ycscite.model.Record> records) {
        // label(upper) → cluster 集合（记录经任一术语归属簇）
        Map<String, Integer> termCluster = new HashMap<>();
        for (GraphNode n : g.nodeCollection())
            if (n.cluster > 0) termCluster.put(n.label.toUpperCase(), n.cluster);
        Map<Integer, List<ycscite.model.Record>> docsIn = new TreeMap<>();
        List<ycscite.model.Record> all = new ArrayList<>();
        for (ycscite.model.Record r : records) {
            all.add(r);
            for (String k : r.allKeywords()) {
                Integer c = termCluster.get(k.trim().toUpperCase());
                if (c != null) { docsIn.computeIfAbsent(c, x -> new ArrayList<>()).add(r); break; }
            }
        }
        // 词 → 含词文档集合（按词源分词）
        Map<String, Set<Integer>> termDocs = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++)
            for (String t : sourceTerms(all.get(i), source))
                termDocs.computeIfAbsent(t, x -> new HashSet<>()).add(i);

        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        Set<Integer> allIdx = new HashSet<>();
        Map<ycscite.model.Record, Integer> idxOf = new java.util.IdentityHashMap<>();
        for (int i = 0; i < all.size(); i++) { allIdx.add(i); idxOf.put(all.get(i), i); }
        for (Map.Entry<Integer, List<ycscite.model.Record>> en : docsIn.entrySet()) {
            Set<Integer> in = new HashSet<>();
            for (ycscite.model.Record r : en.getValue()) in.add(idxOf.get(r));
            Set<Integer> outSide = new HashSet<>(allIdx); outSide.removeAll(in);
            long nIn = in.size(), nOut = outSide.size();
            if (nIn == 0) continue;
            List<LLRLabeler.Scored> scored = new ArrayList<>();
            for (Map.Entry<String, Set<Integer>> te : termDocs.entrySet()) {
                long a = 0;
                for (int i : te.getValue()) if (in.contains(i)) a++;
                long b = te.getValue().size() - a;
                long c2 = nIn - a, d = nOut - b;
                double g2 = LLRLabeler.llr2((int) a, (int) b, (int) c2, (int) d);
                if (g2 > 0 && a > 0) scored.add(new LLRLabeler.Scored(te.getKey(), g2));
            }
            scored.sort((x, y) -> Double.compare(y.g2, x.g2));
            out.put(en.getKey(), scored.subList(0, Math.min(topPerCluster, scored.size())));
        }
        return out;
    }

    /** 按词源取一条记录的候选词。 */
    static List<String> sourceTerms(ycscite.model.Record r, String source) {
        List<String> out = new ArrayList<>();
        switch (source.toUpperCase()) {
            case SRC_T -> out.addAll(tokenize(r.title));
            case SRC_A -> out.addAll(tokenize(r.abstractText));
            case SRC_WC -> { for (String c : r.categories) if (!c.isBlank()) out.add(c.trim()); }
            case SRC_R -> {
                for (String raw : r.citedRefs) {
                    String[] seg = raw.split(",", 4);
                    out.addAll(tokenize(seg.length == 4 ? seg[3] : raw));
                }
            }
            default -> {  // TKA：题名+关键词+摘要
                out.addAll(tokenize(r.title));
                for (String k : r.allKeywords()) if (!k.isBlank()) out.add(k.trim());
                out.addAll(tokenize(r.abstractText));
            }
        }
        return out;
    }

    private static final Set<String> STOP = Set.of("the", "of", "and", "in", "to", "a", "an", "for", "with",
            "on", "by", "as", "is", "are", "its", "from", "at", "this", "that", "be", "or", "which", "their",
            "these", "those", "it", "we", "our", "can", "will", "was", "were", "has", "have", "had", "not",
            "based", "study", "paper", "research", "between", "into", "than", "then", "also", "such", "other",
            "more", "most", "some", "all", "may", "one", "two", "three", "but", "over", "under", "among");

    /**
     * 混合分词：拉丁字母词（≥2 字符，去停用词）+ CJK 连续段 bigram（中文无分词依赖的近似；
     * 原版对 CNKI/CSSCI 数据自动改用 K 词源，T/A 主要服务英文数据）。
     */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z\\-']{1,}").matcher(text);
        while (m.find()) {
            String w = m.group().toLowerCase();
            if (!STOP.contains(w)) out.add(w);
        }
        StringBuilder cjk = new StringBuilder();
        Runnable flush = () -> {
            if (cjk.length() >= 2)
                for (int i = 0; i + 1 < cjk.length(); i++)
                    out.add(cjk.substring(i, i + 2));
            cjk.setLength(0);
        };
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) cjk.append(ch);
            else flush.run();
        }
        flush.run();
        return out;
    }

    /** TF-IDF 标注：tf(词,簇) × log((1+簇数)/(1+含该词簇数))。 */
    static Map<Integer, List<LLRLabeler.Scored>> tfidf(Network g, int topPerCluster) {
        Map<Integer, List<GraphNode>> clusters = clusters(g);
        Map<String, Map<Integer, Integer>> termVec = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<GraphNode>> en : clusters.entrySet())
            for (GraphNode n : en.getValue())
                for (String t : LLRLabeler.terms(n))
                    termVec.computeIfAbsent(t, k -> new HashMap<>()).merge(en.getKey(), n.weight, Integer::sum);
        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        for (Integer c : clusters.keySet()) {
            List<LLRLabeler.Scored> scored = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, Integer>> te : termVec.entrySet()) {
                int tf = te.getValue().getOrDefault(c, 0);
                if (tf <= 0) continue;
                double idf = Math.log((1.0 + clusters.size()) / (1.0 + te.getValue().size()));
                scored.add(new LLRLabeler.Scored(te.getKey(), tf * idf));
            }
            scored.sort((x, y) -> Double.compare(y.g2, x.g2));
            out.put(c, scored.subList(0, Math.min(topPerCluster, scored.size())));
        }
        return out;
    }

    /** LSI-style: score(term, cluster) = count(term in c) / ||count(term, ·)||₂ (cosine with indicator). */
    static Map<Integer, List<LLRLabeler.Scored>> lsi(Network g, int topPerCluster) {
        Map<Integer, List<GraphNode>> clusters = clusters(g);
        // term -> (cluster -> count)
        Map<String, Map<Integer, Integer>> termVec = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<GraphNode>> en : clusters.entrySet())
            for (GraphNode n : en.getValue())
                for (String t : LLRLabeler.terms(n))
                    termVec.computeIfAbsent(t, k -> new HashMap<>()).merge(en.getKey(), n.weight, Integer::sum);

        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        for (Integer c : clusters.keySet()) {
            List<LLRLabeler.Scored> scored = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, Integer>> te : termVec.entrySet()) {
                int inC = te.getValue().getOrDefault(c, 0);
                if (inC <= 0) continue;
                double norm = 0;
                for (int v : te.getValue().values()) norm += (double) v * v;
                scored.add(new LLRLabeler.Scored(te.getKey(), inC / Math.sqrt(norm)));
            }
            scored.sort((x, y) -> Double.compare(y.g2, x.g2));
            out.put(c, scored.subList(0, Math.min(topPerCluster, scored.size())));
        }
        return out;
    }

    /** ISI-KW style: label each cluster by its most frequent member labels. */
    static Map<Integer, List<LLRLabeler.Scored>> isiKw(Network g, int topPerCluster) {
        Map<Integer, List<GraphNode>> clusters = clusters(g);
        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        for (Map.Entry<Integer, List<GraphNode>> en : clusters.entrySet()) {
            List<GraphNode> members = new ArrayList<>(en.getValue());
            members.sort((a, b) -> Integer.compare(b.weight, a.weight));
            List<LLRLabeler.Scored> labels = new ArrayList<>();
            for (int i = 0; i < Math.min(topPerCluster, members.size()); i++)
                labels.add(new LLRLabeler.Scored(members.get(i).label, members.get(i).weight));
            out.put(en.getKey(), labels);
        }
        return out;
    }

    private static Map<Integer, List<GraphNode>> clusters(Network g) {
        Map<Integer, List<GraphNode>> out = new TreeMap<>();
        for (GraphNode n : g.nodeCollection())
            if (n.cluster >= 0) out.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        return out;
    }
}
