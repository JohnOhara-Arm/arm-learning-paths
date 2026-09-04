import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import sun.jvm.hotspot.HotSpotAgent;
import sun.jvm.hotspot.debugger.Address;
import sun.jvm.hotspot.oops.DefaultHeapVisitor;
import sun.jvm.hotspot.oops.Klass;
import sun.jvm.hotspot.oops.Oop;
import sun.jvm.hotspot.runtime.VM;

public final class HeapObjectCsvDump {
    private HeapObjectCsvDump() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        HotSpotAgent agent = new HotSpotAgent();
        boolean attached = false;
        try {
            if (config.core != null) {
                agent.attach(config.executable, config.core);
            } else {
                agent.attach(config.pid);
            }
            attached = true;
            dump(config.out);
        } finally {
            if (attached) {
                agent.detach();
            }
        }
    }

    private static void dump(Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("object_address,size,class_name,object_id");
            writer.newLine();
            VM.getVM().getObjectHeap().iterate(new CsvHeapVisitor(writer));
        }
    }

    private static final class CsvHeapVisitor extends DefaultHeapVisitor {
        private final BufferedWriter writer;
        private long count;

        CsvHeapVisitor(BufferedWriter writer) {
            this.writer = writer;
        }

        @Override
        public boolean doObj(Oop obj) {
            try {
                Address handle = obj.getHandle();
                long address = handle == null ? 0L : handle.asLongValue();
                long size = obj.getObjectSize();
                Klass klass = obj.getKlass();
                String className = klass == null || klass.getName() == null
                        ? "<unknown>"
                        : klass.getName().asString().replace('/', '.');
                writer.write("0x");
                writer.write(Long.toUnsignedString(address, 16));
                writer.write(',');
                writer.write(Long.toUnsignedString(size));
                writer.write(',');
                writeCsv(writer, className);
                writer.write(',');
                writer.newLine();
                count++;
                if ((count & 0xfffffL) == 0L) {
                    System.err.printf(Locale.ROOT, "dumped %,d heap objects%n", count);
                }
            } catch (Throwable t) {
                System.err.println("skipping object after SA read failure: " + t);
            }
            return false;
        }
    }

    private static void writeCsv(BufferedWriter writer, String value) throws IOException {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) {
            writer.write(value);
            return;
        }
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                writer.write("\"\"");
            } else {
                writer.write(ch);
            }
        }
        writer.write('"');
    }

    private static final class Config {
        final int pid;
        final String executable;
        final String core;
        final Path out;

        Config(int pid, String executable, String core, Path out) {
            this.pid = pid;
            this.executable = executable;
            this.core = core;
            this.out = out;
        }

        static Config parse(String[] args) {
            int pid = -1;
            String executable = null;
            String core = null;
            Path out = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--pid":
                        pid = Integer.parseInt(requireValue(args, ++i, "--pid"));
                        break;
                    case "--exe":
                        executable = requireValue(args, ++i, "--exe");
                        break;
                    case "--core":
                        core = requireValue(args, ++i, "--core");
                        break;
                    case "--out":
                        out = Path.of(requireValue(args, ++i, "--out"));
                        break;
                    default:
                        throw usage("unknown argument: " + args[i]);
                }
            }
            if (out == null) {
                throw usage("--out is required");
            }
            if (core != null) {
                if (executable == null) {
                    throw usage("--exe is required with --core");
                }
                return new Config(-1, executable, core, out);
            }
            if (pid <= 0) {
                throw usage("--pid is required when --core is not used");
            }
            return new Config(pid, executable, core, out);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw usage(option + " requires a value");
            }
            return args[index];
        }

        private static IllegalArgumentException usage(String message) {
            return new IllegalArgumentException(message + "\nusage: HeapObjectCsvDump --pid <pid> --out <csv>\n"
                    + "   or: HeapObjectCsvDump --exe <java> --core <core> --out <csv>");
        }
    }
}
