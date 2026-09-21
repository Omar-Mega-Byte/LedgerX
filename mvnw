#!/bin/sh
set -eu

base_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
wrapper_properties="$base_dir/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$wrapper_properties" ]; then
  echo "Maven Wrapper properties not found: $wrapper_properties" >&2
  exit 1
fi

distribution_url=$(sed -n 's/^distributionUrl=//p' "$wrapper_properties")
distribution_sha512=$(sed -n 's/^distributionSha512Sum=//p' "$wrapper_properties")
maven_user_home=${MAVEN_USER_HOME:-"$HOME/.m2"}
maven_home="$maven_user_home/wrapper/dists/apache-maven-3.9.16"
maven_command="$maven_home/bin/mvn"
archive="$maven_user_home/wrapper/dists/apache-maven-3.9.16-bin.zip"

if [ ! -x "$maven_command" ]; then
  mkdir -p "$(dirname "$archive")"
  echo "Downloading Maven distribution..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$distribution_url" -o "$archive"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$distribution_url" -O "$archive"
  else
    echo "Maven Wrapper requires curl or wget to download Maven." >&2
    exit 1
  fi

  if command -v sha512sum >/dev/null 2>&1; then
    actual_sha512=$(sha512sum "$archive" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual_sha512=$(shasum -a 512 "$archive" | awk '{print $1}')
  else
    echo "Maven Wrapper requires sha512sum or shasum to verify Maven." >&2
    exit 1
  fi

  if [ "$actual_sha512" != "$distribution_sha512" ]; then
    rm -f "$archive"
    echo "Maven distribution checksum verification failed." >&2
    exit 1
  fi

  unzip -q "$archive" -d "$(dirname "$archive")"
fi

exec "$maven_command" "$@"
