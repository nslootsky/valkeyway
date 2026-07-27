#!/usr/bin/env sh
# Maven Wrapper launch script
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$SCRIPT_DIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="$SCRIPT_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Error: maven-wrapper.jar not found" >&2
  exit 1
fi

if [ -f "$WRAPPER_PROPERTIES" ]; then
  DISTRIBUTION_URL=$(grep '^distributionUrl=' "$WRAPPER_PROPERTIES" | cut -d'=' -f2- | tr -d '\r')
  MAVEN_USER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}"
  MAVEN_HOME="${MAVEN_HOME:-$MAVEN_USER_HOME}"
  
  DIST_NAME=$(basename "$DISTRIBUTION_URL" | sed 's/-bin\.zip//')
  MAVEN_BIN_DIR="$MAVEN_HOME/$DIST_NAME/bin"
  
  if [ ! -x "$MAVEN_BIN_DIR/mvn" ]; then
    echo "Downloading Maven from $DISTRIBUTION_URL" >&2
    mkdir -p "$MAVEN_HOME"
    TMP_ZIP=$(mktemp)
    curl -fsSL -o "$TMP_ZIP" "$DISTRIBUTION_URL"
    unzip -q "$TMP_ZIP" -d "$MAVEN_HOME"
    rm -f "$TMP_ZIP"
  fi
  
  exec "$MAVEN_BIN_DIR/mvn" "$@"
else
  exec java -jar "$WRAPPER_JAR" "$@"
fi
