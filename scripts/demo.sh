#!/usr/bin/env bash
# Submits every sample, polls them together, retries failing.json, and prints a summary.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"
SAMPLES_DIR="$(cd "$(dirname "$0")/.." && pwd)/samples"
NO_COLOR_OPT=0
QUICK=0

for arg in "$@"; do
  case "$arg" in
    --no-color) NO_COLOR_OPT=1 ;;
    --quick) QUICK=1 ;;
    -h|--help) echo "Usage: $0 [--quick] [--no-color]"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

LIVE=0
if [[ -t 1 ]]; then LIVE=1; fi
USE_COLOR=$LIVE
if [[ -n "${NO_COLOR:-}" || $NO_COLOR_OPT -eq 1 ]]; then USE_COLOR=0; fi

if (( USE_COLOR )); then
  RESET=$'\033[0m'; BOLD=$'\033[1m'; DIM=$'\033[2m'
  GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'
else
  RESET=""; BOLD=""; DIM=""; GREEN=""; RED=""; YELLOW=""; CYAN=""
fi

case "${LC_ALL:-${LANG:-}}" in
  *UTF-8*|*utf8*)
    OK_MARK="✔"; FAIL_MARK="✘"
    SPIN=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏) ;;
  *)
    OK_MARK="+"; FAIL_MARK="x"
    SPIN=('-' '\' '|' '/') ;;
esac

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

require_stack() {
  if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
    echo "Docker is not running. Start Docker Desktop, wait until it is ready, then re-run this script." >&2
    exit 1
  fi
  if ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    echo "The workflow engine is not reachable at $BASE_URL." >&2
    echo "Start the stack with: docker compose up --build -d" >&2
    echo "Wait for the containers to be healthy, then re-run this script." >&2
    exit 1
  fi
}

status_of() {
  curl -sSf "$BASE_URL/api/workflows/$1" | jq -r '.status'
}

retry_workflow() {
  curl -sSf -X POST "$BASE_URL/api/workflows/$1/retry" >/dev/null
}

color_for() {
  case "$1" in
    COMPLETED) printf '%s' "$GREEN" ;;
    FAILED|TIMEOUT) printf '%s' "$RED" ;;
    RETRYING) printf '%s' "$CYAN" ;;
    *) printf '%s' "$YELLOW" ;;
  esac
}

log_change() {
  printf '    %-12s %s\n' "$1" "$2" >&2
}

finish() {
  local i=$1 state=$2
  states[i]=$state
  finished[i]=1
  ends[i]=$(date +%s)
  if (( ! LIVE )); then
    log_change "${names[$i]}" "$state after $(( ends[i] - starts[i] ))s"
  fi
}

