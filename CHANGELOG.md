## 15-5.8 - 2026-01-13
* Fix issue with free space reporting causing insufficient backup space errors

## 15-5.7 - 2025-08-12
* Use more reliable detection of flash drive presence
* Don't request backup more than once when notified about available USB storage
* Check if one backup is running before requesting a new one
* Import translations from Weblate

## 15-5.6 - 2025-05-20
* Reduce storage backup retention policy
* 7 daily, 4 weekly, 3 monthly, and 2 yearly storage backups are retained now
* Remove file backup snapshots we can't decrypt
* Catch more exceptions to handle errors better

## 15-5.5 - 2025-04-04
* Unify scheduling of app and file backup, now they always run one after the other
* Add progress bar to app backup pruning notification
* Fix bug where we didn't find any backups, if user had only file backups
* Allow user to restart restore process during setup wizard (e.g. when wrong code was entered)
* Launch foreground service when finalizing backup to prevent system from freezing us
* Auto-retry more WebDAV connection errors
 
## 15-5.4 - 2025-03-13
* Added support and updated dependencies for 15 QPR2

## 15-5.3 - 2025-02-14
* Added support for user CA certificates
* Fixed issue where many Go server implementations of WebDAV did not work with the WebDAV client in Seedvault
* Bumped the app data quota from 1GB to 3GB
* Nextcloud app is no longer allowed for backup (Use built-in WebDAV Cloud support!)
* Improved handling of metered networks, if disallowed, the backup process will be aborted
* Fixed backup errors with USB when file and app backup are both on
* Fixed overdue backups not automatically starting when USB drives are plugged in
* The size of each app backup is now shown on the restore screen
* Fixed a common error (StaleDataException) causing backups to fail
* Fixed error message when no backups are available to restore
* Implemented a wrapper for the backend with a retrying mechanism, giving us less common errors
* Updated dependencies for 15 QPR1

## 15-5.2 - 2024-12-24
* It is now possible to verify the integrity of file backups as well, partially or fully
* Improve files backup snapshot UI
* Allow changing backup location when USB drive isn't plugged in
* Fix work profile USB backup

## 15-5.1 - 2024-11-20
* It is now possible to verify the integrity of app backups, partially or fully
* The entire WebDAV URL is now shown when in settings
* A launch button is now shown for apps that are force-stopped so that they can be backed up

## 15-5.0 - 2024-10-15
* First Android 15 release
* New backup format using compression and deduplication
* Can still restore old backups, but old Seedvault can't restore backups from this version
* Faster and more reliable backups making snapshots that can individually be restored
* Auto-cleaning of old backups
* All backups now mimic device-to-device (allowing backup for all apps)
* All backups now use a high per-app app quota
* App backup (for APKs) moved to expert settings
* Show more information for backups available to restore
* Fix "Waiting to back up..." showing for apps

## 14-4.1 - 2024-08-23
* It is now possible to restore after setting up a profile
* It is now possible to select what to restore (e.g. apps, files...)
* Automatic backup scheduling can now be modified by the user
* Native support for WebDAV
* Support for RoundSync (if enabled by the OS)
* Now in Material 3
* Name of profile is now shown when selecting a backup to restore
* Already installed apps are not reinstalled anymore
* Already stored files do not create duplicates anymore
* Respect policy when the installation of apps is disallowed
* Storage backup is now beta instead of experimental
* D2D is now alpha instead of experimental
* Various corrections to the UI

## 14-4.0 - 2024-01-24
* Add experimental support for forcing "D2D" transfer backups
* Pretend to be a device-to-device transfer to allow backing up many apps which prevent backup
* Stop backing up excluded app APKs
* Show size of app backups in Backup Status screen
* Slight improvements to color scheme
* Development: Improve CI testing setup

## 14-3.3 - 2023-10-06
* Android 14

## 13-3.3 - 2023-01-11
* Mark Nextcloud as "Not recommended"
* Warn before turning off backups
* Avoid corrupting old backups when turning off backups
* Pre-grant `ACCESS_MEDIA_LOCATION` permission for Storage backups

## 13-3.2 - 2022-12-29
* Add expert option to save logs
* Add more details about branching to README
* Improvements for debug builds
* Documentation improvements
* Better error handling in some cases
* Some Android 13 upgrades

