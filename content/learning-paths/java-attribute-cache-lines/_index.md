---
title: Java - Trace cache line contention
minutes_to_complete: 60

who_is_this_for: Java performance engineers who need to identify which live objects on the  heap occupy addresses reported by Perf C2C and validate a source-level mitigation.

description: Trace contended Perf C2C addresses to live Sunflow objects, apply @Contended, and compare contention and runtime variability on Arm.

learning_objectives:
  - Explain how Perf C2C addresses, virtual memory mappings, live heap-object data, and JOL fit together
  - Capture cache-line and heap-object evidence from one Sunflow object-placement epoch
  - Trace contended cache lines to Java classes and apply targeted @Contended annotations
  - Verify the change with Perf C2C and repeated runtime measurements

prerequisites:
  - Completion of [Detect and resolve false sharing in Java](/learning-paths/servers-and-cloud-computing/java-detect-false-sharing/)
  - Experience with Linux perf and JVM diagnostic tools
  - Root, CAP_PERFMON, and ptrace access to the target JVM
  - An Arm Neoverse Linux system that exposes SPE to perf

author:
  - John O'Hara

generate_summary_faq: true
rerun_summary: false
rerun_faqs: false

skilllevels: Advanced
subjects: Performance and Architecture
armips:
  - Neoverse

tools_software_languages:
  - OpenJDK
  - Perf
  - Java Object Layout
  - Java

operatingsystems:
  - Linux

further_reading:
  - resource:
      title: HotSpot Serviceability Agent source
      link: https://github.com/openjdk/jdk/tree/master/src/jdk.hotspot.agent
      type: documentation
  - resource:
      title: Linux pagemap documentation
      link: https://github.com/torvalds/linux/blob/master/Documentation/admin-guide/mm/pagemap.rst
      type: documentation

weight: 1
layout: "learningpathall"
learning_path_main_page: "yes"
---

The [Java false-sharing Learning Path](/learning-paths/servers-and-cloud-computing/java-detect-false-sharing/) uses a small program to show how Perf C2C and Java Object Layout (JOL) expose cache-line contention. Real applications add another problem: Perf C2C reports an address, but Java source code refers to objects and fields.

You will bridge that gap with the Sunflow renderer. You will run Sunflow directly, capture a hot cache line and a live heap-object map, join the address to concrete Java classes, and use JOL to interpret their layouts. You will then add `@Contended`, repeat the capture, and compare 20 baseline and fixed runs.

The reference measurements use an AWS `m8g.metal-48xl` system with eight Neoverse V2 CPUs bound to NUMA node 0. Your absolute timings will vary, but the same evidence chain applies to another Arm Neoverse system with Statistical Profiling Extension (SPE) support.
