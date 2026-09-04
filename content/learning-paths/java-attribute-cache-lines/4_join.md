---
title: Trace a hot line to Sunflow objects
description: Join a Perf C2C line to live Java object ranges, then use JOL to distinguish field overlap from adjacent-object contention.
weight: 5

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Join the address evidence

Download [AnalyzeJavaCachelines.java](AnalyzeJavaCachelines.java) and run it against the baseline capture:

```bash
mkdir -p analysis/baseline
java AnalyzeJavaCachelines.java \
  --collection captures/baseline \
  --output analysis/baseline \
  --run-id baseline \
  --address-domain auto
```

`auto` succeeds only when exactly one address domain produces matches. If both virtual and physical joins match, inspect `perf script`, the Perf C2C report, and `pagemap-heap.csv`, then rerun with `--address-domain virtual` or `physical`.

For every hot cache line, the analyzer tests the object ranges `[object_address, object_address + size)`. The reduced reference evidence maps line `0x8e001840` to two adjacent objects:

| Object address | Size | Class |
| --- | ---: | --- |
| `0x8e001800` | 120 bytes | `BucketRenderer$BucketThread` |
| `0x8e001878` | 32 bytes | `KDTree` |

The line begins 64 bytes into `BucketThread`. That object ends at `0x8e001878`, where `KDTree` begins, so both ranges overlap the same 64-byte line.

## Use JOL to classify the overlap

Download [AnalyzeJolAdjacency.java](AnalyzeJolAdjacency.java) and [JOL CLI 0.17](https://repo1.maven.org/maven2/org/openjdk/jol/jol-cli/0.17/jol-cli-0.17-full.jar):

```bash
curl -fL \
  https://repo1.maven.org/maven2/org/openjdk/jol/jol-cli/0.17/jol-cli-0.17-full.jar \
  -o jol-cli.jar
```

Generate layouts with the same JVM options as the measured process:

```bash
mkdir -p analysis/baseline/jol
classes=(
  'org.sunflow.core.renderer.BucketRenderer$BucketThread'
  'org.sunflow.core.accel.KDTree'
)
for class_name in "${classes[@]}"; do
  file_name="${class_name##*.}"
  file_name="${file_name//$/_}.txt"
  java -cp jol-cli.jar:sunflow-build/jars/sunflow-baseline.jar \
    org.openjdk.jol.Main internals "${class_name}" \
    > "analysis/baseline/jol/${file_name}"
done
```

This creates one JOL `internals` text file for each class in `analysis/baseline/jol`.

Class metadata describes layout, not the addresses of these particular objects. Combine it with the address join:

```bash
java AnalyzeJolAdjacency.java \
  --join analysis/baseline/baseline_cacheline_object_join.csv \
  --jol-dir analysis/baseline/jol \
  --source-root sunflow-build/sources/baseline/src \
  --output analysis/baseline \
  --run-id baseline
```

The reference summary classifies the hot line as `object_boundary_allocation_adjacency` and reports this boundary pair:

```output
org.sunflow.core.renderer.BucketRenderer$BucketThread -> org.sunflow.core.accel.KDTree
```

This is not two fields deliberately placed in one object. It is allocation adjacency: independently allocated objects happen to share a line. Use the source candidates emitted by the analyzer to find their allocation owner, and confirm the relationship in source before changing code.

## What you've learned

You traced a Perf C2C address to two concrete Sunflow object instances and used JOL to classify their shared boundary. Next, you will isolate the classes and repeat the C2C capture.
