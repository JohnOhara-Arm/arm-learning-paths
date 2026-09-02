---
title: Validate object layout and Perf C2C
weight: 3

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Inspect the layout with JOL

[Java Object Layout (JOL)](https://github.com/openjdk/jol) is an OpenJDK tool
for inspecting layout details such as field offsets, padding, and total object
size in the JVM.

The JOL CLI is available from Maven Central. Download the
[JOL CLI 0.17 full JAR](https://repo.maven.apache.org/maven2/org/openjdk/jol/jol-cli/0.17/jol-cli-0.17-full.jar)
into the directory containing the compiled `FalseSharingDemo` classes:

```bash
curl -LO https://repo.maven.apache.org/maven2/org/openjdk/jol/jol-cli/0.17/jol-cli-0.17-full.jar
```

The `full` JAR includes the dependencies required by the JOL command-line
tool.

Use Java Object Layout (JOL) with the same JDK and relevant VM flags used for
the workload:

Baseline:

```bash
$ "$java_bin" -XX:-RestrictContended \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -cp jol-cli-0.17-full.jar:. org.openjdk.jol.Main internals \
  'FalseSharingDemo$BaselineCounters'
```

```bash
# VM mode: 64 bits
# Compressed references (oops): 3-bit shift
# Compressed class pointers: 3-bit shift
# WARNING | Compressed references base/shifts are guessed by the experiment!
# WARNING | Therefore, computed addresses are just guesses, and ARE NOT RELIABLE.
# WARNING | Make sure to attach Serviceability Agent to get the reliable addresses.
# Object alignment: 8 bytes
#                       ref, bool, byte, char, shrt,  int,  flt,  lng,  dbl
# Field sizes:            4,    1,    1,    2,    2,    4,    4,    8,    8
# Array element sizes:    4,    1,    1,    2,    2,    4,    4,    8,    8
# Array base offsets:    16,   16,   16,   16,   16,   16,   16,   16,   16

Instantiated the sample instance via default constructor.

FalseSharingDemo$BaselineCounters object internals:
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001 (non-biasable; age: 0)
  8   4        (object header: class)    0x01085290
 12   4        (alignment/padding gap)   
 16   8   long BaselineCounters.left     0
 24   8   long BaselineCounters.right    0
Instance size: 32 bytes
Space losses: 4 bytes internal + 0 bytes external = 4 bytes total

```

Padded:

```bash

$ "$java_bin" -XX:-RestrictContended \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -cp jol-cli-0.17-full.jar:. org.openjdk.jol.Main internals \
  'FalseSharingDemo$PaddedCounters'
```

```bash
# VM mode: 64 bits
# Compressed references (oops): 3-bit shift
# Compressed class pointers: 3-bit shift
# WARNING | Compressed references base/shifts are guessed by the experiment!
# WARNING | Therefore, computed addresses are just guesses, and ARE NOT RELIABLE.
# WARNING | Make sure to attach Serviceability Agent to get the reliable addresses.
# Object alignment: 8 bytes
#                       ref, bool, byte, char, shrt,  int,  flt,  lng,  dbl
# Field sizes:            4,    1,    1,    2,    2,    4,    4,    8,    8
# Array element sizes:    4,    1,    1,    2,    2,    4,    4,    8,    8
# Array base offsets:    16,   16,   16,   16,   16,   16,   16,   16,   16

Instantiated the sample instance via default constructor.

FalseSharingDemo$PaddedCounters object internals:
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001 (non-biasable; age: 0)
  8   4        (object header: class)    0x01085290
 12 132        (alignment/padding gap)   
144   8   long PaddedCounters.left       0
152 128        (alignment/padding gap)   
280   8   long PaddedCounters.right      0
Instance size: 288 bytes
Space losses: 260 bytes internal + 0 bytes external = 260 bytes total
```

### Compare the layouts

In this HotSpot configuration, the object header contains an 8-byte mark word
and a 4-byte compressed class pointer, giving a 12-byte header. HotSpot aligns
a `long` field to its natural 8-byte boundary. On this 64-bit platform, natural
alignment prevents the value from straddling two 8-byte words and supports
efficient aligned loads and stores. Because offset 12 is not divisible by 8,
HotSpot inserts the 4-byte `alignment/padding gap` reported by JOL, and the
first `long` starts at offset 16.

The padding does not make the object header itself 16 bytes. It is a gap between
the 12-byte header and the first field. Header size and field offsets can differ
when compressed class pointers or other VM layout settings change.

In the *baseline* layout, the two 8-byte counters are adjacent at offsets 16 and
24. Their proximity means that they can occupy the same 64-byte cache line.

In the *padded* layout, each field belongs to a different `@Contended` group.
With HotSpot's default 128-byte contention-padding width, JOL reports a
132-byte gap before `left`: 4 bytes for alignment and 128 bytes for contention
padding. A further 128-byte gap separates `left` and `right`, keeping them on
different cache lines.

The padding increases the object size from 32 bytes to 288 bytes, a ninefold
increase of 256 bytes per object. This extra space reduces cache-line ownership
transfers at the cost of greater heap usage. Record the offsets from your
output because object headers, alignment, and field layout can vary with the
JDK and VM configuration.

{{% notice Note %}}
HotSpot normally restricts `@Contended` in application classes. The
`-XX:-RestrictContended` option is required at run time for this example.
{{% /notice %}}

## Prove that Perf C2C can see the control

{{% notice Perf version %}}
On Neoverse V2 systems, use Perf 6.13 or later. Earlier versions can record SPE packets, but they don't decode Neoverse V2 data-source
values into peer-cache hits. For other Neoverse processors, confirm that your
Perf version supports the processor's SPE data-source encoding.
{{% /notice %}}

Record each mode with the same `perf` binary:

```bash
sudo perf c2c record -o baseline.data -- \
  taskset -c 0,1 "$java_bin" \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -XX:-RestrictContended FalseSharingDemo baseline

sudo perf c2c record -o padded.data -- \
  taskset -c 0,1 "$java_bin" \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -XX:-RestrictContended FalseSharingDemo padded
```

Generate comparable reports:

```bash
sudo perf c2c report --stdio -i baseline.data > baseline-c2c.txt
sudo perf c2c report --stdio -i padded.data > padded-c2c.txt
```

On Arm, inspect peer-cache or peer-node hits, shared cache lines, records,
loads, stores, CPUs, and access symbols. On x86, reports commonly emphasize
local and remote HITM events. These labels are not interchangeable PMU events,
but both provide evidence of inter-core cache-line sharing.

{{% notice Warning %}}
A zero-event perf report does not prove that false sharing is absent. If the slow
baseline produces no data; validate the SPE kernel module is loaded and available, check permissions, recording duration, and the userspace perf decoder before
re- profiling an application.
{{% /notice %}}
