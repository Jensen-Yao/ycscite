package ycscite.model;

/** A cited reference parsed from a CR line, e.g. "SMITH J, 2010, J INFORMETR, V4, P13". */
public final class CitedRef {
    public final String key;      // canonical key: "SMITH J, 2010, J INFORMETR"
    public final String author;   // first author segment
    public final int year;
    public final String source;

    public CitedRef(String key, String author, int year, String source) {
        this.key = key;
        this.author = author;
        this.year = year;
        this.source = source;
    }

    /** Parse the first three comma segments of a raw CR entry; fall back to the raw text. */
    public static CitedRef parse(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String[] seg = s.split(",", 4);
        if (seg.length < 3) return new CitedRef(s, s, 0, "");
        String author = seg[0].trim();
        String yearStr = seg[1].replaceAll("[^0-9]", "").trim();
        int year = 0;
        try { if (!yearStr.isEmpty()) year = Integer.parseInt(yearStr); } catch (NumberFormatException ignored) { }
        String source = seg[2].trim();
        String key = (author + ", " + (year > 0 ? year : seg[1].trim()) + ", " + source).toUpperCase();
        return new CitedRef(key, author, year, source);
    }

    public String display() {
        String a = author.length() > 24 ? author.substring(0, 24) : author;
        return year > 0 ? (a + " (" + year + ")") : a;
    }
}
