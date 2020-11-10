# Seedvault
[![Build](https://github.com/seedvault-app/seedvault/workflows/Build/badge.svg?branch=master)](https://github.com/seedvault-app/seedvault/actions?query=branch%3Amaster+workflow%3ABuild)

A backup application for the [Android Open Source Project](https://source.android.com/).

## Features
- Backup application data to a flash drive.
- Restore application data from a flash drive.
- User-friendly encryption using a mnemonic phrase (BIP39).
- Automatic daily backups that run in the background.

## Requirements

- Android 11

For older versions of Android, check out [the branches](https://github.com/stevesoltys/seedvault/branches).

## Getting Started
- Check out [the wiki](https://github.com/stevesoltys/seedvault/wiki) for information on building the application with 
AOSP.

## What makes this different?
This application is compiled with the operating system and does not require a rooted device for use.
It uses the same internal APIs as `adb backup` which is deprecated and thus needs a replacement.

## Permissions
* `android.permission.BACKUP` to back up application data.
* `android.permission.ACCESS_NETWORK_STATE` to check if there is internet access when network storage is used.
* `android.permission.MANAGE_USB` to access the serial number of USB mass storage devices.
* `android.permission.WRITE_SECURE_SETTINGS` to change system backup settings and enable call log backup.
* `android.permission.QUERY_ALL_PACKAGES` to get information about all installed apps for backup.
* `android.permission.INSTALL_PACKAGES` to re-install apps when restoring from backup.
* `android.permission.MANAGE_DOCUMENTS` to retrieve the available storage roots (optional) for better UX.

## Contributing
Bug reports and pull requests are welcome on GitHub at https://github.com/stevesoltys/seedvault.

This project aims to adhere to the [official Kotlin coding style](https://developer.android.com/kotlin/style-guide).

## Third-party tools

The [Seedvault backup parser](https://github.com/tlambertz/seedvault_backup_parser)
allows you to decrypt and inspect your backups.
It can also re-encrypt them.

## License
This application is available as open source under the terms of the [Apache-2.0 License](https://opensource.org/licenses/Apache-2.0).
