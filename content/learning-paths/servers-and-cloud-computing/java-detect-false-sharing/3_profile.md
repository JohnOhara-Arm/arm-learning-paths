---
title: Profile the application
weight: 4

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Record a representative hot phase

Keep the JDK, input, heap size, thread count, CPU affinity, and NUMA policy
fixed. Start recording before the representative parallel phase:

```bash
sudo perf c2c record -o application.data -- \
  taskset -c 0-7 "$java_bin" \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  -XX:+PreserveFramePointer \
  -jar application.jar

sudo perf c2c report --stdio -i application.data > application-c2c.txt
```

Rank lines using direct sharing evidence, not the size or allocation frequency
of a Java class. Preserve the line address, peer or HITM counts, records,
loads, stores, sampled CPUs, and access PCs.

## Decide whether the address identifies an object

Perf C2C identifies contended addresses; it does not understand Java heap
objects. JOL explains a class layout but does not identify the address of a
particular live instance.

If symbols and the layout are sufficient to identify independently written
fields, continue to the mitigation. If you need to prove which objects
occupied a hot line, follow the companion Learning Path, **Attribute contended
cache lines to Java heap objects**.

The deeper workflow pauses the JVM, records live object addresses with the
HotSpot Serviceability Agent, establishes whether the c2c address is virtual
or physical, and joins the two evidence sets within one object-placement
epoch.
