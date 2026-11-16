import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Real workload demand. Instead of hard-coded arrays, the daily demand curve comes from real
 * traffic measured by the Wikimedia Pageviews API (the last 30 complete days), or from the user's
 * own usage export (CSV of date,value - e.g. from AWS Cost Explorer, Azure Cost Management or
 * CloudWatch). The traffic gives the SHAPE of demand; the user's average fleet size gives the SCALE.
 */
final class WorkloadService {
    static final Duration TTL = Duration.ofHours(6);
    static final String WIKI = "https://wikimedia.org/api/rest_v1/metrics/pageviews";
    static final int DAYS = 30;
    private static final DateTimeFormatter D8 = DateTimeFormatter.BASIC_ISO_DATE;

    enum Preset {
        STEADY("Steady", "Whole English Wikipedia - total daily user traffic"),
        SPIKY("Spiky", "Today's most-spiky trending Wikipedia article"),
        SEASONAL("Seasonal", "Weekday business cycle - 'LinkedIn' article traffic"),
        ARTICLE("Custom Wikipedia article", "Any English Wikipedia article you name"),
        CSV("My usage CSV", "Your own daily usage export (date,value)");
        final String label, description;
        Preset(String label, String description) { this.label = label; this.description = description; }
        @Override public String toString() { return label; }
    }

