---
title: Apply and verify @Contended
weight: 5

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Select the narrowest intervention

For independently written fields inside one object, assign fields that should
be isolated to different contention groups:

```java
import jdk.internal.vm.annotation.Contended;

final class WorkerCounters {
    @Contended("producer") volatile long produced;
    @Contended("consumer") volatile long consumed;
}
```

For separate objects repeatedly placed across the same allocation-boundary
line, class-level padding can isolate each instance:

```java
import jdk.internal.vm.annotation.Contended;

@Contended
final class WorkerState {
    volatile long progress;
}
```

Compile with the package export and run with unrestricted application
annotations:

```bash
"$javac_bin" \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  WorkerState.java

"$java_bin" --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -XX:-RestrictContended -jar application.jar
```

Verify the annotation with `javap -v` and the effective layout with JOL.
`@Contended` is an internal HotSpot API, so recheck the build after a JDK
upgrade.

## Verify the complete result

Repeat the measurement using this checklist:

1. Alternate baseline and mitigated runs under identical placement and JVM settings.
2. Compare medians and variability from enough paired repetitions.
3. Record both versions using the same `perf` binary and sampling settings.
4. Confirm that the original hot line disappears or its normalized sharing signal declines.
5. Check throughput or latency as appropriate for the application.
6. Check allocation rate, heap occupancy, and GC time for padding regressions.

Padding can improve coherence behavior while increasing memory footprint.
Apply it only to objects supported by strong evidence, especially when a class
has millions of live instances.
