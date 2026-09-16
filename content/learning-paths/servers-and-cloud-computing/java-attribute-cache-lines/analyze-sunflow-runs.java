import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Summarizes accepted three-variant Sunflow timing and memory runs. */
final class AnalyzeSunflowRuns {
    private static final List<String> VARIANTS = List.of(
            "baseline", "contended-default", "contended-64");

    private AnalyzeSunflowRuns() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].equals("--timing") || !args[2].equals("--memory")) {
            throw new IllegalArgumentException(
                    "usage: java analyze-sunflow-runs.java --timing timing-runs.csv --memory memory-runs.csv");
        }

        Map<String, List<TimingRun>> timings = readTimings(Path.of(args[1]));
        Map<String, List<MemoryRun>> memory = readMemory(Path.of(args[3]));
        validateBlocks(timings, "timing");
        validateBlocks(memory, "memory");

        for (String variant : VARIANTS) {
            printTiming(variant,
                    stats(timings.get(variant).stream().mapToDouble(TimingRun::seconds).toArray()));
        }
        printTimingComparison("contended-default", "baseline", timings);
        printTimingComparison("contended-64", "baseline", timings);
        printTimingComparison("contended-64", "contended-default", timings);

        for (String variant : VARIANTS) {
            List<MemoryRun> runs = memory.get(variant);
            Stats heap = stats(runs.stream().mapToDouble(MemoryRun::peakHeapKb).toArray());
            Stats rss = stats(runs.stream().mapToDouble(MemoryRun::maxRssKb).toArray());
            System.out.printf(Locale.ROOT,
                    "%s memory count=%d median_peak_heap_used_kb=%.2f median_max_rss_kb=%.2f%n",
                    variant, runs.size(), heap.median(), rss.median());
        }
        printMemoryComparison("contended-default", "baseline", memory);
        printMemoryComparison("contended-64", "baseline", memory);
        printMemoryComparison("contended-64", "contended-default", memory);
    }

    private static Map<String, List<TimingRun>> readTimings(Path path) throws Exception {
        Map<String, List<TimingRun>> result = emptyVariantMap();
        for (Map<String, String> row : readAcceptedRows(path)) {
            validatePadding(row, path);
            String variant = requireVariant(row, path);
            result.get(variant).add(new TimingRun(
                    integer(row, "block", path), integer(row, "order", path),
                    number(row, "elapsed_seconds", path)));
        }
        return result;
    }

    private static Map<String, List<MemoryRun>> readMemory(Path path) throws Exception {
        Map<String, List<MemoryRun>> result = emptyVariantMap();
        for (Map<String, String> row : readAcceptedRows(path)) {
            validatePadding(row, path);
            String variant = requireVariant(row, path);
            int samples = integer(row, "sample_count", path);
            if (samples <= 0) {
                throw new IllegalArgumentException(
                        "memory row has no heap samples in " + path + ": " + row);
            }
            result.get(variant).add(new MemoryRun(
                    integer(row, "block", path), integer(row, "order", path),
                    number(row, "peak_heap_used_kb", path),
                    number(row, "max_rss_kb", path)));
        }
        return result;
    }

    private static List<Map<String, String>> readAcceptedRows(Path path) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null) {
                throw new IllegalArgumentException("empty CSV: " + path);
            }
            List<String> columns = Arrays.asList(first.split(",", -1));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                if (values.length != columns.size()) {
                    throw new IllegalArgumentException(
                            "malformed CSV row in " + path + ": " + line);
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    row.put(columns.get(i), values[i]);
                }
                if (!"0".equals(required(row, "status", path))
                        || !isTrue(required(row, "accepted", path))
                        || !"image_check_passed".equals(required(row, "validation", path))) {
                    throw new IllegalArgumentException(
                            "unaccepted CSV row in " + path + ": " + line);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean isTrue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String requireVariant(Map<String, String> row, Path path) {
        String variant = required(row, "variant", path);
        if (!VARIANTS.contains(variant)) {
            throw new IllegalArgumentException("unknown variant in " + path + ": " + variant);
        }
        return variant;
    }

    private static void validatePadding(Map<String, String> row, Path path) {
        String variant = requireVariant(row, path);
        String mode = required(row, "padding_mode", path);
        int bytes = integer(row, "effective_padding_bytes", path);
        boolean valid = switch (variant) {
            case "baseline" -> mode.equals("none") && bytes == 0;
            case "contended-default" -> mode.equals("jvm-default") && bytes == 128;
            case "contended-64" -> mode.equals("explicit") && bytes == 64;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "unexpected padding metadata in " + path + ": " + row);
        }
    }

    private static <T extends BlockRun> void validateBlocks(
            Map<String, List<T>> variants, String label) {
        int expectedCount = -1;
        Map<Integer, Set<String>> variantsByBlock = new HashMap<>();
        Map<Integer, Set<Integer>> ordersByBlock = new HashMap<>();
        for (String variant : VARIANTS) {
            List<T> runs = variants.get(variant);
            if (runs == null || runs.isEmpty()) {
                throw new IllegalArgumentException(
                        "no accepted " + variant + " " + label + " runs");
            }
            if (expectedCount < 0) {
                expectedCount = runs.size();
            } else if (runs.size() != expectedCount) {
                throw new IllegalArgumentException("unequal " + label + " run counts");
            }
            for (T run : runs) {
                variantsByBlock.computeIfAbsent(
                        run.block(), ignored -> new LinkedHashSet<>()).add(variant);
                ordersByBlock.computeIfAbsent(
                        run.block(), ignored -> new LinkedHashSet<>()).add(run.order());
            }
        }
        if (variantsByBlock.size() != expectedCount) {
            throw new IllegalArgumentException("duplicate or missing " + label + " blocks");
        }
        for (int block : variantsByBlock.keySet()) {
            if (!variantsByBlock.get(block).equals(new LinkedHashSet<>(VARIANTS))
                    || !ordersByBlock.get(block).equals(Set.of(1, 2, 3))) {
                throw new IllegalArgumentException("incomplete " + label + " block " + block);
            }
        }
    }

    private static Stats stats(double[] unsorted) {
        double[] values = unsorted.clone();
        Arrays.sort(values);
        if (values.length == 0) {
            throw new IllegalArgumentException("cannot summarize an empty series");
        }
        double mean = Arrays.stream(values).average().orElseThrow();
        double variance = Arrays.stream(values)
                .map(value -> (value - mean) * (value - mean)).sum() / values.length;
        double p25 = percentile(values, 0.25);
        double p75 = percentile(values, 0.75);
        return new Stats(values.length, percentile(values, 0.5), Math.sqrt(variance),
                Math.sqrt(variance) / mean, p25, p75, p75 - p25);
    }

    private static double percentile(double[] sorted, double p) {
        double position = (sorted.length - 1) * p;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        return sorted[lower]
                + (position - lower) * (sorted[upper] - sorted[lower]);
    }

    private static void printTiming(String label, Stats stats) {
        System.out.printf(Locale.ROOT,
                "%s timing count=%d median_seconds=%.3f population_stdev_seconds=%.3f cv=%.4f p25_seconds=%.3f p75_seconds=%.3f iqr_seconds=%.3f%n",
                label, stats.count(), stats.median(), stats.stdev(), stats.cv(),
                stats.p25(), stats.p75(), stats.iqr());
    }

    private static void printTimingComparison(String candidate, String reference,
            Map<String, List<TimingRun>> timings) {
        double candidateMedian = stats(timings.get(candidate).stream()
                .mapToDouble(TimingRun::seconds).toArray()).median();
        double referenceMedian = stats(timings.get(reference).stream()
                .mapToDouble(TimingRun::seconds).toArray()).median();
        int wins = pairedWins(
                timings.get(candidate), timings.get(reference), TimingRun::seconds);
        System.out.printf(Locale.ROOT,
                "%s_vs_%s timing median_percent_change=%.2f%% paired_wins=%d/%d%n",
                candidate, reference, percentChange(candidateMedian, referenceMedian),
                wins, timings.get(candidate).size());
    }

    private static void printMemoryComparison(String candidate, String reference,
            Map<String, List<MemoryRun>> memory) {
        List<MemoryRun> candidateRuns = memory.get(candidate);
        List<MemoryRun> referenceRuns = memory.get(reference);
        double candidateHeap = stats(candidateRuns.stream()
                .mapToDouble(MemoryRun::peakHeapKb).toArray()).median();
        double referenceHeap = stats(referenceRuns.stream()
                .mapToDouble(MemoryRun::peakHeapKb).toArray()).median();
        double candidateRss = stats(candidateRuns.stream()
                .mapToDouble(MemoryRun::maxRssKb).toArray()).median();
        double referenceRss = stats(referenceRuns.stream()
                .mapToDouble(MemoryRun::maxRssKb).toArray()).median();
        System.out.printf(Locale.ROOT,
                "%s_vs_%s memory median_peak_heap_percent_change=%.2f%% median_max_rss_percent_change=%.2f%%%n",
                candidate, reference, percentChange(candidateHeap, referenceHeap),
                percentChange(candidateRss, referenceRss));
    }

    private static <T extends BlockRun> int pairedWins(
            List<T> candidate, List<T> reference, Value<T> value) {
        Map<Integer, Double> referenceByBlock = new HashMap<>();
        for (T run : reference) {
            referenceByBlock.put(run.block(), value.get(run));
        }
        int wins = 0;
        for (T run : candidate) {
            if (value.get(run) < referenceByBlock.get(run.block())) {
                wins++;
            }
        }
        return wins;
    }

    private static double percentChange(double candidate, double reference) {
        return (candidate / reference - 1.0) * 100.0;
    }

    private static String required(Map<String, String> row, String column, Path path) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "missing " + column + " in " + path + ": " + row);
        }
        return value;
    }

    private static int integer(Map<String, String> row, String column, Path path) {
        return Integer.parseInt(required(row, column, path));
    }

    private static double number(Map<String, String> row, String column, Path path) {
        return Double.parseDouble(required(row, column, path));
    }

    private static <T> Map<String, List<T>> emptyVariantMap() {
        Map<String, List<T>> result = new LinkedHashMap<>();
        for (String variant : VARIANTS) {
            result.put(variant, new ArrayList<>());
        }
        return result;
    }

    private interface BlockRun {
        int block();
        int order();
    }

    @FunctionalInterface
    private interface Value<T> {
        double get(T value);
    }

    private record TimingRun(int block, int order, double seconds)
            implements BlockRun { }

    private record MemoryRun(int block, int order, double peakHeapKb, double maxRssKb)
            implements BlockRun { }

    private record Stats(int count, double median, double stdev, double cv,
                         double p25, double p75, double iqr) { }
}
