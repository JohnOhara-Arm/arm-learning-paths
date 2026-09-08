---
title: Isolate the contended classes
description: Apply class-level @Contended to the attributed Sunflow classes and compare the result with another Perf C2C capture.
weight: 6

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Apply the targeted change

The fixed patch adds `jdk.internal.vm.annotation.Contended` to the seven concrete Sunflow classes identified on hot shared boundaries: `Matrix4`, `BoundingIntervalHierarchy`, `IntersectionState`, `BucketRenderer$BucketThread`, `KDTree`, `BucketRenderer$ImageSample`, and `Color`. Class-level padding isolates each instance from neighboring allocations; it does not prove which field generated each sampled access.

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

Compare the baseline and fixed `c2c-report-stats.txt` files. Record the shared-line load hits and peer hits for both runs, and calculate the percentage change for each metric:

```text
percentage_change = (fixed - baseline) / baseline * 100
```

Lower shared-line and peer-hit counts support the allocation-isolation hypothesis. One pair is not enough to claim a stable runtime improvement, so you will measure runtime separately over repeated paired runs.

`@Contended` increases object size. Recheck JOL layouts, garbage-collection behavior, allocation rate, and memory footprint before using it in production.

The [sunflow-all-captured-classes-contended.patch](../sunflow-all-captured-classes-contended.patch) provides a separate experiment covering every concrete `org.sunflow` class in the later Perf C2C object join: `Matrix4`, `BoundingIntervalHierarchy`, `IntersectionState`, `BucketRenderer$BucketThread`, `KDTree`, `BucketRenderer$ImageSample`, and `Color`.

The captured `ImageSample[]` and `IntersectionState$StackNode[]` objects are arrays. Java does not allow `@Contended` on an array class, and annotating the component class does not change the spacing of references within an array. The patch therefore cannot isolate those array slots.

Treat the fixed jar as a new variant requiring its own image validation, Perf C2C capture, repeated timings, allocation-rate measurements, and garbage-collection review.

{{% notice Warning %}}
Class-level `@Contended` substantially increases the size of every annotated object. `ImageSample` objects are allocated frequently, so measure allocation rate, garbage collection, memory use, C2C peer hits, and runtime before deciding whether this experiment is an improvement.
{{% /notice %}}

## What you've accomplished

You applied an evidence-derived change to the captured Sunflow classes and compared its cache-line contention with the baseline. Next, you will test whether the patch improves the runtime distribution.
