---
title: Create and inspect the baseline
weight: 3

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Prepare the baseline example

Before running any examples; Resolve the absolute Java executable path . Derive
`javac_bin` from the same JDK so compilation and execution use matching
versions. Keep using the same terminal session throughout this Learning Path;
if you open a new terminal, please re-run the following commands.

```bash
java_bin=$(readlink -f "$(command -v java)")
javac_bin="$(dirname "$java_bin")/javac"

"$java_bin" -version
"$javac_bin" -version
```

Using the absolute path is important when Perf starts the payload with `sudo`,
because the restricted `sudo` `PATH` might not contain your Java installation.

### Setup the False Sharing Demo

Download [FalseSharingDemo.java](../FalseSharingDemo.java). The example has two
worker threads that update adjacent `volatile long` fields. Each worker performs
500 million increments, making the sharing behavior easier to sample.


Compile the baseline:

```bash
"$javac_bin" FalseSharingDemo.java
```

The following commands use logical CPUs 0 and 1. Confirm that both CPUs are in
the current shell's permitted affinity list:

```bash
taskset -pc $$
```

If needed, replace `0,1` with two online CPUs from the reported list. Run the
baseline:

```bash
taskset -c 0,1 "$java_bin" FalseSharingDemo baseline
```

The output includes `mode=baseline`, elapsed seconds, a sum, and the process ID:

```output
mode=baseline seconds=45.123456 sum=1000000000 pid=12345
```

Your elapsed time and process ID will differ. Confirm that the output contains
`mode=baseline` and `sum=1000000000`.

`taskset` restricts the JVM and its threads to the selected logical CPUs. It
does not assign one writer thread to each CPU, so either thread can migrate
within the permitted set.

## Inspect the baseline layout with JOL

[Java Object Layout (JOL)](https://github.com/openjdk/jol) is an OpenJDK tool
used to inspect JVM object-layout details, including field offsets, alignment
gaps, and total object size.

The JOL CLI JAR is published in Maven Central. Download the
[JOL CLI 0.17 full JAR](https://repo.maven.apache.org/maven2/org/openjdk/jol/jol-cli/0.17/jol-cli-0.17-full.jar)
into the directory containing the compiled `FalseSharingDemo` classes:

```bash
curl -LO https://repo.maven.apache.org/maven2/org/openjdk/jol/jol-cli/0.17/jol-cli-0.17-full.jar
```

The `full` JAR includes the dependencies required by the command-line tool.
Inspect `BaselineCounters` with the same JDK used for the workload:

```bash
"$java_bin" -cp jol-cli-0.17-full.jar:. \
  org.openjdk.jol.Main internals 'FalseSharingDemo$BaselineCounters'
```

A representative OpenJDK 21 layout is:

```output
FalseSharingDemo$BaselineCounters object internals:
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001
  8   4        (object header: class)    0x01085290
 12   4        (alignment/padding gap)
 16   8   long BaselineCounters.left     0
 24   8   long BaselineCounters.right    0
Instance size: 32 bytes
Space losses: 4 bytes internal + 0 bytes external = 4 bytes total
```

In this configuration, the header is 12 bytes: an 8-byte mark word and a
4-byte compressed class pointer. A `long` is aligned to an 8-byte boundary so
that it can be accessed efficiently as one aligned value rather than straddling
two 8-byte words. JOL therefore shows a 4-byte gap before `left`; the header
itself has not been padded to 16 bytes.

The two fields are adjacent at offsets 16 and 24. They can occupy one 64-byte
cache line, although their offsets within the object do not reveal where the
object was placed relative to a physical cache-line boundary. Your layout can
differ with the JDK and VM configuration.

## Record the baseline with Perf C2C

{{% notice Perf version %}}
On Neoverse V2 systems, use Perf 6.13 or later. Earlier versions can record SPE
packets but do not decode Neoverse V2 data-source values into peer-cache hits.
For other Neoverse processors, confirm that your Perf version supports the
processor's SPE data-source encoding.
{{% /notice %}}

Check the version, then record the workload:

```bash
perf version

sudo perf c2c record -o baseline.data -- \
  taskset -c 0,1 "$java_bin" FalseSharingDemo baseline
```

Confirm again that the payload prints `mode=baseline` and
`sum=1000000000`. The absolute `"$java_bin"` path is expanded by the shell
before `sudo` runs Perf, avoiding the restricted `sudo` `PATH`.

Perf C2C uses Arm Statistical Profiling Extension (SPE) samples on supported
Arm systems. The next step analyzes the recorded `baseline.data` file.
