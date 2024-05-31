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

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
ROOT_PROJECT_DIR=$SCRIPT_DIR/../../..

EMULATOR_DEVICE_NAME=$("$ANDROID_HOME"/platform-tools/adb devices | grep emulator | cut -f1)

if [ -z "$EMULATOR_DEVICE_NAME" ]; then
  echo "Emulator device name not found"
  exit 1
fi

ADB="$ANDROID_HOME/platform-tools/adb -s $EMULATOR_DEVICE_NAME"

$ADB root
sleep 3      # wait for adb to restart
$ADB remount # remount /system as writable

echo "Installing Seedvault app..."
$ADB shell mkdir -p /system/priv-app/Seedvault
$ADB push "$ROOT_PROJECT_DIR"/app/build/outputs/apk/release/app-release.apk /system/priv-app/Seedvault/Seedvault.apk

echo "Installing Seedvault permissions..."
$ADB push "$ROOT_PROJECT_DIR"/permissions_com.stevesoltys.seedvault.xml /system/etc/permissions/privapp-permissions-seedvault.xml
$ADB push "$ROOT_PROJECT_DIR"/allowlist_com.stevesoltys.seedvault.xml /system/etc/sysconfig/allowlist-seedvault.xml
$ADB shell am broadcast -a android.intent.action.BOOT_COMPLETED
