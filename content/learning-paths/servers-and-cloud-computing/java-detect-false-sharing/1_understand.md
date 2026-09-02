---
title: Understand Java false sharing
weight: 2

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Understand the cache-line effect

Caches transfer data and maintain coherence at the granularity of cache lines.
A 64-byte cache line is common on Arm Neoverse servers, but the line size is
implementation-dependent. When one core writes to a location, the coherence
protocol generally grants it exclusive ownership of the complete line and
invalidates copies held by other cores.

Processors maintain cache coherence for complete cache lines rather than
individual Java objects or fields. The JVM determines field layout, while the
allocator determines where an object resides in the heap. A moving garbage
collector can later relocate it. As a result, one cache line can contain fields
from one object or parts of multiple objects.

Cache-line sharing occurs when multiple cores access data in the same cache
line and at least one access is a write. True sharing occurs when threads
communicate through the same variable. False sharing occurs when threads access
different variables that occupy the same cache line. The Java program treats
the variables as independent, but the hardware still maintains coherence for
their common cache line.

![Two cores write independent Java values in one cache line, repeatedly transferring line ownership.#center](_images/false-sharing-cache-line.svg "False sharing transfers ownership of the complete cache line")

Java applications can encounter false sharing in three common forms:

- Independently written fields within one object
- Independently allocated objects placed on the same line
- Array elements updated by different workers

Object headers, inheritance, compressed references, field layout, object
alignment, allocation order, and garbage collection all influence the result.

## Create controlled examples

Download [FalseSharingDemo.java](../FalseSharingDemo.java). It contains a
baseline with adjacent volatile counters and a padded comparison in which each
counter has a separate `@Contended` group. The baseline fields can share a
cache line, but ordinary Java objects are not guaranteed to begin at a cache-line
boundary. Confirm the sharing behavior with Perf C2C rather than inferring it
from field adjacency alone.

Resolve the absolute Java executable path before running the examples. Derive
`javac_bin` from the same JDK so that compilation and execution use matching
versions. The absolute path also avoids relying on the restricted `PATH` used
by `sudo` when Perf launches the Java workload:

```bash
java_bin=$(readlink -f "$(command -v java)")
javac_bin="$(dirname "$java_bin")/javac"

"$java_bin" -version
"$javac_bin" -version
```

Keep using the same terminal session throughout this Learning Path. If you
open a new terminal, run these assignments again.

Compile the example with access to the HotSpot annotation:

```bash
"$javac_bin" \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  FalseSharingDemo.java
```

The following commands use logical CPUs 0 and 1. Confirm that both CPUs are in
the current shell's permitted affinity list:

```bash
taskset -pc $$
```

If needed, replace `0,1` with two online CPUs from the reported list. Run both
modes with the same CPU affinity:

```bash
taskset -c 0,1 "$java_bin" \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -XX:-RestrictContended FalseSharingDemo baseline

taskset -c 0,1 "$java_bin" \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -XX:-RestrictContended FalseSharingDemo padded
```

`taskset` restricts the JVM and its threads to the selected logical CPUs. It
does not assign one Java writer thread to each CPU, so either thread can migrate
within the permitted set. Check the sampled CPU information when migrations
could affect your result.

When the baseline counters occupy one cache line and the workers run on
different CPUs, the baseline typically takes considerably longer than the
padded run because ownership repeatedly transfers between CPUs. If the timings
are similar, object placement, scheduling, or system noise might have weakened
the false-sharing signal.

{{% notice Note %}}
Run alternating baseline and padded pairs. Do not draw a conclusion from one
timing comparison.
{{% /notice %}}
