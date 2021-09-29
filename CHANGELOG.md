## [11-2.2] - 2021-09-29
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

## [11-2.1] - 2021-07-06
### Updated
* Switch to a different BIP39 library due to licensing

### Notes
* Not tagged as a stable release

## [11-2.0] - 2021-07-05
### Added
* Storage backup!

### Notes
* Not tagged as a stable release

## [11-1.2] - 2021-07-05
### Fixed
* Fix local contacts backup on LineageOS.
* Minor string fixes.
* Make recovery code fit on smaller screens / larger densities
* Sync app colors with system Settings theme for consistency

### Updated
* Translations update, both existing languages and new.
* Switch all text references to github.com/seedvault-app

## [11-1.1] - 2021-04-16
### Fixed
* Don't crash when storage app gets uninstalled

### Added
* Allow verifying and re-generating the 12 word recovery code
* Prepare for storage backup
* gradle: Use AOSP platform key for signing

## [11-1.0] - 2021-04-16
### Notes
* Change versioning scheme to include Android version
* 11-1.0 is the first release for Android 11
* Incomplete changelog entry, lots of changes done

## [1.0.0] - 2020-03-07

### Added
- APK backup and restore support with the option to toggle them off.
- Note to auto-restore setting in case removable storage is used.
- UX for showing which packages were restored and which failed.
- Show heads-up notification when auto-restore fails due to removed storage.
- Show list of apps and their backup status.
- Support for excluding apps from backups.

### Fixed
- Device initialization and generation of new backup tokens.

## [1.0.0-alpha1] - 2019-12-14
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

## [0.3.0] - 2019-03-14
### Fixed
- Transport encryption. Some of the application data was not included during encryption.

### Notes
- This contains breaking changes, any backups made prior to this release can no longer be restored.

## [0.2.0] - 2019-03-01
### Added
- Support for encrypted backups with a 256-bit AES key generated from a password using PBKDF2.

## [0.1.2] - 2019-02-11
### Fixed
- Downgrade SDK target version to 26 due to [#15](https://github.com/seedvault-app/seedvault/issues/15).

## [0.1.1] - 2019-02-11
### Added
- Action bar options for selecting all packages during backup / restore.
- Upgrade compile SDK version to 28.
- Upgrade target SDK version to 28.

### Fixed
- Ignore `com.android.providers.downloads.ui` to resolve [#14](https://github.com/seedvault-app/seedvault/issues/14).
