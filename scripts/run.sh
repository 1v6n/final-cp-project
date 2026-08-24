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

required_major=21
if (( java_major < required_major )); then
  echo "Error: Java ${required_major}+ is required by pom.xml, but found Java ${raw_version}."
  echo "Set JAVA_HOME to a JDK ${required_major}+ and re-run this script."
  exit 1
fi

# Java 24+ warns about the internal API used by Maven's dependency injector.
# Allowing it here removes that unrelated warning without changing the project.
if (( java_major >= 24 )); then
  if [[ -n "${MAVEN_OPTS:-}" ]]; then
    MAVEN_OPTS+=" "
  fi
  MAVEN_OPTS+="--sun-misc-unsafe-memory-access=allow"
  export MAVEN_OPTS
fi

cd "${REPO_ROOT}"

# El modo quiet de Maven oculta su salida normal, pero conserva la barra de
# progreso, el resumen de la aplicación y cualquier error.
mvn -q -B clean compile
mvn -q -B exec:java -Dexec.mainClass=org.concurrent.project.Main
