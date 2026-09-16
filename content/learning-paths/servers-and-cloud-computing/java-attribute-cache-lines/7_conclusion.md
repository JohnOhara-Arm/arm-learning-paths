---
title: Review the cache-line attribution workflow
description: Summarize how address attribution, JOL, @Contended, Perf C2C, and repeated measurements evaluate direct Sunflow execution.
weight: 8

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## What you did

You extended the small-example workflow from the Java false-sharing Learning Path to a real renderer:

1. You ran Sunflow directly and captured Perf C2C samples.
2. You paused the same JVM placement epoch and recorded virtual-memory mappings and live object addresses with HotSpot SA.
3. You joined the hot cache-line address to object ranges and used JOL to interpret the overlapping layouts.
4. You traced hot boundaries to seven concrete Sunflow classes and isolated their instances with `@Contended`.
5. You repeated Perf C2C and compared shared-line and peer-hit counts with the baseline.
6. You compared baseline, JVM-default padding, and explicit 64-byte padding with 24 balanced timing runs and six memory runs per variant.

On the reference system, the median fell from `18.427` seconds for baseline to `8.880` seconds with the JVM's default 128-byte padding and `8.195` seconds with explicit 64-byte padding. The default padding increased median sampled peak heap by `32.37%` over baseline. Explicit 64-byte padding reduced that overhead to `12.84%` and used `14.75%` less sampled peak heap than the default. Every run exited with status `0` and passed image validation.

## Apply the workflow carefully

The key result is the method, not the literal classes or addresses. Object addresses change on every run, `@Contended` trades memory for isolation, and an annotation can alter allocation and garbage-collection behavior. Repeat the full attribution and validation workflow for another JDK, heap configuration, machine, or workload.

Perf C2C identifies suspicious sharing, the live object map attributes it, and JOL explains layout. Only a controlled fixed capture and repeated timings establish whether the source change reduced contention and improved application behavior.

You can now trace a contended cache line from hardware evidence back to Java objects and validate whether isolating those objects delivers a measurable application-level benefit.
