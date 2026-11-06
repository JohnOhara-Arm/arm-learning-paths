#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --baseline-jar JAR --contended-jar JAR --janino-jar JAR --output DIR [--java-home DIR] [--timing-blocks N] [--memory-blocks N] [--jstat-interval-ms N] [--numa-node N]" >&2
  exit 2
}

java_home="${JAVA_HOME:-}"
baseline_jar=""
contended_jar=""
janino_jar=""
output=""
timing_blocks=24
memory_blocks=6
jstat_interval_ms=100
numa_node=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java-home) java_home="${2:?--java-home requires a value}"; shift 2 ;;
    --baseline-jar) baseline_jar="${2:?--baseline-jar requires a value}"; shift 2 ;;
    --contended-jar) contended_jar="${2:?--contended-jar requires a value}"; shift 2 ;;
    --janino-jar) janino_jar="${2:?--janino-jar requires a value}"; shift 2 ;;
    --output) output="${2:?--output requires a value}"; shift 2 ;;
    --timing-blocks) timing_blocks="${2:?--timing-blocks requires a value}"; shift 2 ;;
    --memory-blocks) memory_blocks="${2:?--memory-blocks requires a value}"; shift 2 ;;
    --jstat-interval-ms) jstat_interval_ms="${2:?--jstat-interval-ms requires a value}"; shift 2 ;;
    --numa-node) numa_node="${2:?--numa-node requires a value}"; shift 2 ;;
    *) usage ;;
  esac
done

if [[ -z "${java_home}" ]]; then
  java_home="$(java -XshowSettings:properties -version 2>&1 | \
    awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
fi

[[ -x "${java_home}/bin/java" && -x "${java_home}/bin/jstat" ]] || usage
[[ -f "${baseline_jar}" && -f "${contended_jar}" && -f "${janino_jar}" && -n "${output}" ]] || usage
[[ "${timing_blocks}" =~ ^[0-9]+$ && "${memory_blocks}" =~ ^[0-9]+$ ]] || usage
[[ "${jstat_interval_ms}" =~ ^[1-9][0-9]*$ && "${numa_node}" =~ ^[0-9]+$ ]] || usage
((timing_blocks + memory_blocks > 0 && timing_blocks % 6 == 0 && memory_blocks % 6 == 0)) || {
  echo "timing and memory block counts must be multiples of 6, and at least one must be nonzero" >&2
  exit 2
}
command -v numactl >/dev/null 2>&1 || { echo "numactl is required" >&2; exit 3; }
[[ -x /usr/bin/time ]] || { echo "GNU /usr/bin/time is required" >&2; exit 3; }
/usr/bin/time -v true >/dev/null 2>&1 || { echo "GNU /usr/bin/time with -v support is required" >&2; exit 3; }

timing_csv="${output}/timing-runs.csv"
memory_csv="${output}/memory-runs.csv"
if [[ -e "${timing_csv}" || -e "${memory_csv}" ]]; then
  echo "refusing to overwrite existing result CSV files in ${output}" >&2
  exit 3
fi
mkdir -p "${output}"/{metadata,timing/runs,memory/runs}

default_flags="${output}/metadata/contended-default-flags.txt"
explicit_64_flags="${output}/metadata/contended-64-flags.txt"
"${java_home}/bin/java" -XX:-RestrictContended -XX:+PrintFlagsFinal -version \
  > "${default_flags}" 2>&1
"${java_home}/bin/java" -XX:-RestrictContended -XX:ContendedPaddingWidth=64 \
  -XX:+PrintFlagsFinal -version > "${explicit_64_flags}" 2>&1

padding_width() {
  awk '$2 == "ContendedPaddingWidth" { print $4; exit }' "$1"
}
default_padding="$(padding_width "${default_flags}")"
explicit_64_padding="$(padding_width "${explicit_64_flags}")"
[[ "${default_padding}" == "128" ]] || {
  echo "the selected JDK default ContendedPaddingWidth is ${default_padding:-unknown}, not 128" >&2
  exit 3
}
[[ "${explicit_64_padding}" == "64" ]] || {
  echo "the selected JDK did not accept ContendedPaddingWidth=64" >&2
  exit 3
}

