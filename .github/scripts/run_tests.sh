echo "Settings transport to Seedvault..."
index=0


D2D_BACKUP_TEST=$1

large_test_exit_code=0
./gradlew --stacktrace -Pinstrumented_test_size=large -Pd2d_backup_test="$D2D_BACKUP_TEST" :app:connectedAndroidTest || large_test_exit_code=$?

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
