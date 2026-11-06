---
title: Isolate the contended classes
description: Apply class-level @Contended to the attributed Sunflow classes and compare the result with another Perf C2C capture.
weight: 6

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Apply the targeted change

The [sunflow-all-captured-classes-contended.patch](../sunflow-all-captured-classes-contended.patch) patch adds `jdk.internal.vm.annotation.Contended` to the seven concrete Sunflow classes identified on hot shared boundaries: 
 - `Matrix4`
 - `BoundingIntervalHierarchy`
 - `IntersectionState`
 - `BucketRenderer$BucketThread`
 - `KDTree`
 - `BucketRenderer$ImageSample`
 - `Color`
 
Class-level padding isolates each instance from neighboring allocations and prevents false sharing due to object allocation.

The preparation script has already built `sunflow-all-captured-classes-contended.jar`. Run it with `-XX:-RestrictContended`; without this option HotSpot ignores `@Contended` on application classes.

```bash
jdk_home="$(java -XshowSettings:properties -version 2>&1 | \
  awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
test -x "${jdk_home}/bin/jcmd"
sudo --preserve-env=PATH ./capture-java-cachelines.sh \
  --java-home "${jdk_home}" \
  --output captures/fixed \
  --snapshot-after 20 \
  -- \
  numactl --membind=0 \
  "${jdk_home}/bin/java" \
  -Xms16g -Xmx16g -Xlog:gc:file=captures/fixed/run/gc.log \
  -XX:-RestrictContended \
  -cp sunflow-build/jars/sunflow-all-captured-classes-contended.jar:sunflow-build/janino.jar \
  org.sunflow.Benchmark -bench 0 4096 80
```

Use the same processor availability, NUMA, JDK, heap, Perf, and sampling settings as the baseline. Also require the same image-validation outcome. Then compare the summary counters from both `c2c-report-stats.txt` files.

## Compare the contention evidence

Compare the baseline and fixed `c2c-report-stats.txt` files. 

Lower shared-line and peer-hit counts support the allocation-isolation hypothesis. One pair is not enough to claim a stable runtime improvement, so you will measure runtime separately over repeated paired runs.

## Caveats to using `@Contended` annotation

`@Contended` increases object size. HotSpot controls the padding with `-XX:ContendedPaddingWidth`. The reference JDK uses 128 bytes by default, which is separate from its object-alignment setting. Recheck JOL layouts, garbage-collection behavior, allocation rate, and memory footprint before using it in production.

Treat the fixed jar as a new variant requiring its own image validation, Perf C2C capture, repeated timings, allocation-rate measurements, and garbage-collection review.

{{% notice Warning %}}
Class-level `@Contended` can substantially increase the size of every annotated object. Some objects are allocated frequently, so measure allocation rate, garbage collection, memory use, C2C peer hits, and runtime before deciding whether this experiment is an improvement.
{{% /notice %}}

## What you've accomplished

You applied an evidence-derived change to the captured Sunflow classes and compared its cache-line contention with the baseline. Next, you will compare runtime and memory use with the JVM's default padding and with an explicit 64-byte padding width.
