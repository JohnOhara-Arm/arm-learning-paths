#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --output DIR [--java-home DIR] [--source-archive ZIP] [--janino-jar JAR]" >&2
  exit 2
}

java_home="${JAVA_HOME:-}"
output=""
source_archive=""
janino_input=""
source_url="https://download.dacapobench.org/chopin/src/sunflow-src-v0.07.2.zip"
expected_md5="aaaa162cf76cfdbc29381406c08671a9"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java-home) java_home="${2:?--java-home requires a value}"; shift 2 ;;
    --output) output="${2:?--output requires a value}"; shift 2 ;;
    --source-archive) source_archive="${2:?--source-archive requires a value}"; shift 2 ;;
    --janino-jar) janino_input="${2:?--janino-jar requires a value}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -n "${output}" ]] || usage
if [[ -z "${java_home}" ]]; then
  java_home="$(java -XshowSettings:properties -version 2>&1 | \
    awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
fi
[[ -x "${java_home}/bin/java" && -x "${java_home}/bin/javac" && \
   -x "${java_home}/bin/jar" && -x "${java_home}/bin/javap" ]] || {
  echo "cannot detect a complete JDK; set JAVA_HOME or supply --java-home" >&2
  exit 3
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "${output}"
output="$(cd "${output}" && pwd)"
mkdir -p "${output}"/{archives,classes,jars,sources,verification}

if [[ -z "${source_archive}" ]]; then
  source_archive="${output}/archives/sunflow-src-v0.07.2.zip"
  if [[ ! -f "${source_archive}" ]]; then
    curl -fsSL "${source_url}" -o "${source_archive}"
  fi
fi
source_archive="$(cd "$(dirname "${source_archive}")" && pwd)/$(basename "${source_archive}")"

if command -v md5sum >/dev/null 2>&1; then
  actual_md5="$(md5sum "${source_archive}" | awk '{print $1}')"
elif command -v md5 >/dev/null 2>&1; then
  actual_md5="$(md5 -q "${source_archive}")"
else
  echo "md5sum or md5 is required to verify the source archive" >&2
  exit 4
fi
[[ "${actual_md5}" == "${expected_md5}" ]] || {
  echo "Sunflow archive MD5 mismatch: expected ${expected_md5}, found ${actual_md5}" >&2
  exit 5
}

extract_variant() {
  local variant="${1:?variant required}"
  local destination="${output}/sources/${variant}"
  mkdir -p "${destination}"
  unzip -q "${source_archive}" -d "${output}/sources/${variant}-extract"
  mv "${output}/sources/${variant}-extract/sunflow/"* "${destination}/"
  rmdir "${output}/sources/${variant}-extract/sunflow" "${output}/sources/${variant}-extract"
  find "${destination}/src" -name '*.java' -exec sed -i.bak $'s/\r$//' {} +
  find "${destination}/src" -name '*.bak' -delete
  (cd "${destination}" && patch -p1 < "${script_dir}/sunflow-reference.patch")
}

extract_variant baseline
extract_variant six-class-contended
(cd "${output}/sources/six-class-contended" && patch -p1 < "${script_dir}/sunflow-six-class-contended.patch")

if [[ -n "${janino_input}" ]]; then
  cp "${janino_input}" "${output}/janino.jar"
elif [[ -f "${output}/sources/baseline/janino.jar" ]]; then
  cp "${output}/sources/baseline/janino.jar" "${output}/janino.jar"
else
  echo "the archive contains no janino.jar; supply --janino-jar" >&2
  exit 6
fi

compile_variant() {
  local variant="${1:?variant required}"
  local source_dir="${output}/sources/${variant}"
  local class_dir="${output}/classes/${variant}"
  mkdir -p "${class_dir}"
  find "${source_dir}/src" -name '*.java' -print | sort > "${output}/classes/${variant}.sources"
  "${java_home}/bin/javac" \
    --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
    -cp "${output}/janino.jar" \
    -d "${class_dir}" \
    @"${output}/classes/${variant}.sources"
}

compile_variant baseline

# The source release does not contain a 4096-pixel golden image. Generate it with
# the same thread count as the benchmark and package it in every variant.
(cd "${output}/sources/baseline" && \
  "${java_home}/bin/java" -cp "${output}/classes/baseline:${output}/janino.jar" \
  org.sunflow.Benchmark -regen 4096 8)
golden="${output}/sources/baseline/resources/golden_1000.png"
[[ -s "${golden}" ]] || {
  echo "Sunflow did not generate ${golden}" >&2
  exit 7
}
cp "${golden}" "${output}/sources/six-class-contended/resources/golden_1000.png"

compile_variant six-class-contended

for variant in baseline six-class-contended; do
  "${java_home}/bin/jar" --create \
    --file "${output}/jars/sunflow-${variant}.jar" \
    -C "${output}/classes/${variant}" . \
    -C "${output}/sources/${variant}" resources
done

# Confirm that the direct entry point in the packaged jar accepts the
# 4096-pixel workload's error-threshold argument. This catches stale jars.
"${java_home}/bin/java" \
  -cp "${output}/jars/sunflow-baseline.jar:${output}/janino.jar" \
  org.sunflow.Benchmark > "${output}/verification/benchmark-options.txt"
grep -q '\[error-threshold\]' "${output}/verification/benchmark-options.txt" || {
  echo "packaged benchmark does not expose the error-threshold argument" >&2
  exit 8
}

verify_annotation() {
  local jar="${1:?jar required}"
  local class_name="${2:?class required}"
  local label="${3:?label required}"
  "${java_home}/bin/javap" -v -classpath "${jar}" "${class_name}" \
    > "${output}/verification/${label}.javap.txt"
  grep -q 'jdk.internal.vm.annotation.Contended' "${output}/verification/${label}.javap.txt"
}

six_jar="${output}/jars/sunflow-six-class-contended.jar"
verify_annotation "${six_jar}" 'org.sunflow.core.renderer.BucketRenderer$BucketThread' six-class-BucketThread
verify_annotation "${six_jar}" org.sunflow.core.accel.KDTree six-class-KDTree
verify_annotation "${six_jar}" 'org.sunflow.core.gi.InstantGI$PointLight' six-class-PointLight
verify_annotation "${six_jar}" org.sunflow.image.Color six-class-Color
verify_annotation "${six_jar}" org.sunflow.core.Instance six-class-Instance
verify_annotation "${six_jar}" org.sunflow.core.accel.BoundingIntervalHierarchy six-class-BIH

echo "Sunflow jars are ready in ${output}/jars"
