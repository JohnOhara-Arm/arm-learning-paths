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

java analyze-sunflow-runs.java \
  --timing testdata/sunflow-three-variant-timing-synthetic.csv \
  --memory testdata/sunflow-three-variant-memory-synthetic.csv \
  > Output/sunflow-run-summary.txt
grep -Fq 'baseline timing count=6 median_seconds=12.500 population_stdev_seconds=1.708 cv=0.1366 p25_seconds=11.250 p75_seconds=13.750 iqr_seconds=2.500' \
  Output/sunflow-run-summary.txt
grep -Fq 'contended-default_vs_baseline timing median_percent_change=-16.00% paired_wins=6/6' \
  Output/sunflow-run-summary.txt
grep -Fq 'contended-64_vs_contended-default timing median_percent_change=-9.52% paired_wins=6/6' \
  Output/sunflow-run-summary.txt
grep -Fq 'contended-default memory count=6 median_peak_heap_used_kb=205.00 median_max_rss_kb=285.00' \
  Output/sunflow-run-summary.txt
grep -Fq 'contended-64_vs_baseline memory median_peak_heap_percent_change=32.00% median_max_rss_percent_change=13.33%' \
  Output/sunflow-run-summary.txt

java analyze-sunflow-runs.java \
  --timing testdata/sunflow-m8g-timing-runs.csv \
  --memory testdata/sunflow-m8g-memory-runs.csv \
  > Output/sunflow-m8g-run-summary.txt
grep -Fq 'baseline timing count=24 median_seconds=18.427 population_stdev_seconds=5.424 cv=0.2732 p25_seconds=15.459 p75_seconds=25.545 iqr_seconds=10.085' \
  Output/sunflow-m8g-run-summary.txt
grep -Fq 'contended-64_vs_contended-default timing median_percent_change=-7.72% paired_wins=20/24' \
  Output/sunflow-m8g-run-summary.txt
grep -Fq 'contended-default_vs_baseline memory median_peak_heap_percent_change=32.37% median_max_rss_percent_change=32.45%' \
  Output/sunflow-m8g-run-summary.txt
grep -Fq 'contended-64_vs_contended-default memory median_peak_heap_percent_change=-14.75% median_max_rss_percent_change=-13.24%' \
  Output/sunflow-m8g-run-summary.txt

bash -n run-sunflow-variants.sh

echo "standalone Java analyzer validation passed"
