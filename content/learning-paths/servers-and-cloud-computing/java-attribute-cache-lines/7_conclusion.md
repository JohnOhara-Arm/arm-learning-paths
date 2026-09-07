---
title: Review the cache-line attribution workflow
description: Summarize how address attribution, JOL, @Contended, Perf C2C, and repeated measurements improved direct Sunflow execution.
weight: 8

### FIXED, DO NOT MODIFY
layout: learningpathall
---

## What you did

You extended the small-example workflow from the Java false-sharing Learning Path to a real renderer:

1. You ran Sunflow directly and captured Perf C2C samples.
2. You paused the same JVM placement epoch and recorded virtual-memory mappings and live object addresses with HotSpot SA.
3. You joined the hot cache-line address to object ranges and used JOL to interpret the overlapping layouts.
4. You traced hot boundaries to six Sunflow classes and isolated them with `@Contended`.
5. You repeated Perf C2C and compared shared-line and peer-hit counts with the baseline.
6. You tested the six-class evidence-derived patch over 20 alternating pairs.

On the reference Neoverse V2 system, the six-class fixed variant reduced median runtime from `62.573799` to `54.592332` seconds, a `12.755%` improvement. Population standard deviation fell from `14.080178` to `3.310145` seconds, so the fixed runs were also substantially more consistent.

## Apply the workflow carefully

The key result is the method, not the literal classes or addresses. Object addresses change on every run, `@Contended` trades memory for isolation, and an annotation can alter allocation and garbage-collection behavior. Repeat the full attribution and validation workflow for another JDK, heap configuration, machine, or workload.

Perf C2C identifies suspicious sharing, the live object map attributes it, and JOL explains layout. Only a controlled fixed capture and repeated timings establish whether the source change reduced contention and improved application behavior.

You can now trace a contended cache line from hardware evidence back to Java objects and validate whether isolating those objects delivers a measurable application-level benefit.
