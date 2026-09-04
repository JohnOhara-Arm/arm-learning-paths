import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Joins perf c2c cache-line addresses to Java heap object ranges.
 *
 * Run directly with JDK 21 or later:
 *
 *   java AnalyzeJavaCachelines.java --collection RUN --output RESULTS
 *
 * This file intentionally uses only the Java standard library.
 */
public final class AnalyzeJavaCachelines {
    private static final Pattern C2C_ROW = Pattern.compile(
            "^\\s*(\\d+)\\s+(0x[0-9a-fA-F]+)\\s+(\\d+)\\s+" +
            "(\\d+)\\s+([0-9.]+)%\\s+(\\d+)\\s+(\\d+)\\s+" +
            "(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");

    private static final List<String> CACHELINE_COLUMNS = List.of(
            "index", "phys_line", "node", "pa_cnt", "peer_snoop_percent",
            "peer_total", "peer_local", "peer_remote", "records", "loads", "stores");
    private static final List<String> JOIN_COLUMNS = List.of(
            "address_domain", "phys_line", "c2c_index", "peer_total", "peer_local",
            "records", "loads", "stores", "object_address", "object_size",
            "object_line_offset", "class_name", "object_id", "overlapping_fields");

    private AnalyzeJavaCachelines() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        CollectionFiles collection = findCollection(config.collection());
        Files.createDirectories(config.output());

        List<CacheLine> cacheLines = parseCacheLines(collection.c2cReport(), config.cacheLineSize());
        if (cacheLines.isEmpty()) {
            throw new IllegalArgumentException("no shared cache-line rows found in " + collection.c2cReport());
        }

        Map<Long, CacheLine> wanted = new HashMap<>();
        for (CacheLine line : cacheLines) {
            wanted.put(line.address(), line);
        }

        List<Page> pages = loadPages(collection.base().resolve("snapshot/pagemap-heap.csv"));
        Map<Long, Page> pageByAddress = new HashMap<>();
        for (Page page : pages) {
            pageByAddress.put(page.virtualStart(), page);
        }
        Map<String, List<FieldLayout>> fields = loadFieldLayouts(
                collection.base().resolve("heap/field-layout.csv"));

        Path objectCsv = collection.base().resolve("heap/heap-objects.csv");
        List<JoinRow> virtualMatches = new ArrayList<>();
        List<JoinRow> physicalMatches = new ArrayList<>();
        long objectCount = scanObjects(
                objectCsv, wanted, pages, pageByAddress, fields, config,
                virtualMatches, physicalMatches);

        List<JoinRow> selected = selectMatches(config.addressDomain(), virtualMatches, physicalMatches);
        selected.sort(Comparator
                .comparingLong(JoinRow::peerTotal).reversed()
                .thenComparing(JoinRow::domain)
                .thenComparingLong(JoinRow::lineAddress)
                .thenComparingLong(JoinRow::objectAddress));

        String runId = sanitize(config.runId() == null ? collection.base().getFileName().toString() : config.runId());
        Path cachelineCsv = config.output().resolve(runId + "_cachelines.csv");
        Path joinCsv = config.output().resolve(runId + "_cacheline_object_join.csv");
        Path note = config.output().resolve(runId + "_java_heap_cacheline_attribution.md");
        writeCacheLines(cachelineCsv, cacheLines);
        writeJoins(joinCsv, selected);
        writeNote(note, collection.base(), cachelineCsv, joinCsv, selected, objectCount,
                virtualMatches.size(), physicalMatches.size(), config.addressDomain());

