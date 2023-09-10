# Development

## Using an emulator

It is possible to install and run Seedvault in an emulator. This is likely the path of least resistance, since you don't need to build AOSP from source to make and test code changes.

It's also helpful for quickly testing Seedvault on newer versions of Android.
Please note that this process has only been tested on Linux.

### Setup

After opening the project in Android Studio, try running the `app:provisionEmulator` Gradle task.

This task runs the script in `scripts/provision_emulator.sh`:

```bash
./app/development/scripts/provision_emulator.sh "seedvault" "system-images;android-33;google_apis;x86_64"
```   

### Starting the emulator

You should use the Gradle task `app:startEmulator` to develop with the emulator. This is to ensure  
the `-writable-system` flag is set when the emulator starts (required to install Seedvault).

This task runs the script in `scripts/start_emulator.sh`:

```bash  
./app/development/scripts/start_emulator.sh "seedvault"
```  

### Testing changes

Once the emulator is provisioned and running, you should be able to use the `app:installEmulatorRelease`  
Gradle task to install updates.

This task depends on `app:assembleRelease` and runs the script in `scripts/install_app.sh`:

```bash
./app/development/scripts/install_app.sh
```

There's also an Andriod Studio [runtime configuration](https://developer.android.com/studio/run/rundebugconfig) `app-emulator` which will build, install, and automatically launch the `com.stevesoltys.seedvault.settings.SettingsActivity` as if you clicked `Backup` in settings.

### Notes

The `MANAGE_DOCUMENTS` permission will not be granted unless you are using a base AOSP    
image. Currently by default we are using the `google-apis` version of the image, which does not provide the    
permission because it is not signed with the test platform key.

The [generic AOSP images](https://developer.android.com/topic/generic-system-image/releases) are signed with the test platform key, but at the time of writing there is no AOSP emulator image for Android 13 in the default SDK manager repositories. 
