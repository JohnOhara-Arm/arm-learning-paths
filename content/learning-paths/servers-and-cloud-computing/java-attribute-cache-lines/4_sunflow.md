---
title: Review the Sunflow case study
weight: 5

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Attribute the hottest line

The methodology was developed while analyzing DaCapo Sunflow on an AWS m8g
bare-metal system. The workload used CPUs 0–7 and NUMA node 0. A mainline
userspace `perf` build was required to decode useful Arm SPE peer-line data.

The synchronized capture produced:

- 8,264,273 heap objects;
- 172 shared cache lines;
- 145,383 load hits on shared lines;
- 11,955 peer-cache or peer-node hits;
- 24 cache-line/object join rows.

The hottest joined line was `0x8e001840`:

| Object | Start | Size | Object bytes on the line |
| --- | ---: | ---: | ---: |
| `BucketRenderer$BucketThread` | `0x8e001800` | 120 | 64–120 |
| `KDTree` | `0x8e001878` | 32 | 0–8 |

JOL showed inherited `Thread` references and Sunflow fields in the tail of
`BucketThread`, followed immediately by the `KDTree` header. The correct
classification was `object_boundary_allocation_adjacency`. The evidence did
not claim that one object contained the other inline.

Other hot lines joined `BoundingIntervalHierarchy`, `Instance`,
`IntersectionState`, `PointLight`, and `Color` instances.

## Validate the intervention

Class-level `@Contended` was applied to six hot classes. One c2c-aligned m8g
comparison produced:

| Metric | Default | `@Contended` |
| --- | ---: | ---: |
| Runtime | 71.5870 s | 48.0599 s |
| Shared-line load hits | 140,098 | 4,619 |
| Peer hits | 7,817 | 676 |
| CMN snoop proxy | 4,946,717,884 | 1,259,429,765 |

A separate 20-pair experiment reported medians of 71.709496 seconds for the
default layout and 57.152489 seconds for the padded layout. The annotated
variant won 17 pairs.

Cross-platform medians showed different sensitivities: 4.065% on m8a,
12.755% on m8g, and 36.509% on m9g. One direct m8a c2c capture did not reduce
its counters despite a modest timing improvement in the larger run set.

![Twenty m9g Sunflow runs show that the six-hot @Contended layout lowers median runtime and greatly reduces the interquartile range.#center](_images/cacheline-object-join.svg "m9g Sunflow runtime distribution with and without @Contended")

On m9g, the median fell from 67.06 seconds to 42.58 seconds, a 36.5%
reduction. The interquartile range narrowed from 19.35 seconds to 0.61
seconds, so the padded layout was also substantially more consistent across
the 20 runs.

These measurements demonstrate the Sunflow intervention. They are not a
general expected speedup for Java, `@Contended`, or Arm processors. Preserve
mixed evidence and check memory and GC costs before retaining padding in an
application.
