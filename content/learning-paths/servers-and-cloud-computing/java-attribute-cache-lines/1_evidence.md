---
title: Making sense of the address evidence
description: Connect Perf C2C addresses to virtual memory mappings, live heap objects, and JOL layouts without crossing a JVM object-placement epoch.
weight: 2

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## Translate cache lines to Java objects

Perf C2C ranks cache-line addresses and the memory accesses associated with them. It cannot map any Java objects at a particular cache line address. A conventional HPROF heap dump can identify classes, references, and retained sizes, but its object identifiers are not normally live virtual addresses.

This Learning Path uses an address-bearing heap-object dump from the HotSpot Serviceability Agent (SA). The collector pauses the JVM and records each object's virtual address, size, and class. It also captures the process memory map and Linux `pagemap` rows before resuming the JVM.

In order to map the `perf c2c` cache line hot cache lines back to Java Objects requires a number of address translations:

![Perf C2C evidence and a paused JVM snapshot feed an address join, after which JOL identifies the overlapping fields and object boundaries.#center](_images/cacheline-object-join.svg "Cache-line address attribution workflow")

There are four data sources required to be able to perform the mapping:

| Data Source | What it contributes |
| --- | --- |
| `perf c2c report` and `perf script` | Hot cache line, sharing counts, access PC, CPU, and virtual or physical address |
| `/proc/<pid>/maps` and `pagemap` | Virtual memory areas and virtual-page to physical-frame mappings |
| HotSpot SA object dump | Object virtual address, size, and class |
| JOL `internals` output | Field offsets within each class layout |


Perf C2C can expose a virtual address, a physical address, or both, depending on the event and `perf` decoder. The analyzer in this Learning Path tests both domains and accepts an automatic choice only when one domain produces matches.

For a physical address, calculate the cache-line base with:

```text
physical_address = pfn * page_size + offset_within_virtual_page
cache_line       = physical_address & ~(cache_line_size - 1)
```

## Preserve one placement Epoch

The samples, memory mapping, and object dump must describe the same placement of objects. If any of the following events occur while capturing the data sources, the result will be inaccurate:

- A moving or compacting garbage collection occurred between sampling and the snapshot
- The JVM exited before the object scan
- Linux hid page-frame numbers when a physical join was needed
- The SA binaries did not match the target JDK
- Both address domains appear plausible and independent evidence cannot resolve them

Use a fixed heap large enough to avoid collection during the hot window and record garbage-collection logs. A workload-controlled pause is preferable, but the supplied collector uses a timed pause so it can work with an unmodified application.

{{% notice Warning %}}
A precise-looking address match is invalid if it crosses an object-moving garbage collection. Do no reuse object addresses between JVM runs.
{{% /notice %}}

## What you've learned

You now know why an HPROF identifier cannot be joined directly to Perf C2C and why the live object map, address domain, and placement epoch matter. Next, you will prepare the exact Sunflow source used by the reference experiment.
