package ycscite.analysis;

/**
 * 关键词复数→单数归一（原版 "Node Type: Keywords: Convert plural to its singular form"），
 * 保守英文规则：ies→y；ses/xes/zes/ches/shes→去 es；其余词尾 s（非 ss/us/is）→去 s。
 */
public final class KeywordNorm {

    public static String singular(String kw) {
        if (kw == null || kw.length() < 5) return kw;
        String low = kw.toLowerCase();
        String upper = kw.substring(0, kw.length() - 3);
        if (low.endsWith("ies")) return upper + "y";
        if (low.endsWith("ses") || low.endsWith("xes") || low.endsWith("zes")
                || low.endsWith("ches") || low.endsWith("shes"))
            return kw.substring(0, kw.length() - 2);
        if (low.endsWith("s") && !low.endsWith("ss") && !low.endsWith("us") && !low.endsWith("is"))
            return kw.substring(0, kw.length() - 1);
        return kw;
    }
}
