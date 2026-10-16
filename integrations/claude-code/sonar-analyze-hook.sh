#!/usr/bin/env bash
# Claude Code PostToolUse hook: after Claude edits a file, run the official SonarQube CLI's Vortex
# analysis on it (answered by Community Vortex) and hand any findings back to Claude.
#
# Needs: sonar (SonarQube CLI, authenticated with `sonar auth login --server <url>`), jq,
#        SONAR_PROJECT_KEY in the environment (or the project linked with `sonar link`).
# Exit 2 feeds stderr back to Claude; 0 lets it continue.
set -u

file="$(jq -r '.tool_input.file_path // empty')"
[ -n "$file" ] && [ -f "$file" ] || exit 0

project_args=()
[ -n "${SONAR_PROJECT_KEY:-}" ] && project_args=(--project "$SONAR_PROJECT_KEY")

output="$(sonar analyze --file "$file" "${project_args[@]}" 2>&1)"
status=$?
# 0: no issues, 51: issues found (SonarQube CLI convention), anything else: could not analyse
if [ "$status" -eq 51 ]; then
  {
    echo "SonarQube (Community Vortex) found issues in $file. Fix them before continuing:"
    echo "$output"
  } >&2
  exit 2
fi
if [ "$status" -ne 0 ]; then
  echo "SonarQube analysis did not run for $file (exit $status): $output" >&2
fi
exit 0
