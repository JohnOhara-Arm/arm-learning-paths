#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --baseline-jar JAR --fixed-jar JAR --janino-jar JAR --output DIR [--java-home DIR] [--pairs N] [--cpus LIST] [--numa-node N]" >&2
  exit 2
}

java_home="${JAVA_HOME:-}"
baseline_jar=""
fixed_jar=""
janino_jar=""
output=""
pairs=20
cpus="0-7"
numa_node=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java-home) java_home="${2:?--java-home requires a value}"; shift 2 ;;
    --baseline-jar) baseline_jar="${2:?--baseline-jar requires a value}"; shift 2 ;;
    --fixed-jar) fixed_jar="${2:?--fixed-jar requires a value}"; shift 2 ;;
    --janino-jar) janino_jar="${2:?--janino-jar requires a value}"; shift 2 ;;
    --output) output="${2:?--output requires a value}"; shift 2 ;;
    --pairs) pairs="${2:?--pairs requires a value}"; shift 2 ;;
    --cpus) cpus="${2:?--cpus requires a value}"; shift 2 ;;
    --numa-node) numa_node="${2:?--numa-node requires a value}"; shift 2 ;;
    *) usage ;;
  esac
done

if [[ -z "${java_home}" ]]; then
  java_home="$(java -XshowSettings:properties -version 2>&1 | \
    awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
fi
[[ -x "${java_home}/bin/java" && -f "${baseline_jar}" && -f "${fixed_jar}" && -f "${janino_jar}" && -n "${output}" ]] || usage
[[ "${pairs}" =~ ^[1-9][0-9]*$ ]] || usage
command -v numactl >/dev/null 2>&1 || { echo "numactl is required" >&2; exit 3; }

mkdir -p "${output}/runs"
csv="${output}/runs.csv"
echo 'pair,order,variant,elapsed_seconds,status,accepted,validation' > "${csv}"

run_one() {
  local pair="${1:?pair required}"
  local order="${2:?order required}"
  local variant="${3:?variant required}"
  local jar
  if [[ "${variant}" == "baseline" ]]; then jar="${baseline_jar}"; else jar="${fixed_jar}"; fi
  local run_dir
  run_dir="${output}/runs/$(printf '%02d' "${pair}")-${order}-${variant}"
  mkdir -p "${run_dir}"
  local command=(
    numactl --physcpubind="${cpus}" --membind="${numa_node}"
    "${java_home}/bin/java"
    -XX:ActiveProcessorCount=8 -XX:-RestrictContended
    -cp "${jar}:${janino_jar}"
    org.sunflow.Benchmark -bench 8 4096 80
  )
  printf '%q ' "${command[@]}" > "${run_dir}/command.txt"
  printf '\n' >> "${run_dir}/command.txt"

  local start_ns end_ns status elapsed accepted validation
  start_ns="$(date +%s%N)"
  set +e
  "${command[@]}" > "${run_dir}/stdout.txt" 2> "${run_dir}/stderr.txt"
  status=$?
  set -e
  end_ns="$(date +%s%N)"
  elapsed="$(awk -v start="${start_ns}" -v end="${end_ns}" 'BEGIN { printf "%.6f", (end-start)/1000000000 }')"
  accepted=0
  validation=unexpected_status
  if [[ "${status}" -eq 0 ]] && grep -q 'Image check passed' "${run_dir}/stdout.txt" "${run_dir}/stderr.txt"; then
    accepted=1
    validation=image_check_passed
  fi
  printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "${pair}" "${order}" "${variant}" "${elapsed}" "${status}" "${accepted}" "${validation}" >> "${csv}"
  printf 'elapsed_seconds=%s\nstatus=%s\naccepted=%s\nvalidation=%s\n' \
    "${elapsed}" "${status}" "${accepted}" "${validation}" > "${run_dir}/status.txt"
  if [[ "${accepted}" -ne 1 ]]; then
    echo "rejected ${variant} run in pair ${pair}; inspect ${run_dir}" >&2
    exit 4
  fi
}

for ((pair=1; pair<=pairs; pair++)); do
  if ((pair % 2 == 1)); then
    run_one "${pair}" 1 baseline
    run_one "${pair}" 2 fixed
  else
    run_one "${pair}" 1 fixed
    run_one "${pair}" 2 baseline
  fi
done

echo "accepted ${pairs} baseline/fixed pairs; results: ${csv}"