        System.out.println(cachelineCsv);
        System.out.println(joinCsv);
        System.out.println(note);
        System.out.printf(Locale.ROOT,
                "objects_scanned=%d virtual_matches=%d physical_matches=%d selected=%d%n",
                objectCount, virtualMatches.size(), physicalMatches.size(), selected.size());
    }

    private static List<JoinRow> selectMatches(
            AddressDomain requested, List<JoinRow> virtualMatches, List<JoinRow> physicalMatches) {
        if (requested == AddressDomain.VIRTUAL) {
            return virtualMatches;
        }
        if (requested == AddressDomain.PHYSICAL) {
            return physicalMatches;
        }
        if (!virtualMatches.isEmpty() && !physicalMatches.isEmpty()) {
            throw new IllegalStateException(
                    "address domain is ambiguous: both virtual and physical joins produced matches; " +
                    "rerun with --address-domain virtual or --address-domain physical after inspecting the evidence");
        }
        return virtualMatches.isEmpty() ? physicalMatches : virtualMatches;
    }

    private static CollectionFiles findCollection(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        Path nested = path.resolve("c2c/c2c-report.txt");
        if (Files.isRegularFile(nested)) {
            return new CollectionFiles(path, nested);
        }
        Path direct = path.resolve("c2c-report.txt");
        if (Files.isRegularFile(direct)) {
            return new CollectionFiles(path, direct);
        }
        try (Stream<Path> paths = Files.walk(path)) {
            List<Path> candidates = paths
                    .filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().equals("c2c-report.txt"))
                    .sorted()
                    .toList();
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("no c2c-report.txt found under " + path);
            }
            Path report = candidates.get(0);
            Path parent = report.getParent();
            Path base = parent.getFileName().toString().equals("c2c") ? parent.getParent() : parent;
            return new CollectionFiles(base, report);
        }
    }

    private static List<CacheLine> parseCacheLines(Path report, int lineSize) throws IOException {
        List<CacheLine> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher match = C2C_ROW.matcher(line);
                if (!match.find()) {
                    continue;
                }
                long address = lineBase(parseAddress(match.group(2)), lineSize);
                rows.add(new CacheLine(
                        Integer.parseInt(match.group(1)), address,
                        Integer.parseInt(match.group(3)), Long.parseLong(match.group(4)),
                        Double.parseDouble(match.group(5)), Long.parseLong(match.group(6)),
                        Long.parseLong(match.group(7)), Long.parseLong(match.group(8)),
                        Long.parseLong(match.group(9)), Long.parseLong(match.group(10)),
                        Long.parseLong(match.group(11))));
            }
        }
        return rows;
    }

    private static List<Page> loadPages(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<Page> rows = new ArrayList<>();
        try (CsvReader csv = new CsvReader(path)) {
            Map<String, String> row;
            while ((row = csv.readRow()) != null) {
                String present = value(row, "present");
                if (!(present.equals("1") || present.equalsIgnoreCase("true"))) {
                    continue;
                }
                try {
                    rows.add(new Page(
                            parseAddress(required(row, "vaddr_start")),
                            parseAddress(required(row, "vaddr_end")),
                            parseAddress(required(row, "pfn")),
                            parseAddress(required(row, "phys_start")),
                            value(row, "mapping")));
                } catch (IllegalArgumentException ignored) {
                    // A restricted or malformed pagemap row cannot support an exact physical join.
                }
            }
        }
        rows.sort(Comparator.comparingLong(Page::virtualStart));
        return rows;
    }

    private static Map<String, List<FieldLayout>> loadFieldLayouts(Path path) throws IOException {
        Map<String, List<FieldLayout>> layouts = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return layouts;
        }
        try (CsvReader csv = new CsvReader(path)) {
            Map<String, String> row;
            while ((row = csv.readRow()) != null) {
                try {
                    FieldLayout field = new FieldLayout(
                            value(row, "field_name"), value(row, "field_type"),
                            parseAddress(required(row, "offset")),
                            parseAddress(row.getOrDefault("size", "1")));
                    layouts.computeIfAbsent(value(row, "class_name"), ignored -> new ArrayList<>()).add(field);
                } catch (IllegalArgumentException ignored) {
                    // Skip incomplete optional layout rows.
                }
            }
        }
        return layouts;
    }

    private static long scanObjects(
            Path path,
            Map<Long, CacheLine> wanted,
            List<Page> pages,
            Map<Long, Page> pageByAddress,
            Map<String, List<FieldLayout>> fields,
            Config config,
            List<JoinRow> virtualMatches,
            List<JoinRow> physicalMatches) throws IOException {
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        Set<MatchKey> seen = new HashSet<>();
        long count = 0;
        try (CsvReader csv = new CsvReader(path)) {
            Map<String, String> row;
            while ((row = csv.readRow()) != null) {
                ObjectRange object;
                try {
                    long address = parseAddress(first(row, "object_address", "address"));
                    long size = parseAddress(first(row, "size", "object_size"));
                    if (size <= 0) {
                        continue;
                    }
                    object = new ObjectRange(
                            address, address + size, size,
                            first(row, "class_name", "klass", "type"), value(row, "object_id"));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                count++;

                if (config.addressDomain() != AddressDomain.PHYSICAL) {
                    for (Map.Entry<Long, Long> entry : virtualLineOffsets(object, config.cacheLineSize()).entrySet()) {
                        CacheLine line = wanted.get(entry.getKey());
                        if (line != null) {
                            addMatch(virtualMatches, seen, "virtual", line, entry.getValue(), object, "");
                        }
                    }
                }
                if (config.addressDomain() != AddressDomain.VIRTUAL && !pageByAddress.isEmpty()) {
                    for (Map.Entry<Long, Long> entry : physicalLineOffsets(
                            object, pageByAddress, config.pageSize(), config.cacheLineSize()).entrySet()) {
                        CacheLine line = wanted.get(entry.getKey());
                        if (line != null) {
                            String overlaps = overlappingFields(
                                    object, line.address(), pages, fields,
                                    config.pageSize(), config.cacheLineSize());
                            addMatch(physicalMatches, seen, "physical", line, entry.getValue(), object, overlaps);
                        }
                    }
                }
            }
        }
        return count;
    }

    private static void addMatch(
            List<JoinRow> rows, Set<MatchKey> seen, String domain, CacheLine line,
            long offset, ObjectRange object, String overlaps) {
        MatchKey key = new MatchKey(domain, line.address(), object.address());
        if (!seen.add(key)) {
            return;
        }
        rows.add(new JoinRow(
                domain, line.address(), line.index(), line.peerTotal(), line.peerLocal(),
                line.records(), line.loads(), line.stores(), object.address(), object.size(),
                offset, object.className(), object.objectId(), overlaps));
    }

    private static Map<Long, Long> virtualLineOffsets(ObjectRange object, int lineSize) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (long line = lineBase(object.address(), lineSize); line < object.end(); line += lineSize) {
            result.put(line, Math.max(0, line - object.address()));
        }
        return result;
    }

    private static Map<Long, Long> physicalLineOffsets(
            ObjectRange object, Map<Long, Page> pages, int pageSize, int lineSize) {
        Map<Long, Long> result = new LinkedHashMap<>();
        long firstPage = lineBase(object.address(), pageSize);
        for (long pageAddress = firstPage; pageAddress < object.end(); pageAddress += pageSize) {
            Page page = pages.get(pageAddress);
            if (page == null) {
                continue;
            }
            long overlapStart = Math.max(object.address(), pageAddress);
            long overlapEnd = Math.min(object.end(), page.virtualEnd());
            if (overlapStart >= overlapEnd) {
                continue;
            }
            long physicalPage = page.pfn() * pageSize;
            long firstLine = lineBase(physicalPage + overlapStart - page.virtualStart(), lineSize);
            long lastLine = lineBase(physicalPage + overlapEnd - 1 - page.virtualStart(), lineSize);
            for (long line = firstLine; line <= lastLine; line += lineSize) {
                long virtualLine = page.virtualStart() + line - physicalPage;
                result.putIfAbsent(line, Math.max(0, virtualLine - object.address()));
            }
        }
        return result;
    }

    private static String overlappingFields(
            ObjectRange object, long physicalLine, List<Page> pages,
            Map<String, List<FieldLayout>> layouts, int pageSize, int lineSize) {
        List<FieldLayout> fields = layouts.get(object.className());
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        List<LongRange> virtualRanges = new ArrayList<>();
        for (Page page : pages) {
            long physicalPage = page.pfn() * pageSize;
            long physicalEnd = physicalPage + pageSize;
            if (physicalEnd <= physicalLine || physicalPage >= physicalLine + lineSize) {
                continue;
            }
            long start = page.virtualStart() + Math.max(0, physicalLine - physicalPage);
            long end = page.virtualStart() + Math.min(pageSize, physicalLine + lineSize - physicalPage);
            start = Math.max(start, object.address());
            end = Math.min(end, object.end());
            if (start < end) {
                virtualRanges.add(new LongRange(start, end));
            }
        }
        List<String> names = new ArrayList<>();
        for (FieldLayout field : fields) {
            long start = object.address() + field.offset();
            long end = start + field.size();
            boolean overlaps = virtualRanges.stream().anyMatch(range -> start < range.end() && end > range.start());
            if (overlaps) {
                names.add(field.name() + ":" + field.type() + "@" + field.offset());
            }
        }
        return String.join(";", names);
    }

    private static void writeCacheLines(Path path, List<CacheLine> rows) throws IOException {
        try (CsvWriter csv = new CsvWriter(path, CACHELINE_COLUMNS)) {
            for (CacheLine row : rows) {
                csv.write(List.of(
                        Integer.toString(row.index()), hex(row.address()), Integer.toString(row.node()),
                        Long.toString(row.paCount()), Double.toString(row.peerSnoopPercent()),
                        Long.toString(row.peerTotal()), Long.toString(row.peerLocal()),
                        Long.toString(row.peerRemote()), Long.toString(row.records()),
                        Long.toString(row.loads()), Long.toString(row.stores())));
            }
        }
    }

    private static void writeJoins(Path path, List<JoinRow> rows) throws IOException {
        try (CsvWriter csv = new CsvWriter(path, JOIN_COLUMNS)) {
            for (JoinRow row : rows) {
                csv.write(List.of(
                        row.domain(), hex(row.lineAddress()), Integer.toString(row.c2cIndex()),
                        Long.toString(row.peerTotal()), Long.toString(row.peerLocal()),
                        Long.toString(row.records()), Long.toString(row.loads()), Long.toString(row.stores()),
                        hex(row.objectAddress()), Long.toString(row.objectSize()),
                        Long.toString(row.objectLineOffset()), row.className(), row.objectId(), row.overlappingFields()));
            }
        }
    }

    private static void writeNote(
            Path path, Path collection, Path cacheLines, Path joins, List<JoinRow> selected,
            long objectCount, int virtualCount, int physicalCount, AddressDomain requested) throws IOException {
        String status = selected.isEmpty()
                ? "cache lines extracted; no exact object join produced"
                : "exact object join produced";
        String text = """
                # Java heap cache-line attribution

                Collection: `%s`
                Status: %s.

                ## Summary

                - Heap objects scanned: %,d
                - Virtual-domain matches: %,d
                - Physical-domain matches: %,d
                - Requested address domain: `%s`

                ## Outputs

                - Cache lines: `%s`
                - Cache-line/object join: `%s`

                ## Validity requirements

                The pagemap and heap-object CSV must come from the paused JVM in the same
                object-placement epoch as the perf samples. Reject the result if a moving GC
                occurred between sampling and the address-bearing heap scan.
                """.formatted(collection, status, objectCount, virtualCount, physicalCount,
                requested.name().toLowerCase(Locale.ROOT), cacheLines, joins);
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static long lineBase(long value, int size) {
        return value & -((long) size);
    }

    private static long parseAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing integer");
        }
        String value = raw.trim();
        return value.startsWith("0x") || value.startsWith("0X")
                ? Long.parseUnsignedLong(value.substring(2), 16)
                : Long.parseLong(value);
    }

    private static String hex(long value) {
        return "0x" + Long.toUnsignedString(value, 16);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]+", "_");
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

    private static String first(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("none of the required CSV columns is populated");
    }

    private enum AddressDomain { AUTO, VIRTUAL, PHYSICAL }

    private record Config(
            Path collection, Path output, String runId, int cacheLineSize,
            int pageSize, AddressDomain addressDomain) {
        static Config parse(String[] args) {
            Path collection = null;
            Path output = null;
            String runId = null;
            int cacheLineSize = 64;
            int pageSize = 4096;
            AddressDomain domain = AddressDomain.AUTO;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--collection" -> collection = Path.of(requireArg(args, ++i, "--collection"));
                    case "--output" -> output = Path.of(requireArg(args, ++i, "--output"));
                    case "--run-id" -> runId = requireArg(args, ++i, "--run-id");
                    case "--cache-line-size" -> cacheLineSize = Integer.parseInt(requireArg(args, ++i, "--cache-line-size"));
                    case "--page-size" -> pageSize = Integer.parseInt(requireArg(args, ++i, "--page-size"));
                    case "--address-domain" -> domain = AddressDomain.valueOf(
                            requireArg(args, ++i, "--address-domain").toUpperCase(Locale.ROOT));
                    case "-h", "--help" -> throw usage(null);
                    default -> throw usage("unknown argument: " + args[i]);
                }
            }
            if (collection == null) {
                throw usage("--collection is required");
            }
            if (output == null) {
                output = collection.resolve("analysis");
            }
            validatePowerOfTwo(cacheLineSize, "cache-line size");
            validatePowerOfTwo(pageSize, "page size");
            return new Config(collection, output, runId, cacheLineSize, pageSize, domain);
        }

        private static void validatePowerOfTwo(int value, String label) {
            if (value <= 0 || (value & (value - 1)) != 0) {
                throw usage(label + " must be a positive power of two");
            }
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
                    "usage: java AnalyzeJavaCachelines.java --collection DIR [--output DIR] " +
                    "[--run-id ID] [--cache-line-size 64] [--page-size 4096] " +
                    "[--address-domain auto|virtual|physical]");
        }
    }

    private static final class CsvReader implements AutoCloseable {
        private final PushbackReader reader;
        private final List<String> header;

        CsvReader(Path path) throws IOException {
            Reader base = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            this.reader = new PushbackReader(base, 1);
            this.header = readRecord();
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
                            if (next != -1) {
                                reader.unread(next);
                            }
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
            if (!sawData && values.isEmpty() && field.length() == 0) {
                return null;
            }
            values.add(field.toString());
            return values;
        }

        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class CsvWriter implements AutoCloseable {
        private final BufferedWriter writer;

        CsvWriter(Path path, List<String> header) throws IOException {
            Files.createDirectories(path.toAbsolutePath().getParent());
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            write(header);
        }

        void write(List<String> values) throws IOException {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    writer.write(',');
                }
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

        public void close() throws IOException {
            writer.close();
        }
    }

    private record CollectionFiles(Path base, Path c2cReport) {}
    private record CacheLine(
            int index, long address, int node, long paCount, double peerSnoopPercent,
            long peerTotal, long peerLocal, long peerRemote, long records, long loads, long stores) {}
    private record Page(long virtualStart, long virtualEnd, long pfn, long physicalStart, String mapping) {}
    private record FieldLayout(String name, String type, long offset, long size) {}
    private record ObjectRange(long address, long end, long size, String className, String objectId) {}
    private record LongRange(long start, long end) {}
    private record MatchKey(String domain, long line, long object) {}
    private record JoinRow(
            String domain, long lineAddress, int c2cIndex, long peerTotal, long peerLocal,
            long records, long loads, long stores, long objectAddress, long objectSize,
            long objectLineOffset, String className, String objectId, String overlappingFields) {}
}