{
  uname -a
  echo "level1_dcache_line_bytes=$(getconf LEVEL1_DCACHE_LINESIZE 2>/dev/null || echo unknown)"
  echo "numa_node=${numa_node}"
  echo "timing_blocks=${timing_blocks}"
  echo "memory_blocks=${memory_blocks}"
  echo "jstat_interval_ms=${jstat_interval_ms}"
} > "${output}/metadata/system.txt"
"${java_home}/bin/java" --version > "${output}/metadata/java-version.txt" 2>&1
numactl --hardware > "${output}/metadata/numa-hardware.txt" 2>&1

echo 'block,order,variant,padding_mode,effective_padding_bytes,elapsed_seconds,status,accepted,validation' > "${timing_csv}"
echo 'block,order,variant,padding_mode,effective_padding_bytes,peak_heap_used_kb,max_rss_kb,sample_count,status,accepted,validation' > "${memory_csv}"

orders=(
  'baseline contended-default contended-64'
  'baseline contended-64 contended-default'
  'contended-default baseline contended-64'
  'contended-default contended-64 baseline'
  'contended-64 baseline contended-default'
  'contended-64 contended-default baseline'
)

variant_config() {
  local variant="${1:?variant required}"
  case "${variant}" in
    baseline)
      variant_jar="${baseline_jar}"
      padding_mode=none
      effective_padding_bytes=0
      variant_flags=()
      ;;
    contended-default)
      variant_jar="${contended_jar}"
      padding_mode=jvm-default
      effective_padding_bytes="${default_padding}"
      variant_flags=(-XX:-RestrictContended)
      ;;
    contended-64)
      variant_jar="${contended_jar}"
      padding_mode=explicit
      effective_padding_bytes="${explicit_64_padding}"
      variant_flags=(-XX:-RestrictContended -XX:ContendedPaddingWidth=64)
      ;;
    *)
      echo "unknown variant: ${variant}" >&2
      exit 3
      ;;
  esac
}

