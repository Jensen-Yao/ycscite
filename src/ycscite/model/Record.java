package ycscite.model;

import java.util.ArrayList;
import java.util.List;

/** One bibliographic record, aligned with Web of Science export fields used by CiteSpace. */
public final class Record {
    public String pt = "";                    // publication type
    public List<String> authors = new ArrayList<>();      // AU short names
    public List<String> authorsFull = new ArrayList<>();  // AF full names
    public String title = "";                 // TI
    public String source = "";                // SO journal
    public List<String> keywords = new ArrayList<>();     // DE author keywords
    public List<String> keywordsPlus = new ArrayList<>(); // ID keywords plus
    public String abstractText = "";          // AB
    public List<String> addresses = new ArrayList<>();    // C1
    public List<String> citedRefs = new ArrayList<>();    // CR raw lines
    public int year = 0;                      // PY
    public String volume = "";                // VL
    public String beginPage = "";             // BP
    public String doi = "";                   // DI
    public String ut = "";                    // UT accession number
    public int timesCited = 0;                // TC
    public List<String> categories = new ArrayList<>(); // WC 学科

    public List<String> allAuthors() {
        return authorsFull.isEmpty() ? authors : authorsFull;
    }

    public List<String> allKeywords() {
        List<String> out = new ArrayList<>(keywords);
        out.addAll(keywordsPlus);
        return out;
    }
}
