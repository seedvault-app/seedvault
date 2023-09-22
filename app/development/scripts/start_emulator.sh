#!/usr/bin/env bash

# assert ANDROID_HOME is set
if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME is not set"
  exit 1
fi

# assert 1 parameter is provided
if [ $# -ne 1 ]; then
  echo "Usage: $0 <emulator_name>"
  exit 1
fi

EMULATOR_NAME=$1

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
DEVELOPMENT_DIR=$SCRIPT_DIR/..
ROOT_PROJECT_DIR=$SCRIPT_DIR/../../..

echo "Starting emulator..."
nohup $ANDROID_HOME/emulator/emulator -avd "$EMULATOR_NAME" -gpu swiftshader_indirect -writable-system -no-snapshot-load >/dev/null 2>&1 &
