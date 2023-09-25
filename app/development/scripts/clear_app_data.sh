#!/usr/bin/env bash

# assert ANDROID_HOME is set
if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME is not set"
  exit 1
fi

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
DEVELOPMENT_DIR=$SCRIPT_DIR/..
ROOT_PROJECT_DIR=$SCRIPT_DIR/../../..

EMULATOR_DEVICE_NAME=$($ANDROID_HOME/platform-tools/adb devices | grep emulator | cut -f1)

if [ -z "$EMULATOR_DEVICE_NAME" ]; then
  echo "Emulator device name not found"
  exit 1
fi

ADB="$ANDROID_HOME/platform-tools/adb -s $EMULATOR_DEVICE_NAME"

$ADB shell pm clear com.stevesoltys.seedvault
