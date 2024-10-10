#
# SPDX-FileCopyrightText: 2023 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

echo "Disable auto-restore"
adb shell bmgr autorestore false

echo "Installing Seedvault app..."
./gradlew --stacktrace :app:installDebugAndroidTest
sleep 60

large_test_exit_code=0
./gradlew --stacktrace -Pinstrumented_test_size=large :app:connectedAndroidTest || large_test_exit_code=$?

adb pull /sdcard/seedvault_test_results

if [ "$large_test_exit_code" -ne 0 ]; then
    echo 'Large tests failed.'
    exit 1
fi

medium_test_exit_code=0
./gradlew --stacktrace -Pinstrumented_test_size=medium :app:connectedAndroidTest || medium_test_exit_code=$?

if [ "$medium_test_exit_code" -ne 0 ]; then
    echo 'Medium tests failed.'
    exit 1
fi

exit 0
