---
title: Attribute contended cache lines to Java heap objects
minutes_to_complete: 60

who_is_this_for: Java performance engineers who need to prove which live heap objects and fields occupy addresses reported by Perf C2C.

description: Join Arm SPE cache-line samples to HotSpot heap objects using a synchronized Serviceability Agent and pagemap snapshot.

learning_objectives:
  - Keep Perf C2C samples and heap addresses in one object-placement epoch
  - Distinguish virtual and physical cache-line address domains
  - Join hot lines to live Java objects with dependency-free Java tools
  - Classify field sharing and adjacent-object placement using JOL

prerequisites:
  - Completion of Detect and resolve false sharing in Java
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
  - OpenJDK 21
  - Perf
  - Java Object Layout

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
