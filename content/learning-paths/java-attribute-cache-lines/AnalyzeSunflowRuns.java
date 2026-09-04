import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Summarizes accepted baseline and fixed rows from run-sunflow-pairs.sh. */
public final class AnalyzeSunflowRuns {
    private AnalyzeSunflowRuns() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--input")) {
            throw new IllegalArgumentException("usage: java AnalyzeSunflowRuns.java --input runs.csv");
        }
        Map<String, List<Run>> variants = read(Path.of(args[1]));
        Stats baseline = stats(require(variants, "baseline"));
        Stats fixed = stats(require(variants, "fixed"));
        print("baseline", baseline);
        print("fixed", fixed);
        int pairedWins = pairedWins(variants.get("baseline"), variants.get("fixed"));
        double ratio = fixed.median() / baseline.median();
        System.out.printf(Locale.ROOT, "fixed_to_baseline_median_ratio=%.6f%n", ratio);
        System.out.printf(Locale.ROOT, "median_percent_change=%.3f%%%n", (ratio - 1.0) * 100.0);
        System.out.printf(Locale.ROOT, "fixed_paired_wins=%d/%d%n", pairedWins,
                Math.min(baseline.count(), fixed.count()));
    }

    private static Map<String, List<Run>> read(Path path) throws Exception {
        Map<String, List<Run>> result = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("empty CSV: " + path);
            List<String> columns = Arrays.asList(header.split(",", -1));
            int pairColumn = columns.indexOf("pair");
            int variantColumn = columns.indexOf("variant");
            int elapsedColumn = columns.indexOf("elapsed_seconds");
            int acceptedColumn = columns.indexOf("accepted");
            if (pairColumn < 0 || variantColumn < 0 || elapsedColumn < 0 || acceptedColumn < 0) {
                throw new IllegalArgumentException("missing required columns in " + path);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] row = line.split(",", -1);
                if (row.length != columns.size()) throw new IllegalArgumentException("malformed CSV row: " + line);
                if (!row[acceptedColumn].equals("1") && !row[acceptedColumn].equalsIgnoreCase("true")) continue;
                Run run = new Run(Integer.parseInt(row[pairColumn]), Double.parseDouble(row[elapsedColumn]));
                result.computeIfAbsent(row[variantColumn], ignored -> new ArrayList<>()).add(run);
            }
        }
        return result;
    }

    private static List<Run> require(Map<String, List<Run>> variants, String name) {
        List<Run> runs = variants.get(name);
        if (runs == null || runs.isEmpty()) throw new IllegalArgumentException("no accepted " + name + " runs");
        return runs;
    }

    private static Stats stats(List<Run> runs) {
        double[] values = runs.stream().mapToDouble(Run::seconds).sorted().toArray();
        double mean = Arrays.stream(values).average().orElseThrow();
        double variance = Arrays.stream(values).map(value -> (value - mean) * (value - mean)).sum() / values.length;
        double p25 = percentile(values, 0.25);
        double p75 = percentile(values, 0.75);
        return new Stats(values.length, percentile(values, 0.5), Math.sqrt(variance),
                Math.sqrt(variance) / mean, p25, p75, p75 - p25);
    }

    private static double percentile(double[] sorted, double p) {
        double position = (sorted.length - 1) * p;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }

    private static int pairedWins(List<Run> baseline, List<Run> fixed) {
        Map<Integer, Double> baselineByPair = new HashMap<>();
        for (Run run : baseline) baselineByPair.put(run.pair(), run.seconds());
        int wins = 0;
        for (Run run : fixed) {
            Double baselineTime = baselineByPair.get(run.pair());
            if (baselineTime != null && run.seconds() < baselineTime) wins++;
        }
        return wins;
    }

    private static void print(String label, Stats stats) {
        System.out.printf(Locale.ROOT,
                "%s count=%d median_seconds=%.6f population_stdev_seconds=%.6f cv=%.6f p25_seconds=%.6f p75_seconds=%.6f iqr_seconds=%.6f%n",
                label, stats.count(), stats.median(), stats.stdev(), stats.cv(),
                stats.p25(), stats.p75(), stats.iqr());
    }

    private record Run(int pair, double seconds) { }
    private record Stats(int count, double median, double stdev, double cv,
                         double p25, double p75, double iqr) { }
}
