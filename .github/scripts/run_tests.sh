#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2023 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

echo "Disable auto-restore"
adb shell bmgr autorestore false

echo "Installing Seedvault app..."
./gradlew --stacktrace :app:installDebugAndroidTest
sleep 60

# The large tests (the app's end-to-end backup/restore test) are run on their
# own via the instrumentation runner's built-in size filtering.
large_test_exit_code=0
./gradlew --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.size=large \
    :app:connectedAndroidTest || large_test_exit_code=$?

adb pull /sdcard/seedvault_test_results

if [ "$large_test_exit_code" -ne 0 ]; then
    echo 'Large tests failed.'
    exit 1
fi

# All remaining (non-large) instrumentation tests across every module.
other_test_exit_code=0
./gradlew --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=androidx.test.filters.LargeTest \
    connectedAndroidTest || other_test_exit_code=$?

if [ "$other_test_exit_code" -ne 0 ]; then
    echo 'Non-large tests failed.'
    exit 1
fi

exit 0
