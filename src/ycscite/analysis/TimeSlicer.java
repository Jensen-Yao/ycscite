package ycscite.analysis;

import ycscite.model.Project;
import ycscite.model.Record;

import java.util.ArrayList;
import java.util.List;

/** Time slicing: split records into 1-year slices (aligned with CiteSpace's default). */
public final class TimeSlicer {

    public static List<int[]> availableSlices(Project p) {
        List<int[]> out = new ArrayList<>();
        if (p.minYear == Integer.MAX_VALUE) return out;
        out.add(new int[]{p.minYear, p.maxYear});
        return out;
    }

    public static List<List<Record>> sliceByYear(Project p) {
        List<List<Record>> out = new ArrayList<>();
        if (p.minYear == Integer.MAX_VALUE || p.records.isEmpty()) return out;
        for (int y = p.minYear; y <= p.maxYear; y++) out.add(new ArrayList<>());
        for (Record r : p.records)
            if (r.year >= p.minYear && r.year <= p.maxYear) out.get(r.year - p.minYear).add(r);
        return out;
    }
}
