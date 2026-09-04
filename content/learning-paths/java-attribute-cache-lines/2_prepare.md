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
- [sunflow-six-class-contended.patch](../sunflow-six-class-contended.patch)

The preparation script downloads Sunflow `0.07.2` and checks the source archive against MD5 `aaaa162cf76cfdbc29381406c08671a9`. The reference patch contains the Sunflow race fix and benchmark entry-point changes used by the experiment. It generates the 4096-pixel reference image with eight rendering threads, matching the benchmark command below.

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
sunflow-build/jars/sunflow-six-class-contended.jar
sunflow-build/janino.jar
```

It also uses `javap -v` to verify the annotation in each modified class. The fixed jar annotates `BucketRenderer$BucketThread`, `KDTree`, `InstantGI$PointLight`, `Color`, `Instance`, and `BoundingIntervalHierarchy`.


## Confirm direct execution

Run one baseline invocation on eight CPUs from NUMA node 0:

```bash
numactl --physcpubind=0-7 --membind=0 \
  "${jdk_home}/bin/java" \
  -XX:ActiveProcessorCount=8 \
  -cp sunflow-build/jars/sunflow-baseline.jar:sunflow-build/janino.jar \
  org.sunflow.Benchmark -bench 8 4096 80
```

The final argument is Sunflow's image-difference threshold. The value `80` is the threshold defined for its 4096-pixel workload and accommodates small nondeterministic pixel differences without disabling validation.

Sunflow prints a benchmark time after rendering the 4096-pixel reference image. A successful validation ends with:

```output
BENCH  info  : Image check passed!
```

If the image check fails, do not continue with that build. Confirm that `prepare-sunflow.sh` generated the reference image with eight threads and that this command uses `-bench 8 4096 80` with the same JDK.

## What you've accomplished

You built comparable Sunflow jars from one pinned source archive and verified that the benchmark runs directly. Next, you will capture cache-line samples and live object addresses from the baseline jar.
