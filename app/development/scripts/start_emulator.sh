#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2023 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

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

echo "Starting emulator..."
nohup $ANDROID_HOME/emulator/emulator -avd "$EMULATOR_NAME" -gpu swiftshader_indirect -writable-system >/dev/null 2>&1 &
