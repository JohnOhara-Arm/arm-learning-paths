---
title: Isolate the contended classes
description: Apply class-level @Contended to six attributed Sunflow classes and compare the result with another Perf C2C capture.
weight: 6

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Apply the targeted change

The fixed patch adds `jdk.internal.vm.annotation.Contended` to the six classes identified on hot shared boundaries: `BucketRenderer$BucketThread`, `KDTree`, `InstantGI$PointLight`, `Color`, `Instance`, and `BoundingIntervalHierarchy`. Class-level padding isolates each instance from neighboring allocations; it does not prove which field generated each sampled access.

The preparation script has already built `sunflow-six-class-contended.jar`. Run it with `-XX:-RestrictContended`; without this option HotSpot ignores `@Contended` on application classes.

```bash
jdk_home="$(java -XshowSettings:properties -version 2>&1 | \
  awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
test -x "${jdk_home}/bin/jcmd"
sudo --preserve-env=PATH ./capture-java-cachelines.sh \
  --java-home "${jdk_home}" \
  --output captures/fixed \
  --snapshot-after 20 \
  -- \
  numactl --physcpubind=0-7 --membind=0 \
  "${jdk_home}/bin/java" \
  -Xms16g -Xmx16g -Xlog:gc:file=captures/fixed/run/gc.log \
  -XX:ActiveProcessorCount=8 -XX:-RestrictContended \
  -cp sunflow-build/jars/sunflow-six-class-contended.jar:sunflow-build/janino.jar \
  org.sunflow.Benchmark -bench 8 4096 80
```

Use the same CPU, NUMA, JDK, heap, Perf, and sampling settings as the baseline. Also require the same image-validation outcome. Then compare the summary counters from both `c2c-report-stats.txt` files.

## Compare the contention evidence

Compare the baseline and fixed `c2c-report-stats.txt` files. Record the shared-line load hits and peer hits for both runs, and calculate the percentage change for each metric:

```text
percentage_change = (fixed - baseline) / baseline * 100
```

Lower shared-line and peer-hit counts support the allocation-isolation hypothesis. One pair is not enough to claim a stable runtime improvement, so you will measure runtime separately over repeated paired runs.

`@Contended` increases object size. Recheck JOL layouts, garbage-collection behavior, allocation rate, and memory footprint before using it in production.

## What you've accomplished

You applied one evidence-derived six-class change and compared its cache-line contention with the baseline. Next, you will test whether the same patch improves the runtime distribution.
