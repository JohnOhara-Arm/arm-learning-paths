import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Classifies Java objects that overlap hot cache lines using JOL layouts.
 *
 * Run directly with JDK 21 or later:
 *
 *   java AnalyzeJolAdjacency.java --join cacheline_object_join.csv --jol-dir internals
 *
 * This file intentionally uses only the Java standard library.
 */
public final class AnalyzeJolAdjacency {
    private static final Pattern FIELD_LINE = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(.+?)\\s*$");
    private static final Pattern CLASS_HEADER = Pattern.compile("^([\\w.$\\[\\]/]+) object internals:");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*" +
            "([A-Za-z_][\\w.$]*(?:\\[\\])?)\\s+" +
            "([A-Za-z_][\\w]*(?:\\s*=\\s*[^,;]+)?(?:\\s*,\\s*[A-Za-z_][\\w]*(?:\\s*=\\s*[^,;]+)?)*)\\s*;");

    private static final List<String> FIELD_COLUMNS = List.of(
            "class_name", "declaring_class", "field_name", "field_type",
            "offset", "size", "offset_end", "source");
    private static final List<String> RANGE_COLUMNS = List.of(
            "address_domain", "phys_line", "c2c_index", "peer_total", "records",
            "object_address", "object_size", "object_overlap_start", "object_overlap_end",
            "line_overlap_start", "line_overlap_end", "overlap_size", "class_name",
            "jol_overlapping_fields");
    private static final List<String> SUMMARY_COLUMNS = List.of(
            "line_address", "classification", "object_count", "classes", "peer_total",
            "records", "boundary_pairs", "candidate_parent_classes");

    private AnalyzeJolAdjacency() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Path joinCsv = findJoinCsv(config.join());
        Files.createDirectories(config.output());

        List<JolField> layouts = loadLayouts(config.jolDir(), config.jolCsv());
        Map<String, List<JolField>> fieldsByClass = new HashMap<>();
        for (JolField field : layouts) {
            fieldsByClass.computeIfAbsent(field.className(), ignored -> new ArrayList<>()).add(field);
        }
        List<SourceField> sourceFields = scanSourceFields(config.sourceRoot());
        List<ObjectOverlap> overlaps = loadJoinRows(joinCsv, config.cacheLineSize());
        List<ObjectOverlap> enriched = new ArrayList<>(overlaps.size());
        for (ObjectOverlap overlap : overlaps) {
            enriched.add(overlap.withFields(overlappingFields(overlap, fieldsByClass)));
        }
        List<LineSummary> summaries = classify(enriched, sourceFields);

        String runId = sanitize(config.runId() == null
                ? removeSuffix(joinCsv.getFileName().toString(), "_cacheline_object_join.csv")
                : config.runId());
        Path fieldCsv = config.output().resolve(runId + "_jol_field_layout.csv");
        Path rangesCsv = config.output().resolve(runId + "_hot_cacheline_object_ranges.csv");
        Path summaryCsv = config.output().resolve(runId + "_hot_cacheline_adjacency_summary.csv");
        Path note = config.output().resolve(runId + "_jol_cacheline_adjacency.md");

        writeFields(fieldCsv, layouts);
        writeRanges(rangesCsv, enriched);
        writeSummaries(summaryCsv, summaries);
        writeNote(note, runId, summaries, enriched, layouts.size(), sourceFields.size());

        System.out.println(fieldCsv);
        System.out.println(rangesCsv);
        System.out.println(summaryCsv);
        System.out.println(note);
    }

    private static Path findJoinCsv(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) {
            return path;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith("_cacheline_object_join.csv"))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no *_cacheline_object_join.csv found under " + path));
        }
    }

    private static List<JolField> loadLayouts(Path jolDir, Path jolCsv) throws IOException {
        List<JolField> rows = new ArrayList<>();
        if (jolCsv != null && Files.isRegularFile(jolCsv)) {
            try (CsvReader csv = new CsvReader(jolCsv)) {
                Map<String, String> row;
                while ((row = csv.readRow()) != null) {
                    try {
                        long offset = parseInteger(required(row, "offset"));
                        long size = parseInteger(row.getOrDefault("size", "1"));
                        rows.add(new JolField(
                                value(row, "class_name"), value(row, "declaring_class"),
                                value(row, "field_name"), value(row, "field_type"),
                                offset, size, offset + size,
                                row.getOrDefault("source", jolCsv.toString())));
                    } catch (IllegalArgumentException ignored) {
                        // Skip incomplete optional layout rows.
                    }
                }
            }
        }
        if (jolDir != null && Files.isDirectory(jolDir)) {
            try (Stream<Path> paths = Files.list(jolDir)) {
                for (Path path : paths
                        .filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".txt"))
                        .sorted().toList()) {
                    rows.addAll(parseJolInternals(path));
                }
            }
        }
        return rows;
    }

    private static List<JolField> parseJolInternals(Path path) throws IOException {
        List<JolField> rows = new ArrayList<>();
        String currentClass = "";
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Matcher header = CLASS_HEADER.matcher(raw.trim());
            if (header.find()) {
                currentClass = header.group(1).replace('/', '.');
                continue;
            }
            if (currentClass.isEmpty() || raw.stripLeading().startsWith("OFF") || raw.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher match = FIELD_LINE.matcher(raw);
            if (!match.matches()) {
                continue;
            }
            long offset = Long.parseLong(match.group(1));
            long size = Long.parseLong(match.group(2));
            String rest = match.group(3);
            if (rest.startsWith("(")) {
                continue;
            }
            String[] parts = rest.split("\\s+");
            if (parts.length < 2 || !parts[1].contains(".")) {
                continue;
            }
            int lastDot = parts[1].lastIndexOf('.');
            rows.add(new JolField(
                    currentClass, parts[1].substring(0, lastDot).replace('/', '.'),
                    parts[1].substring(lastDot + 1), parts[0].replace('/', '.'),
                    offset, size, offset + size, path.toString()));
        }
        return rows;
    }

    private static List<SourceField> scanSourceFields(Path sourceRoot) throws IOException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        List<SourceField> rows = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted().toList()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                String packageName = "";
                Map<String, String> imports = new HashMap<>();
                for (String line : lines) {
                    Matcher packageMatch = PACKAGE.matcher(line);
                    if (packageMatch.find()) {
                        packageName = packageMatch.group(1);
                    }
                    Matcher importMatch = IMPORT.matcher(line);
                    if (importMatch.find()) {
                        String full = importMatch.group(1);
                        imports.put(simpleName(full), full);
                    }
                }
                rows.addAll(scanJavaFile(path, lines, packageName, imports));
            }
        }
        return rows;
    }

    private static List<SourceField> scanJavaFile(
            Path path, List<String> lines, String packageName, Map<String, String> imports) {
        List<SourceField> rows = new ArrayList<>();
        Deque<ClassScope> classes = new ArrayDeque<>();
        int braceDepth = 0;
        for (String line : lines) {
            Matcher classMatch = CLASS.matcher(line);
            if (classMatch.find()) {
                String name = classMatch.group(2);
                classes.addLast(new ClassScope(name, braceDepth + count(line, '{') - count(line, '}')));
            } else if (!classes.isEmpty()) {
                Matcher fieldMatch = FIELD.matcher(line);
                if (fieldMatch.matches()) {
                    String rawType = fieldMatch.group(1);
                    String simple = sourceSimpleName(rawType);
                    String fullType = imports.getOrDefault(simple,
                            rawType.contains(".") ? rawType : joinName(packageName, rawType));
                    String owner = ownerName(packageName, classes);
                    for (String rawName : fieldMatch.group(2).split(",")) {
                        String fieldName = rawName.split("=", 2)[0].trim();
                        rows.add(new SourceField(owner, fieldName, fullType, simple, path.toString()));
                    }
                }
            }
            braceDepth += count(line, '{') - count(line, '}');
            while (!classes.isEmpty() && braceDepth < classes.peekLast().bodyDepth()) {
                classes.removeLast();
            }
        }
        return rows;
    }

    private static String ownerName(String packageName, Deque<ClassScope> classes) {
        return joinName(packageName, String.join("$", classes.stream().map(ClassScope::name).toList()));
    }

    private static int count(String value, char wanted) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == wanted) {
                count++;
            }
        }
        return count;
    }

    private static List<ObjectOverlap> loadJoinRows(Path joinCsv, int lineSize) throws IOException {
        List<ObjectOverlap> rows = new ArrayList<>();
        try (CsvReader csv = new CsvReader(joinCsv)) {
            Map<String, String> row;
            while ((row = csv.readRow()) != null) {
                try {
                    long line = parseInteger(required(row, "phys_line"));
                    long address = parseInteger(required(row, "object_address"));
                    long size = parseInteger(required(row, "object_size"));
                    long end = address + size;
                    long overlapStart = Math.max(line, address);
                    long overlapEnd = Math.min(line + lineSize, end);
                    if (overlapStart >= overlapEnd) {
                        continue;
                    }
                    rows.add(new ObjectOverlap(
                            value(row, "address_domain"), line,
                            parseLongDefault(row, "c2c_index"), parseLongDefault(row, "peer_total"),
                            parseLongDefault(row, "records"), address, size, end,
                            overlapStart - address, overlapEnd - address,
                            overlapStart - line, overlapEnd - line, overlapEnd - overlapStart,
                            value(row, "class_name"), ""));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed join rows.
                }
            }
        }
        rows.sort(Comparator.comparingLong(ObjectOverlap::lineAddress)
                .thenComparingLong(ObjectOverlap::objectAddress));
        return rows;
    }

    private static String overlappingFields(
            ObjectOverlap object, Map<String, List<JolField>> fieldsByClass) {
        List<JolField> fields = fieldsByClass.get(object.className());
        if (fields == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JolField field : fields) {
            if (field.offset() < object.objectOverlapEnd() && field.offsetEnd() > object.objectOverlapStart()) {
                names.add(field.declaringClass() + "." + field.fieldName() + ":" +
                        field.fieldType() + "@" + field.offset() + "+" + field.size());
            }
        }
        return String.join(";", names);
    }

    private static List<LineSummary> classify(
            List<ObjectOverlap> rows, List<SourceField> sourceFields) {
        Map<Long, List<ObjectOverlap>> byLine = new TreeMap<>();
        for (ObjectOverlap row : rows) {
            byLine.computeIfAbsent(row.lineAddress(), ignored -> new ArrayList<>()).add(row);
        }
        List<LineSummary> summaries = new ArrayList<>();
        for (Map.Entry<Long, List<ObjectOverlap>> entry : byLine.entrySet()) {
            List<ObjectOverlap> lineRows = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(ObjectOverlap::objectAddress)).toList();
            Set<String> classes = new LinkedHashSet<>();
            Set<Long> objects = new HashSet<>();
            List<String> boundaries = new ArrayList<>();
            long peerTotal = 0;
            long records = 0;
            boolean hasFields = false;
            for (int i = 0; i < lineRows.size(); i++) {
                ObjectOverlap row = lineRows.get(i);
                classes.add(row.className());
                objects.add(row.objectAddress());
                peerTotal = Math.max(peerTotal, row.peerTotal());
                records += row.records();
                hasFields |= !row.jolFields().isBlank();
                if (i + 1 < lineRows.size() && row.objectEnd() == lineRows.get(i + 1).objectAddress()) {
                    boundaries.add(row.className() + " -> " + lineRows.get(i + 1).className());
                }
            }
            String classification = !boundaries.isEmpty()
                    ? "object_boundary_allocation_adjacency"
                    : objects.size() > 1
                    ? "inter_object_allocation_adjacency"
                    : hasFields ? "intra_object_field_overlap" : "single_object_no_jol_field_overlap";
            summaries.add(new LineSummary(
                    entry.getKey(), classification, objects.size(),
                    String.join(";", classes.stream().sorted().toList()), peerTotal, records,
                    String.join(";", boundaries), candidateParents(classes, sourceFields)));
        }
        summaries.sort(Comparator.comparingLong(LineSummary::peerTotal).reversed()
                .thenComparingLong(LineSummary::lineAddress));
        return summaries;
    }

    private static String candidateParents(Set<String> classes, List<SourceField> sourceFields) {
        Map<String, Set<String>> hits = new HashMap<>();
        for (SourceField field : sourceFields) {
            for (String target : classes) {
                if (field.fieldType().equals(target)) {
                    hits.computeIfAbsent(field.ownerClass(), ignored -> new HashSet<>()).add(target);
                }
            }
        }
        return String.join(";", hits.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .map(Map.Entry::getKey).sorted().toList());
    }

    private static void writeFields(Path path, List<JolField> rows) throws IOException {
        try (CsvWriter csv = new CsvWriter(path, FIELD_COLUMNS)) {
            for (JolField row : rows) {
                csv.write(List.of(row.className(), row.declaringClass(), row.fieldName(), row.fieldType(),
                        Long.toString(row.offset()), Long.toString(row.size()),
                        Long.toString(row.offsetEnd()), row.source()));
            }
        }
    }

    private static void writeRanges(Path path, List<ObjectOverlap> rows) throws IOException {
        try (CsvWriter csv = new CsvWriter(path, RANGE_COLUMNS)) {
            for (ObjectOverlap row : rows) {
                csv.write(List.of(row.domain(), hex(row.lineAddress()), Long.toString(row.c2cIndex()),
                        Long.toString(row.peerTotal()), Long.toString(row.records()), hex(row.objectAddress()),
                        Long.toString(row.objectSize()), Long.toString(row.objectOverlapStart()),
                        Long.toString(row.objectOverlapEnd()), Long.toString(row.lineOverlapStart()),
                        Long.toString(row.lineOverlapEnd()), Long.toString(row.overlapSize()),
                        row.className(), row.jolFields()));
            }
        }
    }

    private static void writeSummaries(Path path, List<LineSummary> rows) throws IOException {
        try (CsvWriter csv = new CsvWriter(path, SUMMARY_COLUMNS)) {
            for (LineSummary row : rows) {
                csv.write(List.of(hex(row.lineAddress()), row.classification(),
                        Integer.toString(row.objectCount()), row.classes(), Long.toString(row.peerTotal()),
                        Long.toString(row.records()), row.boundaryPairs(), row.candidateParents()));
            }
        }
    }

    private static void writeNote(
            Path path, String runId, List<LineSummary> summaries, List<ObjectOverlap> overlaps,
            int jolCount, int sourceCount) throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "# JOL cache-line adjacency analysis", "", "Run id: `" + runId + "`", "",
                "## Summary", "", "- JOL field rows loaded: " + jolCount,
                "- Source field rows scanned: " + sourceCount,
                "- Hot cache lines classified: " + summaries.size(), "",
                "## Top cache-line classifications", "",
                "| Cache line | Classification | Peer hits | Objects/classes | Boundary pairs | Candidate parent classes |",
                "|---|---|---:|---|---|---|"));
        for (LineSummary row : summaries.stream().limit(15).toList()) {
            lines.add("| `" + hex(row.lineAddress()) + "` | " + row.classification() + " | " +
                    row.peerTotal() + " | " + row.objectCount() + " / `" + row.classes() + "` | `" +
                    row.boundaryPairs() + "` | `" + row.candidateParents() + "` |");
        }
        lines.addAll(List.of("", "## Interpretation rule", "",
                "Multiple Java object bodies on one cache line prove heap-placement adjacency, not parent ownership. " +
                "JOL identifies fields inside an object; reference-edge or allocation tracing is required to prove ownership or allocation order.",
                "", "## Top object overlaps", "",
                "| Cache line | Object | Address | Object bytes on line | Line bytes | JOL overlapping fields |",
                "|---|---|---|---:|---:|---|"));
        for (ObjectOverlap row : overlaps.stream().limit(25).toList()) {
            lines.add("| `" + hex(row.lineAddress()) + "` | `" + row.className() + "` | `" +
                    hex(row.objectAddress()) + "` | " + row.objectOverlapStart() + ".." +
                    row.objectOverlapEnd() + " | " + row.lineOverlapStart() + ".." +
                    row.lineOverlapEnd() + " | `" + row.jolFields() + "` |");
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static long parseLongDefault(Map<String, String> row, String name) {
        String value = row.get(name);
        return value == null || value.isBlank() ? 0 : parseInteger(value);
    }

    private static long parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing integer");
        }
        String value = raw.trim();
        return value.startsWith("0x") || value.startsWith("0X")
                ? Long.parseUnsignedLong(value.substring(2), 16) : Long.parseLong(value);
    }

    private static String required(Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing CSV column " + name);
        }
        return value;
    }

    private static String value(Map<String, String> row, String name) {
        return row.getOrDefault(name, "").trim();
    }

    private static String simpleName(String value) {
        return value.substring(value.lastIndexOf('.') + 1);
    }

    private static String sourceSimpleName(String value) {
        String cleaned = value.replace("[]", "").replace('$', '.');
        return simpleName(cleaned);
    }

    private static String joinName(String left, String right) {
        return left.isBlank() ? right : left + "." + right;
    }

    private static String removeSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private static String hex(long value) {
        return "0x" + Long.toUnsignedString(value, 16);
    }

    private record Config(
            Path join, Path output, Path jolDir, Path jolCsv,
            Path sourceRoot, int cacheLineSize, String runId) {
        static Config parse(String[] args) {
            Path join = null;
            Path output = null;
            Path jolDir = null;
            Path jolCsv = null;
            Path sourceRoot = null;
            int lineSize = 64;
            String runId = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--join" -> join = Path.of(requireArg(args, ++i, "--join"));
                    case "--output" -> output = Path.of(requireArg(args, ++i, "--output"));
                    case "--jol-dir" -> jolDir = Path.of(requireArg(args, ++i, "--jol-dir"));
                    case "--jol-csv" -> jolCsv = Path.of(requireArg(args, ++i, "--jol-csv"));
                    case "--source-root" -> sourceRoot = Path.of(requireArg(args, ++i, "--source-root"));
                    case "--cache-line-size" -> lineSize = Integer.parseInt(requireArg(args, ++i, "--cache-line-size"));
                    case "--run-id" -> runId = requireArg(args, ++i, "--run-id");
                    case "-h", "--help" -> throw usage(null);
                    default -> throw usage("unknown argument: " + args[i]);
                }
            }
            if (join == null) {
                throw usage("--join is required");
            }
            if (output == null) {
                output = Files.isDirectory(join) ? join.resolve("analysis") : join.toAbsolutePath().getParent();
            }
            if (lineSize <= 0 || (lineSize & (lineSize - 1)) != 0) {
                throw usage("cache-line size must be a positive power of two");
            }
            return new Config(join, output, jolDir, jolCsv, sourceRoot, lineSize, runId);
        }

        private static String requireArg(String[] args, int index, String option) {
            if (index >= args.length) {
                throw usage(option + " requires a value");
            }
            return args[index];
        }

        private static IllegalArgumentException usage(String message) {
            String prefix = message == null ? "" : message + System.lineSeparator();
            return new IllegalArgumentException(prefix +
                    "usage: java AnalyzeJolAdjacency.java --join CSV_OR_DIR [--output DIR] " +
                    "[--jol-dir DIR] [--jol-csv FILE] [--source-root DIR] " +
                    "[--cache-line-size 64] [--run-id ID]");
        }
    }

    private static final class CsvReader implements AutoCloseable {
        private final PushbackReader reader;
        private final List<String> header;

        CsvReader(Path path) throws IOException {
            Reader base = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            reader = new PushbackReader(base, 1);
            header = readRecord();
            if (header == null) {
                throw new IOException("empty CSV: " + path);
            }
        }

        Map<String, String> readRow() throws IOException {
            List<String> values = readRecord();
            if (values == null) {
                return null;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < values.size() ? values.get(i) : "");
            }
            return row;
        }

        private List<String> readRecord() throws IOException {
            List<String> values = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean sawData = false;
            int current;
            while ((current = reader.read()) != -1) {
                sawData = true;
                char ch = (char) current;
                if (quoted) {
                    if (ch == '"') {
                        int next = reader.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            quoted = false;
                            if (next != -1) reader.unread(next);
                        }
                    } else {
                        field.append(ch);
                    }
                } else if (ch == '"' && field.length() == 0) {
                    quoted = true;
                } else if (ch == ',') {
                    values.add(field.toString());
                    field.setLength(0);
                } else if (ch == '\n') {
                    values.add(field.toString());
                    return values;
                } else if (ch != '\r') {
                    field.append(ch);
                }
            }
            if (!sawData && values.isEmpty() && field.length() == 0) return null;
            values.add(field.toString());
            return values;
        }

        public void close() throws IOException { reader.close(); }
    }

    private static final class CsvWriter implements AutoCloseable {
        private final BufferedWriter writer;

        CsvWriter(Path path, List<String> header) throws IOException {
            Files.createDirectories(path.toAbsolutePath().getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            write(header);
        }

        void write(List<String> values) throws IOException {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) writer.write(',');
                String value = values.get(i) == null ? "" : values.get(i);
                boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 ||
                        value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
                if (quote) {
                    writer.write('"');
                    writer.write(value.replace("\"", "\"\""));
                    writer.write('"');
                } else {
                    writer.write(value);
                }
            }
            writer.newLine();
        }

        public void close() throws IOException { writer.close(); }
    }

    private record JolField(
            String className, String declaringClass, String fieldName, String fieldType,
            long offset, long size, long offsetEnd, String source) {}
    private record SourceField(
            String ownerClass, String fieldName, String fieldType, String simpleType, String source) {}
    private record ClassScope(String name, int bodyDepth) {}
    private record ObjectOverlap(
            String domain, long lineAddress, long c2cIndex, long peerTotal, long records,
            long objectAddress, long objectSize, long objectEnd,
            long objectOverlapStart, long objectOverlapEnd,
            long lineOverlapStart, long lineOverlapEnd, long overlapSize,
            String className, String jolFields) {
        ObjectOverlap withFields(String value) {
            return new ObjectOverlap(domain, lineAddress, c2cIndex, peerTotal, records,
                    objectAddress, objectSize, objectEnd, objectOverlapStart, objectOverlapEnd,
                    lineOverlapStart, lineOverlapEnd, overlapSize, className, value);
        }
    }
    private record LineSummary(
            long lineAddress, String classification, int objectCount, String classes,
            long peerTotal, long records, String boundaryPairs, String candidateParents) {}
}