    static final class Workload {
        String name, source, sourceUrl, detectedPattern, patternReason;
        Preset preset;
        final List<String> dates = new ArrayList<>();
        double[] values;
        String fetchedAt;
        boolean stale;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); m.put("preset", preset.label); m.put("source", source); m.put("sourceUrl", sourceUrl);
            m.put("fetchedAt", fetchedAt); m.put("stale", stale);
            m.put("detectedPattern", detectedPattern); m.put("patternReason", patternReason);
            m.put("dates", dates); m.put("rawValues", values);
            return m;
        }
    }

    /** Last complete day available from Wikimedia (data lands roughly a day late). */
    static LocalDate endDate() { return LocalDate.now(ZoneOffset.UTC).minusDays(2); }

    static Workload load(Preset preset, String article, String csv) throws IOException {
        LocalDate end = endDate();
        LocalDate start = end.minusDays(DAYS - 1);
        switch (preset) {
            case STEADY: {
                String url = WIKI + "/aggregate/en.wikipedia/all-access/user/daily/" + start.format(D8) + "/" + end.format(D8);
                return fromWiki(preset, "en.wikipedia.org (all pages)", url, start, end);
            }
            case SEASONAL:
                return article(preset, "LinkedIn", start, end);
            case SPIKY:
                return article(preset, mostSpikyTrending(end, start), start, end);
            case ARTICLE:
                if (article == null || article.isBlank()) throw new IOException("Enter a Wikipedia article title, e.g. Cricket");
                return article(preset, article.trim().replace(' ', '_'), start, end);
            default:
                return fromCsv(csv);
        }
    }

    private static Workload article(Preset preset, String title, LocalDate start, LocalDate end) throws IOException {
        String url = WIKI + "/per-article/en.wikipedia/all-access/user/" + Http.enc(title) + "/daily/" + start.format(D8) + "/" + end.format(D8);
        try {
            return fromWiki(preset, "Wikipedia article: " + title.replace('_', ' '), url, start, end);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("404"))
                throw new IOException("Wikipedia has no traffic data for '" + title.replace('_', ' ') + "'. Check the exact article title.");
            throw e;
        }
    }

    private static Workload fromWiki(Preset preset, String name, String url, LocalDate start, LocalDate end) throws IOException {
        Http.Fetched f = Http.get(url, TTL);
        Map<String, Object> root = Json.obj(Json.parse(f.body));
        Map<String, Double> byDay = new HashMap<>();
        for (Object o : Json.arr(root.get("items"))) {
            Map<String, Object> it = Json.obj(o);
            byDay.put(Json.str(it, "timestamp").substring(0, 8), Json.num(it, "views"));
        }
        Workload w = new Workload();
        w.preset = preset; w.name = name; w.sourceUrl = url;
        w.source = "Wikimedia Pageviews REST API (real measured user traffic)";
        w.fetchedAt = f.fetchedAt.toString(); w.stale = f.stale;
        w.values = new double[DAYS];
        for (int d = 0; d < DAYS; d++) {
            LocalDate day = start.plusDays(d);
            w.dates.add(day.toString());
            w.values[d] = byDay.getOrDefault(day.format(D8), 0.0); // Wikimedia omits zero-traffic days
        }
        classify(w);
        return w;
    }

    /** Looks at the top trending articles and picks the one with the sharpest spike. */
    private static String mostSpikyTrending(LocalDate day, LocalDate start) throws IOException {
        String url = WIKI + "/top/en.wikipedia/all-access/" + day.getYear() + "/" + String.format("%02d", day.getMonthValue()) + "/" + String.format("%02d", day.getDayOfMonth());
        Http.Fetched f = Http.get(url, TTL);
        List<Object> articles = Json.arr(Json.obj(Json.arr(Json.obj(Json.parse(f.body)).get("items")).get(0)).get("articles"));
        String best = null;
        double bestRatio = -1;
        int checked = 0;
        for (Object o : articles) {
            String title = Json.str(Json.obj(o), "article");
            if (title.contains(":") || title.equals("Main_Page") || title.startsWith(".")) continue;
            try {
                Workload w = article(Preset.SPIKY, title, start, day);
                double ratio = max(w.values) / Math.max(1, median(w.values));
                if (ratio > bestRatio) { bestRatio = ratio; best = title; }
            } catch (IOException ignored) { }
            if (++checked >= 6) break;
        }
        if (best == null) throw new IOException("Could not read trending Wikipedia articles");
        return best;
    }

    static Workload fromCsv(String csv) throws IOException {
        if (csv == null || csv.isBlank()) throw new IOException("Paste or load a CSV with rows of: date,value");
        Workload w = new Workload();
        w.preset = Preset.CSV; w.name = "Uploaded usage data"; w.sourceUrl = "";
        w.source = "User-supplied usage export (CSV)"; w.fetchedAt = java.time.Instant.now().toString();
        List<Double> vals = new ArrayList<>();
        for (String line : csv.split("\\r?\\n")) {
            String[] parts = line.trim().split("[,;\\t]");
            if (parts.length < 2) continue;
            try {
                double v = Double.parseDouble(parts[parts.length - 1].trim().replace("\"", ""));
                w.dates.add(parts[0].trim().replace("\"", ""));
                vals.add(v);
            } catch (NumberFormatException headerOrJunk) { /* skip header rows */ }
        }
        if (vals.size() < 7) throw new IOException("CSV needs at least 7 daily rows of: date,value (found " + vals.size() + ")");
        w.values = vals.stream().mapToDouble(Double::doubleValue).toArray();
        classify(w);
        return w;
    }

    /** Labels the real curve as Steady / Spiky / Seasonal from its statistics. */
    static void classify(Workload w) {
        double[] v = w.values;
        double mean = Arrays.stream(v).average().orElse(0);
        double sd = Math.sqrt(Arrays.stream(v).map(x -> (x - mean) * (x - mean)).average().orElse(0));
        double cv = mean == 0 ? 0 : sd / mean;
        double spike = max(v) / Math.max(1e-9, median(v));
        double weekly = autocorrelation(v, 7);
        if (spike >= 2.0) {
            w.detectedPattern = "Spiky";
            w.patternReason = String.format("Peak day is %.1fx the median day.", spike);
        } else if (weekly >= 0.35 || cv >= 0.12) {
            w.detectedPattern = "Seasonal";
            w.patternReason = String.format("Repeating weekly cycle (lag-7 autocorrelation %.2f, variation %.0f%%).", weekly, cv * 100);
        } else {
            w.detectedPattern = "Steady";
            w.patternReason = String.format("Low day-to-day variation (%.0f%%), peak only %.2fx the median.", cv * 100, spike);
        }
    }

    static double max(double[] v) { return Arrays.stream(v).max().orElse(0); }

    static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    static double autocorrelation(double[] v, int lag) {
        if (v.length <= lag) return 0;
        double mean = Arrays.stream(v).average().orElse(0);
        double num = 0, den = 0;
        for (int i = 0; i < v.length; i++) {
            den += (v[i] - mean) * (v[i] - mean);
            if (i + lag < v.length) num += (v[i] - mean) * (v[i + lag] - mean);
        }
        return den == 0 ? 0 : num / den;
    }
}
