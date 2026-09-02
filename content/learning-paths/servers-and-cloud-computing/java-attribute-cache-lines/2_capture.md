---
title: Capture cache lines and heap objects
weight: 3

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Download the capture tools

Keep these files in one directory on the target Arm Linux system:

- [capture-java-cachelines.sh](capture-java-cachelines.sh)
- [HeapObjectCsvDump.java](HeapObjectCsvDump.java)
- [PagemapCsvDump.java](PagemapCsvDump.java)

They require a full HotSpot JDK 21 or later and standard Linux utilities. The
Serviceability Agent must come from the same JDK build as the target JVM.

Make the collector executable:

```bash
chmod +x capture-java-cachelines.sh
```

## Start a synchronized collection

Run the collector on the same machine as the application. Supply the output
directory, matching JDK, validated `perf` binary, snapshot delay, and complete
Java command:

```bash
sudo ./capture-java-cachelines.sh \
  --output java-cacheline-run \
  --java-home /usr/lib/jvm/java-21-openjdk-arm64 \
  --perf /usr/local/bin/perf \
  --snapshot-after 20 \
  -- taskset -c 0-7 /usr/lib/jvm/java-21-openjdk-arm64/bin/java \
     -Xms8g -Xmx8g \
     -Xlog:gc*:file=gc.log \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:+DebugNonSafepoints \
     -XX:+PreserveFramePointer \
     -jar application.jar
```

The collector performs this sequence:

1. Launch the application and attach `perf c2c record`.
2. Pause the JVM after the selected hot interval.
3. Capture `maps`, `smaps`, `numa_maps`, status, and pagemap rows.
4. Scan every heap object using the HotSpot Serviceability Agent.
5. Resume the JVM, collect supporting `jcmd` diagnostics, and finish c2c reporting.

The generic timed pause is convenient for a tutorial. For an application with
distinct phases, replace it with a workload-controlled latch so that the
snapshot occurs immediately after the representative phase.

## Check the required artifacts

Confirm that the output contains:

```text
c2c/c2c-report.txt
c2c/perf-script-data-src.txt
snapshot/maps.txt
snapshot/pagemap-heap.csv
heap/heap-objects.csv
jcmd/vm-flags.txt
```

An HPROF dump can provide reference paths and retained sizes, but it is
supporting evidence. Do not substitute HPROF identifiers for the address CSV.
