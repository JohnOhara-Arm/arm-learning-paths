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
- [pagemap-csv-dump.java](pagemap-csv-dump.java)
- [heap-object-csv-dump.java](heap-object-csv-dump.java)

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
  numactl --membind=0 \
  "${jdk_home}/bin/java" \
  -Xms16g -Xmx16g -Xlog:gc:file=captures/baseline/run/gc.log \
  -cp sunflow-build/jars/sunflow-baseline.jar:sunflow-build/janino.jar \
  org.sunflow.Benchmark -bench 0 4096 80
```

Inspect `captures/baseline/run/status.txt`, the garbage-collection log, and capture errors before continuing. Reject the run if the snapshot failed, the object scan failed, or a moving garbage collection occurred during the sampling-to-snapshot interval.

{{% notice Warning %}}
Continue only when the render returns status 0 and reports `Image check passed!`. A failed image check invalidates the capture even if rendering completed.
{{% /notice %}}

## Check the C2C report

Open `captures/baseline/c2c/c2c-report.txt`. The following output was captured during a baseline Sunflow run on a Neoverse V2 system:

```output
=================================================
            Trace Event Information
=================================================
  Total records                     :    3155822
  Locked Load/Store Operations      :          0
  Load Operations                   :    2903443
  Loads - uncacheable               :          0
  Loads - IO                        :          0
  Loads - Miss                      :          0
  Loads - no mapping                :          0
  Load Fill Buffer Hit              :          0
  Load L1D hit                      :    2864768
  Load L2D hit                      :      19732
  Load LLC hit                      :      18797
  Load Local HITM                   :          0
  Load Remote HITM                  :          0
  Load Remote HIT                   :          0
  Load Local DRAM                   :        146
  Load Remote DRAM                  :          0
  Load MESI State Exclusive         :        146
  Load MESI State Shared            :          0
  Load LLC Misses                   :        146
  Load access blocked by data       :          0
  Load access blocked by address    :          0
  Load HIT Local Peer               :       5025
  Load HIT Remote Peer              :          0
  LLC Misses to Local DRAM          :      100.0%
  LLC Misses to Remote DRAM         :        0.0%
  LLC Misses to Remote cache (HIT)  :        0.0%
  LLC Misses to Remote cache (HITM) :        0.0%
  Store Operations                  :     252379
  Store - uncacheable               :          0
  Store - no mapping                :          0
  Store L1D Hit                     :          0
  Store L1D Miss                    :          0
  Store No available memory level   :     252379
  No Page Map Rejects               :          0
  Unable to parse data source       :          0

=================================================
    Global Shared Cache Line Event Information
=================================================
  Total Shared Cache Lines          :        143
  Load HITs on shared lines         :      89507
  Fill Buffer Hits on shared lines  :          0
  L1D hits on shared lines          :      70618
  L2D hits on shared lines          :        284
  LLC hits on shared lines          :      18605
  Load hits on peer cache or nodes  :       5025
  Locked Access on shared lines     :          0
  Blocked Access on shared lines    :          0
  Store HITs on shared lines        :       1994
  Store L1D hits on shared lines    :          0
  Store No available memory level   :       1994
  Total Merged records              :       1994

=================================================
                 c2c details
=================================================
  Events                            : arm_spe_0/ts_enable=1,pa_enable=1,load_filter=1,store_filter=1,min_latency=30/
                                    : dummy:u
                                    : memory
  Cachelines sort on                : Peer Snoop
  Cacheline data grouping           : offset,iaddr

=================================================
           Shared Data Cache Line Table
=================================================
#
#        ----------- Cacheline ----------     Peer  ------- Load Peer -------    Total    Total    Total  --------- Stores --------  ----- Core Load Hit -----  - LLC Load Hit --  - RMT Load Hit --  --- Load Dram ----
# Index             Address  Node  PA cnt    Snoop    Total    Local   Remote  records    Loads   Stores    L1Hit   L1Miss      N/A       FB       L1       L2    LclHit  LclHitm    RmtHit  RmtHitm       Lcl       Rmt
# .....  ..................  ....  ......  .......  .......  .......  .......  .......  .......  .......  .......  .......  .......  .......  .......  .......  ........  .......  ........  .......  ........  ........
#
      0         0x400816340     0   12275   27.70%     1392     1392        0    27076    27076        0        0        0        0        0    23058       17      4001        0         0        0         0         0
      1         0x400816380     0     904   21.69%     1090     1090        0     6514     6203      311        0        0      311        0     2415       28      3760        0         0        0         0         0
      2         0x400842100     0   16823   18.39%      924      924        0    22299    22124      175        0        0      175        0    16066       73      5985        0         0        0         0         0
      3         0x4008005c0     0     494    7.82%      393      393        0      795      545      250        0        0      250        0      105        0       440        0         0        0         0         0
      4         0x40082c480     0   12783    5.53%      278      278        0    19181    19101       80        0        0       80        0    17242      139      1720        0         0        0         0         0
      5         0x599069900     0    1099    3.12%      157      157        0     1873     1848       25        0        0       25        0     1363        0       485        0         0        0         0         0
      6         0x599091d00     0    1552    2.97%      149      149        0     2288     2270       18        0        0       18        0     1781        0       489        0         0        0         0         0
      7         0x599037280     0    1481    2.95%      148      148        0     2215     2192       23        0        0       23        0     1719        0       473        0         0        0         0         0
      8         0x599079840     0    1512    1.37%       69       69        0     2379     2365       14        0        0       14        0     2005        6       354        0         0        0         0         0
      9         0x400851540     0     108    0.86%       43       43        0      165      140       25        0        0       25        0       65        4        71        0         0        0         0         0
```

## Understand the overview

The report describes both the complete SPE sample and the subset associated with shared cache lines:

- Perf decoded approximately `3.2 million` records: `2.9 million` loads and `250,000` stores. Approximately 99% of the sampled loads hit in L1D. The peer-hit count is a subset of the load hierarchy, not an additional set of loads.
- There are no page-map rejects or unparsed data sources. This is important because the address-attribution workflow requires usable addresses and decoded SPE data-source values.
- Perf found approximately `140` shared cache lines containing `90,000` load hits. Of those, `5,000`, or approximately 5.6%, hit a peer CPU's cache. All peer hits are local to the NUMA node; the report contains no remote-node peer hits.
- Store samples are present, but SPE did not assign a memory level to them. `Store No available memory level` does not mean that the stores missed every cache; it means this report cannot classify their cache level.

The shared-line table is sorted by `Peer Snoop`, so the first rows are the best candidates for investigation. Cache line `0x400816340` accounts for approximately `1,400` local peer hits, or 28% of all peer hits. The first three lines together account for approximately `3,400` of the `5,000` peer hits, or 68%, so begin the object-address join with these lines.

The zero `Local HITM` and `Remote HITM` values do not rule out cache-line contention on this Arm system. For this SPE capture, use `Load HIT Local Peer` and the per-line `Load Peer` columns as the sharing evidence.

Addresses and counts change between JVM runs. Do not search for these literal addresses in another capture; use the highest-ranked addresses produced by that run and join them only to the object snapshot from the same placement epoch.

## What you've accomplished

You captured cache-line activity and live object locations from one Sunflow placement epoch. Next, you will join those two address spaces and use JOL to interpret the match.
