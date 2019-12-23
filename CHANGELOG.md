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
- Application can no longer be built in the Android source tree. It must be built using Gradle and binaries can now be found here: https://github.com/stevesoltys/seedvault-prebuilt

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
- Downgrade SDK target version to 26 due to [#15](https://github.com/stevesoltys/seedvault/issues/15).

## [0.1.1] - 2019-02-11
### Added
- Action bar options for selecting all packages during backup / restore.
- Upgrade compile SDK version to 28.
- Upgrade target SDK version to 28.

### Fixed
- Ignore `com.android.providers.downloads.ui` to resolve [#14](https://github.com/stevesoltys/seedvault/issues/14).
