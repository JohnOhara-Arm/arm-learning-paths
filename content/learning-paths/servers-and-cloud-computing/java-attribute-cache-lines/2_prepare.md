---
title: Prepare the Sunflow benchmark
description: Build baseline and annotated Sunflow 0.07.2 jars for direct execution with a matching JDK and Janino dependency.
weight: 3

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Download the preparation files

The reference experiment runs Sunflow directly through `org.sunflow.Benchmark`. It does not use a benchmark-suite launcher.

Download these files into one working directory on the target Arm Linux system:

- [prepare-sunflow.sh](../prepare-sunflow.sh)
- [sunflow-reference.patch](../sunflow-reference.patch)
- [sunflow-all-captured-classes-contended.patch](../sunflow-all-captured-classes-contended.patch)

The preparation script downloads Sunflow `0.07.2` and checks the source archive against MD5 `aaaa162cf76cfdbc29381406c08671a9`. The reference patch contains the Sunflow race fix and benchmark entry-point changes used by the experiment. It generates the 4096-pixel reference image using Sunflow's auto-detected processor count, matching the benchmark command below.

Detect the JDK used by the `java` command and confirm that it includes the Java compiler:

```bash
jdk_home="$(java -XshowSettings:properties -version 2>&1 | \
  awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
test -x "${jdk_home}/bin/javac"
test -x "${jdk_home}/bin/jar"
test -x "${jdk_home}/bin/javap"
"${jdk_home}/bin/java" --version
```

Make the script executable and build both variants. Passing the detected path ensures that the compiler, runtime, and inspection tools come from the same JDK:

```bash
chmod +x prepare-sunflow.sh
./prepare-sunflow.sh \
  --java-home "${jdk_home}" \
  --output sunflow-build
```

The script produces:

```output
sunflow-build/jars/sunflow-baseline.jar
sunflow-build/jars/sunflow-all-captured-classes-contended.jar
sunflow-build/janino.jar
```

It also uses `javap -v` to verify the annotation in each modified class. The all-captured-classes experiment annotates `Matrix4`, `BoundingIntervalHierarchy`, `IntersectionState`, `BucketRenderer$BucketThread`, `KDTree`, `BucketRenderer$ImageSample`, and `Color`.


## Confirm direct execution

Run one baseline invocation on all processors visible to the JVM, with memory allocated from NUMA node 0:

```bash
numactl --membind=0 \
  "${jdk_home}/bin/java" \
  -cp sunflow-build/jars/sunflow-baseline.jar:sunflow-build/janino.jar \
  org.sunflow.Benchmark -bench 0 4096 80
```

The thread argument `0` tells Sunflow to use all processors reported by `Runtime.availableProcessors()`. The final argument is Sunflow's image-difference threshold. The value `80` is the threshold defined for its 4096-pixel workload and accommodates small nondeterministic pixel differences without disabling validation.

Sunflow prints a benchmark time after rendering the 4096-pixel reference image. A successful validation ends with:

```output
BENCH  info  : Image check passed!
```

If the image check fails, do not continue with that build. Confirm that `prepare-sunflow.sh` generated the reference image with auto-detected threads and that this command uses `-bench 0 4096 80` with the same JDK and processor availability.

## What you've accomplished

You built comparable Sunflow jars from one pinned source archive and verified that the benchmark runs directly. Next, you will capture cache-line samples and live object addresses from the baseline jar.
