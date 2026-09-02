#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${script_dir}"
mkdir -p Output/classes

java --version
javac -Xlint:all -d Output/classes \
  AnalyzeJavaCachelines.java AnalyzeJolAdjacency.java PagemapCsvDump.java

sa_flags=(
  --add-modules jdk.hotspot.agent
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot=ALL-UNNAMED
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot.debugger=ALL-UNNAMED
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot.oops=ALL-UNNAMED
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot.runtime=ALL-UNNAMED
)
javac "${sa_flags[@]}" -d Output/sa-classes HeapObjectCsvDump.java

java AnalyzeJavaCachelines.java \
  --collection testdata/sunflow-reduced \
  --output Output \
  --run-id sunflow \
  --address-domain virtual

join_rows="$(wc -l < Output/sunflow_cacheline_object_join.csv)"
[[ "${join_rows}" -eq 25 ]] || {
  echo "expected 24 join rows, found $((join_rows - 1))" >&2
  exit 1
}

java AnalyzeJolAdjacency.java \
  --join Output/sunflow_cacheline_object_join.csv \
  --jol-dir testdata/jol-internals \
  --source-root testdata/source \
  --output Output \
  --run-id sunflow

grep -Fq '0x8e001840,object_boundary_allocation_adjacency' \
  Output/sunflow_hot_cacheline_adjacency_summary.csv
grep -Fq 'org.sunflow.core.renderer.BucketRenderer$BucketThread -> org.sunflow.core.accel.KDTree' \
  Output/sunflow_hot_cacheline_adjacency_summary.csv
grep -Fq 'example.AllocationOwner' Output/sunflow_hot_cacheline_adjacency_summary.csv

java AnalyzeJavaCachelines.java \
  --collection testdata/physical \
  --output Output \
  --run-id physical \
  --address-domain auto
grep -Fq 'physical,0x2040,0,8,8,20,18,2,0x1040,16,0,"com.example.Counter,Variant",counter-1,' \
  Output/physical_cacheline_object_join.csv

echo "standalone Java analyzer validation passed"
