#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --output DIR [--java-home DIR] [--perf PATH] [--snapshot-after SEC] [--period N] -- COMMAND [ARGS...]" >&2
  exit 2
}

output=""
java_home="${JAVA_HOME:-}"
perf_bin="${PERF:-perf}"
snapshot_after=20
period=100000

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) output="${2:?--output requires a value}"; shift 2 ;;
    --java-home) java_home="${2:?--java-home requires a value}"; shift 2 ;;
    --perf) perf_bin="${2:?--perf requires a value}"; shift 2 ;;
    --snapshot-after) snapshot_after="${2:?--snapshot-after requires a value}"; shift 2 ;;
    --period) period="${2:?--period requires a value}"; shift 2 ;;
    --) shift; break ;;
    *) usage ;;
  esac
done

[[ -n "${output}" && $# -gt 0 ]] || usage
if [[ -z "${java_home}" ]]; then
  java_home="$(java -XshowSettings:properties -version 2>&1 | \
    awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }')"
fi
[[ -x "${java_home}/bin/java" && -x "${java_home}/bin/jcmd" ]] || {
  echo "cannot detect a complete JDK; set JAVA_HOME or supply --java-home" >&2
  exit 3
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "${output}"/{c2c,heap,jcmd,run,snapshot}

java_pid=""
resume_if_stopped() {
  if [[ -n "${java_pid}" ]] && kill -0 "${java_pid}" 2>/dev/null; then
    kill -CONT "${java_pid}" 2>/dev/null || true
  fi
}
trap resume_if_stopped EXIT INT TERM

printf '%q ' "$@" > "${output}/run/command.txt"
printf '\n' >> "${output}/run/command.txt"
"$@" > "${output}/run/stdout.txt" 2> "${output}/run/stderr.txt" &
java_pid=$!
echo "${java_pid}" > "${output}/run/java.pid"

"${perf_bin}" c2c record -u -g -c "${period}" -o "${output}/c2c/perf.data" -p "${java_pid}" \
  > "${output}/c2c/c2c-record.out" 2> "${output}/c2c/c2c-record.err" &
c2c_pid=$!

sleep "${snapshot_after}"
if ! kill -0 "${java_pid}" 2>/dev/null; then
  echo "JVM exited before the snapshot" > "${output}/snapshot/snapshot-missed.txt"
else
  kill -STOP "${java_pid}"
  sleep 0.2
  cp "/proc/${java_pid}/maps" "${output}/snapshot/maps.txt"
  cp "/proc/${java_pid}/smaps" "${output}/snapshot/smaps.txt" 2>/dev/null || true
  cp "/proc/${java_pid}/numa_maps" "${output}/snapshot/numa_maps.txt" 2>/dev/null || true
  cp "/proc/${java_pid}/status" "${output}/snapshot/status.txt"

  sudo -n "${java_home}/bin/java" -Dpagemap.pageSize="$(getconf PAGESIZE)" \
    "${script_dir}/PagemapCsvDump.java" \
    --pid "${java_pid}" --out "${output}/snapshot/pagemap-heap.csv" \
    > "${output}/snapshot/pagemap.out" 2> "${output}/snapshot/pagemap.err" || \
    echo "pagemap capture failed; check pagemap.err" > "${output}/snapshot/pagemap-error.txt"

  sa_flags=(
    --add-modules jdk.hotspot.agent
    --add-exports jdk.hotspot.agent/sun.jvm.hotspot=ALL-UNNAMED
    --add-exports jdk.hotspot.agent/sun.jvm.hotspot.debugger=ALL-UNNAMED
    --add-exports jdk.hotspot.agent/sun.jvm.hotspot.oops=ALL-UNNAMED
    --add-exports jdk.hotspot.agent/sun.jvm.hotspot.runtime=ALL-UNNAMED
  )
  sudo -n "${java_home}/bin/java" "${sa_flags[@]}" "${script_dir}/HeapObjectCsvDump.java" \
    --pid "${java_pid}" --out "${output}/heap/heap-objects.csv" \
    > "${output}/heap/heap-dumper.out" 2> "${output}/heap/heap-dumper.err" || \
    echo "SA heap scan failed; check heap-dumper.err and use a matching JDK" > "${output}/heap/heap-objects-error.txt"

  kill -CONT "${java_pid}"
  "${java_home}/bin/jcmd" "${java_pid}" VM.flags > "${output}/jcmd/vm-flags.txt" 2>&1 || true
  "${java_home}/bin/jcmd" "${java_pid}" GC.heap_info > "${output}/jcmd/gc-heap-info.txt" 2>&1 || true
  "${java_home}/bin/jcmd" "${java_pid}" GC.class_histogram > "${output}/jcmd/class-histogram.txt" 2>&1 || true
  "${java_home}/bin/jcmd" "${java_pid}" Thread.print -l > "${output}/jcmd/thread-print.txt" 2>&1 || true
fi

set +e
wait "${java_pid}"
java_status=$?
wait "${c2c_pid}"
c2c_status=$?
set -e

if [[ -s "${output}/c2c/perf.data" ]]; then
  "${perf_bin}" c2c report -i "${output}/c2c/perf.data" --stdio --stats \
    > "${output}/c2c/c2c-report-stats.txt" 2> "${output}/c2c/c2c-report-stats.err" || true
  "${perf_bin}" c2c report -i "${output}/c2c/perf.data" --stdio \
    > "${output}/c2c/c2c-report.txt" 2> "${output}/c2c/c2c-report.err" || true
  "${perf_bin}" script -i "${output}/c2c/perf.data" \
    -F comm,tid,time,cpu,event,ip,sym,dso,addr,data_src,weight,phys_addr --ns \
    > "${output}/c2c/perf-script-data-src.txt" 2> "${output}/c2c/perf-script-data-src.err" || true
fi

{
  echo "java_status=${java_status}"
  echo "c2c_status=${c2c_status}"
  echo "java_pid=${java_pid}"
  echo "snapshot_after=${snapshot_after}"
} > "${output}/run/status.txt"

trap - EXIT INT TERM
exit "${java_status}"
