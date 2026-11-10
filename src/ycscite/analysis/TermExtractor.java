package ycscite.analysis;

import ycscite.model.Record;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Term extraction for the "Term co-occurrence" network (CiteSpace's noun-phrase terms
 * from titles/abstracts): stopword-filtered unigrams + adjacent-content-word bigrams,
 * lowercased, deduplicated.
 */
public final class TermExtractor {

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "or", "of", "in", "on", "for", "to", "with", "by", "at", "as",
            "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those",
            "we", "our", "it", "its", "from", "which", "based", "using", "used", "use", "can",
            "than", "then", "also", "between", "into", "over", "under", "such", "their", "they",
            "has", "have", "had", "not", "but", "more", "most", "other", "some", "may", "will",
            "would", "could", "should", "study", "studies", "paper", "papers", "article", "results",
            "result", "method", "methods", "approach", "analysis", "research", "propose", "proposed",
            "study.", "two", "three", "one", "new", "different", "however", "there", "when", "where"));

    public static List<String> extract(Record r) {
        String text = (r.title + ". " + r.abstractText).toLowerCase(Locale.ROOT);
        String[] tokens = text.split("[^a-z\\-]+");
        List<String> words = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() < 3 || t.startsWith("-") || STOP.contains(t)) continue;
            words.add(t);
        }
        Set<String> terms = new java.util.LinkedHashSet<>();
        for (int i = 0; i < words.size(); i++) {
            terms.add(words.get(i));
            if (i + 1 < words.size()) terms.add(words.get(i) + " " + words.get(i + 1));
        }
        return new ArrayList<>(terms);
    }
}