run_one() {
  local campaign="${1:?campaign required}"
  local block="${2:?block required}"
  local order="${3:?order required}"
  local variant="${4:?variant required}"
  variant_config "${variant}"

  local run_dir="${output}/${campaign}/runs/$(printf '%02d' "${block}")-${order}-${variant}"
  mkdir -p "${run_dir}"
  local command=(
    numactl --membind="${numa_node}"
    "${java_home}/bin/java"
    -Xms16g -Xmx16g -XX:+UseG1GC
    "${variant_flags[@]}"
    -cp "${variant_jar}:${janino_jar}"
    org.sunflow.Benchmark -bench 0 4096 80
  )
  printf '%q ' "${command[@]}" > "${run_dir}/command.txt"
  printf '\n' >> "${run_dir}/command.txt"

  local start_ns end_ns status elapsed accepted validation
  start_ns="$(date +%s%N)"
  if [[ "${campaign}" == "timing" ]]; then
    set +e
    "${command[@]}" > "${run_dir}/stdout.txt" 2> "${run_dir}/stderr.txt"
    status=$?
    set -e
  else
    local java_pid_file="${run_dir}/java.pid"
    set +e
    /usr/bin/time -v -o "${run_dir}/time.txt" \
      bash -c 'printf "%s\n" "$$" > "$1"; shift; exec "$@"' bash \
      "${java_pid_file}" "${command[@]}" \
      > "${run_dir}/stdout.txt" 2> "${run_dir}/stderr.txt" &
    local time_pid=$!
    set -e

    local attempts=0
    while [[ ! -s "${java_pid_file}" ]] && kill -0 "${time_pid}" 2>/dev/null && ((attempts < 100)); do
      sleep 0.01
      ((attempts += 1))
    done
    if [[ -s "${java_pid_file}" ]]; then
      local java_pid
      java_pid="$(< "${java_pid_file}")"
      set +e
      "${java_home}/bin/jstat" -gc "${java_pid}" "${jstat_interval_ms}" \
        > "${run_dir}/jstat-gc.txt" 2> "${run_dir}/jstat-gc.err" &
      local jstat_pid=$!
      wait "${time_pid}"
      status=$?
      wait "${jstat_pid}" >/dev/null 2>&1
      set -e
    else
      set +e
      wait "${time_pid}"
      status=$?
      set -e
      : > "${run_dir}/jstat-gc.txt"
      echo "could not obtain the Java PID before exit" > "${run_dir}/jstat-gc.err"
    fi
  fi
  end_ns="$(date +%s%N)"
  elapsed="$(awk -v start="${start_ns}" -v end="${end_ns}" 'BEGIN { printf "%.6f", (end-start)/1000000000 }')"

  accepted=0
  validation=unexpected_status
  if [[ "${status}" -eq 0 ]] && grep -q 'Image check passed' "${run_dir}/stdout.txt" "${run_dir}/stderr.txt"; then
    accepted=1
    validation=image_check_passed
  fi

  if [[ "${campaign}" == "timing" ]]; then
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "${block}" "${order}" "${variant}" "${padding_mode}" "${effective_padding_bytes}" \
      "${elapsed}" "${status}" "${accepted}" "${validation}" >> "${timing_csv}"
  else
    local heap_result peak_heap_used_kb sample_count max_rss_kb
    set +e
    heap_result="$(awk '
      NR == 1 {
        for (i = 1; i <= NF; i++) column_index[$i] = i
        next
      }
      NF > 0 {
        required[1] = "S0U"; required[2] = "S1U"; required[3] = "EU"; required[4] = "OU"
        used = 0
        for (i = 1; i <= 4; i++) {
          if (!(required[i] in column_index)) exit 2
          value = $(column_index[required[i]])
          if (value != "-") used += value
        }
        if (count == 0 || used > maximum) maximum = used
        count++
      }
      END { if (count > 0) printf "%.3f %d", maximum, count }
    ' "${run_dir}/jstat-gc.txt")"
    local heap_status=$?
    set -e
    if [[ "${heap_status}" -ne 0 ]]; then
      heap_result=""
    fi
    read -r peak_heap_used_kb sample_count <<< "${heap_result}"
    max_rss_kb="$(awk -F: '/Maximum resident set size \(kbytes\)/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' "${run_dir}/time.txt")"
    peak_heap_used_kb="${peak_heap_used_kb:-}"
    sample_count="${sample_count:-0}"
    max_rss_kb="${max_rss_kb:-}"
    if [[ -z "${peak_heap_used_kb}" || -z "${max_rss_kb}" ]]; then
      accepted=0
      validation=memory_measurement_missing
    fi
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "${block}" "${order}" "${variant}" "${padding_mode}" "${effective_padding_bytes}" \
      "${peak_heap_used_kb}" "${max_rss_kb}" "${sample_count}" "${status}" "${accepted}" "${validation}" \
      >> "${memory_csv}"
  fi

  printf 'elapsed_seconds=%s\nstatus=%s\naccepted=%s\nvalidation=%s\n' \
    "${elapsed}" "${status}" "${accepted}" "${validation}" > "${run_dir}/status.txt"
  if [[ "${accepted}" -ne 1 ]]; then
    echo "rejected ${campaign} ${variant} run in block ${block}; inspect ${run_dir}" >&2
    exit 4
  fi
}

run_campaign() {
  local campaign="${1:?campaign required}"
  local blocks="${2:?blocks required}"
  local block order variant
  local -a block_order
  for ((block=1; block<=blocks; block++)); do
    read -r -a block_order <<< "${orders[$(((block - 1) % 6))]}"
    for order in 1 2 3; do
      variant="${block_order[$((order - 1))]}"
      run_one "${campaign}" "${block}" "${order}" "${variant}"
    done
  done
}

run_campaign timing "${timing_blocks}"
run_campaign memory "${memory_blocks}"

echo "accepted ${timing_blocks} timing blocks and ${memory_blocks} memory blocks per variant"
echo "timing results: ${timing_csv}"
echo "memory results: ${memory_csv}"
