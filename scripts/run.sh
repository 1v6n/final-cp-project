#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! command -v mvn >/dev/null 2>&1; then
  echo "Error: Maven (mvn) is not installed or not in PATH."
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Error: Java is not installed or not in PATH."
  exit 1
fi

raw_version="$(java -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/{print $3}')"
if [[ -z "${raw_version}" ]]; then
  echo "Error: Could not determine Java version."
  exit 1
fi

if [[ "${raw_version}" == 1.* ]]; then
  java_major="${raw_version#1.}"
else
  java_major="${raw_version}"
fi

required_major=20
if (( java_major < required_major )); then
  echo "Error: Java ${required_major}+ is required by pom.xml, but found Java ${raw_version}."
  echo "Set JAVA_HOME to a JDK ${required_major}+ and re-run this script."
  exit 1
fi

cd "${REPO_ROOT}"

mvn clean compile

mvn -e exec:java -Dexec.mainClass=org.concurrent.project.Main
