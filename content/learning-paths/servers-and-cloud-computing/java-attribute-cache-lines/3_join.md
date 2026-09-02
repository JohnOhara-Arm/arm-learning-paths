---
title: Join objects and classify layouts
weight: 4

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Run the cache-line analyzer

Download [AnalyzeJavaCachelines.java](AnalyzeJavaCachelines.java). It is a
single Java 21 source file with no external dependencies and streams the heap
CSV so multi-million-object captures do not need to fit in memory.

Run it directly:

```bash
java AnalyzeJavaCachelines.java \
  --collection java-cacheline-run \
  --output analysis \
  --cache-line-size 64 \
  --page-size "$(getconf PAGESIZE)" \
  --address-domain auto
```

Source-file mode is convenient for normal captures. For a multi-million-object
CSV, compile once and set an explicit heap limit to avoid retaining the Java
source compiler during the scan:

```bash
javac AnalyzeJavaCachelines.java
java -Xmx256m AnalyzeJavaCachelines \
  --collection java-cacheline-run \
  --output analysis \
  --address-domain auto
```

The analyzer tests virtual and physical joins. Automatic selection succeeds
only when one domain produces matches. If both produce matches, inspect the
address evidence and rerun with an explicit domain.

It writes:

```text
analysis/<run>_cachelines.csv
analysis/<run>_cacheline_object_join.csv
analysis/<run>_java_heap_cacheline_attribution.md
```

## Collect and interpret JOL layouts

For each class in the join CSV, run JOL using the workload class path, JDK,
and layout-related VM flags:

```bash
mkdir -p jol-internals
java -cp jol-cli-0.17-full.jar:application.jar \
  org.openjdk.jol.Main internals com.example.WorkerState \
  > jol-internals/WorkerState.txt
```

Download [AnalyzeJolAdjacency.java](AnalyzeJolAdjacency.java) and classify the
overlaps:

```bash
java AnalyzeJolAdjacency.java \
  --join analysis/<run>_cacheline_object_join.csv \
  --jol-dir jol-internals \
  --source-root application-source \
  --output analysis \
  --cache-line-size 64
```

The optional source scan looks for parent classes that reference multiple hot
types. It is a conservative hint, not proof that referenced objects are
stored inline or allocated together.

The classifications are:

- `intra_object_field_overlap` — JOL fields overlap one hot line;
- `object_boundary_allocation_adjacency` — one object ends where another begins;
- `inter_object_allocation_adjacency` — multiple object bodies overlap the line;
- `single_object_no_jol_field_overlap` — the line is a header or unresolved range.

Arrays require an additional element-offset interpretation using the array
base and scale reported by the JVM.
