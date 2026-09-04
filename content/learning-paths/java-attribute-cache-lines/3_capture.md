---
title: Capture a baseline placement epoch
description: Record Perf C2C samples, virtual-memory mappings, and live Sunflow object addresses while object placement remains stable.
weight: 4

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Capture the baseline

Download the following files to the working directory that contains `sunflow-build`:

- [capture-java-cachelines.sh](capture-java-cachelines.sh)
- [PagemapCsvDump.java](PagemapCsvDump.java)
- [HeapObjectCsvDump.java](HeapObjectCsvDump.java)

Check the installed Perf version:

```bash
perf version
```

{{% notice Perf version %}}
On Neoverse V2 systems, use Perf 6.13 or later. Earlier versions can record SPE packets but do not decode Neoverse V2 data-source values into peer-cache hits. For other Neoverse processors, confirm that your Perf version supports the processor's SPE data-source encoding.
{{% /notice %}}

Perf `6.8.12` is therefore not suitable for this workflow on Neoverse V2. Install or build Perf `6.13` or later before continuing. The HotSpot Serviceability Agent (SA) must come from the same JDK build as the target JVM.

The capture script starts Sunflow, attaches Perf C2C, and briefly stops the JVM after 20 seconds. While it is stopped, the script records `/proc/<pid>/maps`, the virtual-to-physical page map, and an address-bearing SA object dump. It then resumes the renderer and creates the text reports.

```bash
jdk_home="$(java -XshowSettings:properties -version 2>&1 | \
  awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
test -x "${jdk_home}/bin/jcmd"
chmod +x capture-java-cachelines.sh
sudo --preserve-env=PATH ./capture-java-cachelines.sh \
  --java-home "${jdk_home}" \
  --output captures/baseline \
  --snapshot-after 20 \
  -- \
  numactl --physcpubind=0-7 --membind=0 \
  "${jdk_home}/bin/java" \
  -Xms16g -Xmx16g -Xlog:gc:file=captures/baseline/run/gc.log \
  -XX:ActiveProcessorCount=8 \
  -cp sunflow-build/jars/sunflow-baseline.jar:sunflow-build/janino.jar \
  org.sunflow.Benchmark -bench 8 4096 80
```

Inspect `captures/baseline/run/status.txt`, the garbage-collection log, and capture errors before continuing. Reject the run if the snapshot failed, the object scan failed, or a moving garbage collection occurred during the sampling-to-snapshot interval.

{{% notice Warning %}}
Continue only when the render returns status 0 and reports `Image check passed!`. A failed image check invalidates the capture even if rendering completed.
{{% /notice %}}

## Check the C2C report

Open `captures/baseline/c2c/c2c-report.txt`. The reference capture's hottest shared line was:

```output
0 0x8e001840 0 24256 21.46% 2566 2566 0 46544 45987 557
```

Here, `0x8e001840` is the cache-line address and `2566` is the peer-hit count in the reduced report used by the analyzer example. Addresses and counts change between runs; do not search for this literal address in a new capture.

## What you've accomplished

You captured cache-line activity and live object locations from one Sunflow placement epoch. Next, you will join those two address spaces and use JOL to interpret the match.
