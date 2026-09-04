import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes anonymous writable virtual-page to PFN mappings using only the JDK. */
public final class PagemapCsvDump {
    private PagemapCsvDump() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        int pageSize = Integer.getInteger("pagemap.pageSize", 4096);
        if (pageSize <= 0 || (pageSize & (pageSize - 1)) != 0) {
            throw new IllegalArgumentException("pagemap.pageSize must be a positive power of two");
        }
        dump(config.pid(), config.out(), pageSize);
    }

    private static void dump(long pid, Path out, int pageSize) throws IOException {
        Path maps = Path.of("/proc", Long.toString(pid), "maps");
        Path pagemap = Path.of("/proc", Long.toString(pid), "pagemap");
        Files.createDirectories(out.toAbsolutePath().getParent());
        ByteBuffer entryBuffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.nativeOrder());
        try (FileChannel channel = FileChannel.open(pagemap, StandardOpenOption.READ);
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("vaddr_start,vaddr_end,present,swapped,pfn,phys_start,mapping");
            writer.newLine();
            for (String line : Files.readAllLines(maps, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+", 6);
                if (parts.length < 2 || !selected(parts[1], parts.length == 6 ? parts[5] : "")) {
                    continue;
                }
                String[] range = parts[0].split("-", 2);
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                String mapping = parts.length == 6 ? parts[5] : "";
                for (long address = start; address < end; address += pageSize) {
                    entryBuffer.clear();
                    long offset = Math.multiplyExact(Long.divideUnsigned(address, pageSize), Long.BYTES);
                    int read = 0;
                    while (entryBuffer.hasRemaining()) {
                        int count = channel.read(entryBuffer, offset + read);
                        if (count < 0) break;
                        read += count;
                    }
                    if (read != Long.BYTES) {
                        continue;
                    }
                    entryBuffer.flip();
                    long entry = entryBuffer.getLong();
                    boolean present = ((entry >>> 63) & 1L) == 1L;
                    boolean swapped = ((entry >>> 62) & 1L) == 1L;
                    long pfn = entry & ((1L << 55) - 1L);
                    long virtualEnd = Math.min(address + pageSize, end);
                    writer.write(hex(address));
                    writer.write(',');
                    writer.write(hex(virtualEnd));
                    writer.write(',');
                    writer.write(present ? "1" : "0");
                    writer.write(',');
                    writer.write(swapped ? "1" : "0");
                    writer.write(',');
                    writer.write(Long.toUnsignedString(pfn));
                    writer.write(',');
                    if (present) {
                        writer.write(hex(pfn * pageSize));
                    }
                    writer.write(',');
                    writeCsv(writer, mapping);
                    writer.newLine();
                }
            }
        }
    }

    private static boolean selected(String permissions, String mapping) {
        if (!permissions.contains("r") || !permissions.contains("w")) {
            return false;
        }
        if (mapping.startsWith("[stack") || mapping.equals("[vdso]") ||
                mapping.equals("[vvar]") || mapping.equals("[vsyscall]")) {
            return false;
        }
        return mapping.isEmpty() || mapping.equals("[heap]");
    }

    private static void writeCsv(BufferedWriter writer, String value) throws IOException {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            writer.write(value);
            return;
        }
        writer.write('"');
        writer.write(value.replace("\"", "\"\""));
        writer.write('"');
    }

    private static String hex(long value) {
        return "0x" + Long.toUnsignedString(value, 16);
    }

    private record Config(long pid, Path out) {
        static Config parse(String[] args) {
            long pid = -1;
            Path out = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--pid" -> pid = Long.parseLong(require(args, ++i, "--pid"));
                    case "--out" -> out = Path.of(require(args, ++i, "--out"));
                    default -> throw usage("unknown argument: " + args[i]);
                }
            }
            if (pid <= 0 || out == null) {
                throw usage("--pid and --out are required");
            }
            return new Config(pid, out);
        }

        private static String require(String[] args, int index, String option) {
            if (index >= args.length) throw usage(option + " requires a value");
            return args[index];
        }

        private static IllegalArgumentException usage(String message) {
            return new IllegalArgumentException(message + System.lineSeparator() +
                    "usage: java PagemapCsvDump.java --pid PID --out FILE");
        }
    }
}
