#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${script_dir}"
mkdir -p Output/classes

java --version
javac -Xlint:all -d Output/classes \
  analyze-java-cachelines.java analyze-jol-adjacency.java analyze-sunflow-runs.java pagemap-csv-dump.java

sa_flags=(
  --add-modules jdk.hotspot.agent
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot=ALL-UNNAMED
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot.debugger=ALL-UNNAMED
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot.oops=ALL-UNNAMED
  --add-exports jdk.hotspot.agent/sun.jvm.hotspot.runtime=ALL-UNNAMED
)
javac "${sa_flags[@]}" -d Output/sa-classes heap-object-csv-dump.java

java analyze-java-cachelines.java \
  --collection testdata/sunflow-reduced \
  --output Output \
  --run-id sunflow \
  --address-domain virtual

join_rows="$(wc -l < Output/sunflow_cacheline_object_join.csv)"
[[ "${join_rows}" -eq 25 ]] || {
  echo "expected 24 join rows, found $((join_rows - 1))" >&2
  exit 1
}

java analyze-jol-adjacency.java \
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

java analyze-java-cachelines.java \
  --collection testdata/physical \
  --output Output \
  --run-id physical \
  --address-domain auto
grep -Fq 'physical,0x2040,0,8,8,20,18,2,0x1040,16,0,"com.example.Counter,Variant",counter-1,' \
  Output/physical_cacheline_object_join.csv

java analyze-sunflow-runs.java --input testdata/sunflow-runs.csv \
  > Output/sunflow-run-summary.txt
grep -Fq 'baseline count=20 median_seconds=63 population_stdev_seconds=14 cv=0.20 p25_seconds=59 p75_seconds=82 iqr_seconds=23' \
  Output/sunflow-run-summary.txt
grep -Fq 'fixed count=20 median_seconds=55 population_stdev_seconds=3.3 cv=0.060 p25_seconds=54 p75_seconds=55 iqr_seconds=1.2' \
  Output/sunflow-run-summary.txt
grep -Fq 'median_percent_change=-13%' Output/sunflow-run-summary.txt

echo "standalone Java analyzer validation passed"