drawn=0
draw_table() {
  local frame=$1 i st mark secs now
  now=$(date +%s)
  if (( drawn > 0 )); then printf '\033[%dA' "$drawn" >&2; fi
  printf '\033[2K%s%-12s %-38s %-12s %s%s\n' "$BOLD" SAMPLE WORKFLOW_ID STATE ELAPSED "$RESET" >&2
  for i in "${!names[@]}"; do
    st=${states[$i]}
    if (( finished[i] )); then
      secs=$(( ends[i] - starts[i] ))
      if [[ $st == COMPLETED ]]; then mark=$OK_MARK; else mark=$FAIL_MARK; fi
    else
      secs=$(( now - starts[i] ))
      mark=${SPIN[$(( frame % ${#SPIN[@]} ))]}
    fi
    printf '\033[2K%-12s %-38s %s%s %-10s%s %4ss\n' "${names[$i]}" "${ids[$i]}" "$(color_for "$st")" "$mark" "$st" "$RESET" "$secs" >&2
  done
  drawn=$(( ${#names[@]} + 1 ))
}

poll_all() {
  local i st
  for i in "${!names[@]}"; do
    (( finished[i] )) && continue
    st=$(status_of "${ids[$i]}" 2>/dev/null || echo UNKNOWN)
    case "$st" in
      COMPLETED) finish "$i" COMPLETED ;;
      FAILED)
        if [[ "${names[$i]}" == failing && "${retried[$i]}" -eq 0 ]]; then
          retry_workflow "${ids[$i]}"
          retried[i]=1
          states[i]=RETRYING
          if (( ! LIVE )); then log_change "${names[$i]}" "FAILED as expected; sending manual retry"; fi
        else
          finish "$i" FAILED
        fi ;;
      *)
        if [[ "${states[$i]}" != "$st" ]] && (( ! LIVE )); then log_change "${names[$i]}" "$st"; fi
        states[i]=$st ;;
    esac
  done
}

mark_timeouts() {
  local i
  for i in "${!names[@]}"; do
    if (( ! finished[i] )); then finish "$i" TIMEOUT; fi
  done
}

all_finished() {
  local i
  for i in "${!names[@]}"; do
    if (( ! finished[i] )); then return 1; fi
  done
  return 0
}

run_polling() {
  local deadline tick=0
  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  if (( LIVE )); then printf '\033[?25l' >&2; fi
  while :; do
    if (( tick % 5 == 0 )); then poll_all; fi
    if (( $(date +%s) >= deadline )); then mark_timeouts; fi
    if (( LIVE )); then draw_table "$tick"; fi
    if all_finished; then break; fi
    sleep 0.2
    tick=$((tick + 1))
  done
}

on_interrupt() {
  local i
  echo >&2
  for i in "${!names[@]}"; do
    if (( ! finished[i] )); then echo "still running: ${names[$i]} ${ids[$i]}" >&2; fi
  done
  exit 130
}

START=$(date +%s)
if (( LIVE )); then trap 'printf "\033[?25h" >&2' EXIT; fi
echo "${BOLD}========================================${RESET}"
echo "${BOLD}Workflow engine demo${RESET}"
echo "${BOLD}========================================${RESET}"
echo "${CYAN}==>${RESET} Checking Docker and the workflow engine at $BASE_URL"
require_stack
echo "    ${GREEN}${OK_MARK}${RESET} engine is healthy"

samples=("$SAMPLES_DIR"/*.json)
if (( QUICK )); then
  filtered=()
  for file in "${samples[@]}"; do
    [[ $(basename "$file" .json) == crash ]] || filtered+=("$file")
  done
  samples=("${filtered[@]}")
fi

echo
echo "${CYAN}==>${RESET} Submitting ${#samples[@]} samples from $SAMPLES_DIR"
names=(); ids=(); states=(); starts=(); ends=(); finished=(); retried=()
for file in "${samples[@]}"; do
  name=$(basename "$file" .json)
  id=$(curl -sSf -X POST "$BASE_URL/api/workflows" \
    -H 'Content-Type: application/json' --data-binary "@$file" | jq -r '.id')
  steps=$(jq '.steps | length' "$file")
  names+=("$name"); ids+=("$id"); states+=(PENDING)
  starts+=("$(date +%s)"); ends+=(0); finished+=(0); retried+=(0)
  printf '    submitted %-11s (%s steps) -> %s\n' "$name" "$steps" "$id"
done

echo
echo "${CYAN}==>${RESET} Polling every second (timeout ${TIMEOUT_SECONDS}s)"
trap on_interrupt INT TERM
run_polling

failures=0
echo
echo "${CYAN}==>${RESET} Summary (total $(( $(date +%s) - START ))s)"
printf '\n%s%-12s %-38s %-10s %-10s%s\n' "$BOLD" SAMPLE WORKFLOW_ID EXPECTED ACTUAL "$RESET"
for i in "${!names[@]}"; do
  expected=COMPLETED
  actual=${states[$i]}
  if [[ "$actual" != "$expected" ]]; then
    failures=$((failures + 1))
  fi
  printf '%-12s %-38s %-10s %s%-10s%s\n' "${names[$i]}" "${ids[$i]}" "$expected" "$(color_for "$actual")" "$actual" "$RESET"
done

echo
if (( failures > 0 )); then
  echo "${RED}${FAIL_MARK} FAILED: $failures sample(s) did not reach the expected state${RESET}" >&2
  exit 1
fi
echo "${GREEN}${OK_MARK} PASSED: all samples reached the expected state${RESET}"
