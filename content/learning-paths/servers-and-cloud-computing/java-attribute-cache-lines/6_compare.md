---
title: Compare padding-width performance and memory use
description: Compare Sunflow without @Contended, with the JVM's default padding, and with explicit 64-byte padding using balanced runtime and memory measurements.
weight: 7

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Confirm the padding and cache-line widths

The updated address join identified seven concrete Sunflow classes on hot cache lines. The annotated jar adds class-level `@Contended` to those classes.

HotSpot's `ContendedPaddingWidth` controls the padding added for `@Contended`. It is not the JVM's general object-alignment setting. Confirm both values for your JDK, and query the target's L1 data-cache line size:

```bash
"${jdk_home}/bin/java" -XX:+PrintFlagsFinal -version 2>&1 | \
  awk '$2 == "ContendedPaddingWidth" || $2 == "ObjectAlignmentInBytes" {
    print $2, $4
  }'
getconf LEVEL1_DCACHE_LINESIZE
```

The output on the reference system is:

```output
ContendedPaddingWidth 128
ObjectAlignmentInBytes 8
64
```

The annotated default variant only needs `-XX:-RestrictContended`, so it uses the JDK's implicit 128-byte padding width. The explicit variant also passes `-XX:ContendedPaddingWidth=64` to match the target's cache-line size. Treat 64 bytes as an experiment, not an assumed optimum.

## Run the three-variant comparison

Download [run-sunflow-variants.sh](run-sunflow-variants.sh) and [analyze-sunflow-runs.java](analyze-sunflow-runs.java). The runner requires `numactl`, GNU `/usr/bin/time`, and the `jstat` tool included in the JDK.

Run 24 balanced timing blocks and six separately instrumented memory blocks:

```bash
chmod +x run-sunflow-variants.sh
./run-sunflow-variants.sh \
  --java-home "${jdk_home}" \
  --baseline-jar sunflow-build/jars/sunflow-baseline.jar \
  --contended-jar sunflow-build/jars/sunflow-all-captured-classes-contended.jar \
  --janino-jar sunflow-build/janino.jar \
  --output comparison \
  --timing-blocks 24 \
  --memory-blocks 6 \
  --jstat-interval-ms 100 \
  --numa-node 0
```

Each block runs the baseline, JVM-default padding, and explicit 64-byte padding once. The script cycles through all six possible orders. Each variant therefore occupies each run position eight times in the timing campaign and twice in the memory campaign.

All variants use G1, a 16 GB initial and maximum heap, all processors visible to the JVM, memory from NUMA node 0, and the same Sunflow image-validation threshold. The script also verifies that the default and explicit padding widths resolve to 128 and 64 bytes.

The timing campaign does not run a memory sampler. During the separate memory campaign, `jstat -gc` samples used heap every 100 ms and GNU `time -v` records maximum resident set size (RSS). The script writes:

```output
comparison/timing-runs.csv
comparison/memory-runs.csv
comparison/metadata/
comparison/timing/runs/
comparison/memory/runs/
```

{{% notice Note %}}
The peak heap value is the highest 100 ms sample, so it can miss a shorter-lived allocation spike. Maximum RSS covers the whole JVM process, not only objects in the Java heap. Keep these measurements separate from the configured 16 GB heap limit.
{{% /notice %}}

Summarize the accepted runs:

```bash
java analyze-sunflow-runs.java \
  --timing comparison/timing-runs.csv \
  --memory comparison/memory-runs.csv
```

The analyzer rejects incomplete blocks, failed image validation, missing memory samples, and unexpected padding metadata. It reports runtime median, population standard deviation, coefficient of variation (CV), interquartile range (IQR), paired wins, median sampled peak heap, and median maximum RSS.

## Compare the recorded result

The reference measurements were recorded on an AWS `m8g.metal-48xl` system with 192 processors visible to the JVM. The system reports a Neoverse V2 CPU, a 64-byte L1 data-cache line, and OpenJDK 21.0.12. You can audit the recorded [timing runs](testdata/sunflow-m8g-timing-runs.csv) and [memory runs](testdata/sunflow-m8g-memory-runs.csv).

The runtime statistics for 24 accepted runs per variant are:

| Variant | Median | Change from baseline | Population standard deviation | CV | IQR | Paired wins against baseline |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | 18.427 s | - | 5.424 s | 0.2732 | 10.085 s | - |
| JVM-default padding (128 bytes) | 8.880 s | -51.81% | 1.154 s | 0.1233 | 1.462 s | 24/24 |
| Explicit 64-byte padding | 8.195 s | -55.53% | 0.921 s | 0.1086 | 0.885 s | 24/24 |

![The baseline runtime spans 10.779 to 29.585 seconds with a median of 18.427 seconds. JVM-default 128-byte padding spans 8.015 to 12.511 seconds with a median of 8.880 seconds. Explicit 64-byte padding spans 7.235 to 10.476 seconds with a median of 8.195 seconds. Each variant has 24 validated runs.#center](_images/sunflow-runtime-distribution.svg "Validated runtime distributions for 24 runs per variant")

Both annotated variants were faster and less variable than baseline. Explicit 64-byte padding had a 7.72% lower median than JVM-default padding and won 20 of their 24 block-level comparisons.

The memory statistics are medians of the run-level peaks from six accepted runs per variant:

| Variant | Sampled peak heap | Change from baseline | Maximum RSS | Change from baseline |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 9.78 GiB | - | 9.89 GiB | - |
| JVM-default padding (128 bytes) | 12.95 GiB | +32.37% | 13.10 GiB | +32.45% |
| Explicit 64-byte padding | 11.04 GiB | +12.84% | 11.37 GiB | +14.92% |

Explicit 64-byte padding used 14.75% less sampled peak heap and 13.24% less maximum RSS than JVM-default padding. It did not eliminate the memory cost of `@Contended`: sampled peak heap remained 12.84% above baseline.

Every recorded row has process status `0`, accepted value `1`, and validation `image_check_passed`. Treat the measured differences as specific to this workload, JDK, processor availability, heap configuration, and machine.

## What you've accomplished

You compared the performance and memory impact of class-level `@Contended` using the JVM's implicit padding and an explicit padding width matched to the processor's cache line. Next, you will review the complete attribution and validation workflow.