## 13-3.1 - 2022-09-01
* Initial release for Android 13
* Don't attempt to restore app that is used as a backup location (e.g. Nextcloud),
  because can cause restore to abort early 
* Upgrade several libraries

## 12-3.0 - 2021-10-13
* Initial release for Android 12
* Use the same (faster and more secure) crypto that storage backups use,
  for app backup.
* Avoid leaking installed app list through filenames by using salted names
* Old backups can still be restored, but new backups will be made with this format
* If you generated the recovery code / setup Seedvault before 11-1.2, you will be prompted
  to generate a new code.
* Improve backup behavior in general

## 11-2.3 - 2021-10-02
### Fixed
* Fix translations for the new BIP39 library
* Switch all translations references to github.com/seedvault-app

## 11-2.2 - 2021-09-29
### User-facing changes
* Don't backup on metered networks
* Disable spell-checker on recovery code input
* Disable Nextcloud restore when not installed and no store available
* Ask for system authentication before storing a new recovery code
* Prevent screenshots of recovery code
* Add expert settings with an option for unlimited quota
* Allow launching restore through a dialer code
* Restrict exported components

### Others
* Improve .editorconfig setup
* Move LocalContactsBackup to product partition
* Link FAQ in Readme to make it more discoverable
* Compares kotlin-bip39 library with bitcoinj library
* Provide an overview over key derivations
* document potential information leakage through the long-lived SQL caches
* Add warning for third-party tools to README

## 11-2.1 - 2021-07-06
### Updated
* Switch to a different BIP39 library due to licensing

### Notes
* Not tagged as a stable release

## 11-2.0 - 2021-07-05
### Added
* Storage backup!

### Notes
* Not tagged as a stable release

## 11-1.2 - 2021-07-05
### Fixed
* Fix local contacts backup on LineageOS.
* Minor string fixes.
* Make recovery code fit on smaller screens / larger densities
* Sync app colors with system Settings theme for consistency

### Updated
* Translations update, both existing languages and new.
* Switch all text references to github.com/seedvault-app

## 11-1.1 - 2021-04-16
### Fixed
* Don't crash when storage app gets uninstalled

### Added
* Allow verifying and re-generating the 12 word recovery code
* Prepare for storage backup
* gradle: Use AOSP platform key for signing

## 11-1.0 - 2021-04-16
### Notes
* Change versioning scheme to include Android version
* 11-1.0 is the first release for Android 11
* Incomplete changelog entry, lots of changes done

## 1.0.0 - 2020-03-07

### Added
- APK backup and restore support with the option to toggle them off.
- Note to auto-restore setting in case removable storage is used.
- UX for showing which packages were restored and which failed.
- Show heads-up notification when auto-restore fails due to removed storage.
- Show list of apps and their backup status.
- Support for excluding apps from backups.

### Fixed
- Device initialization and generation of new backup tokens.

## 1.0.0-alpha1 - 2019-12-14
### Added
- Automatic daily backups that run in the background.
- User friendly UI for creating and restoring backups.
- Support to backing up to and restoring from removable storage.

### Updated
- Application can now be configured in the settings app.
- BIP39 is now used for key generation.

### Notes
- This contains breaking changes, any backups made prior to this release can no longer be restored.
- Application can no longer be built in the Android source tree. It must be built using Gradle and binaries can now be found here: https://github.com/seedvault-app/seedvault-prebuilt

## 0.3.0 - 2019-03-14
### Fixed
- Transport encryption. Some of the application data was not included during encryption.

### Notes
- This contains breaking changes, any backups made prior to this release can no longer be restored.

## 0.2.0 - 2019-03-01
### Added
- Support for encrypted backups with a 256-bit AES key generated from a password using PBKDF2.

## 0.1.2 - 2019-02-11
### Fixed
- Downgrade SDK target version to 26 due to [#15](https://github.com/seedvault-app/seedvault/issues/15).

## 0.1.1 - 2019-02-11
### Added
- Action bar options for selecting all packages during backup / restore.
- Upgrade compile SDK version to 28.
- Upgrade target SDK version to 28.

### Fixed
- Ignore `com.android.providers.downloads.ui` to resolve [#14](https://github.com/seedvault-app/seedvault/issues/14).
