# Seedvault
[![Build](https://github.com/seedvault-app/seedvault/actions/workflows/build.yml/badge.svg)](https://github.com/seedvault-app/seedvault/actions/workflows/build.yml)

A backup application for the [Android Open Source Project](https://source.android.com/).
Needs to be [integrated](https://github.com/seedvault-app/seedvault/wiki/ROM-Integration)
in your Android ROM and **can not** be installed as a regular app.

If you are having an issue/question,
please look at our [FAQ](https://github.com/seedvault-app/seedvault/wiki/FAQ)
or [ask a new question](https://github.com/seedvault-app/seedvault/discussions).

## Components

* [Local Contacts Backup](contactsbackup) - an app that backs up local on-device contacts
* [File backup library](storage) - a library handling efficient backup of files
  ([documentation](storage/doc/design.md))
* [Seedvault app](app) - the main app where all functionality comes together
  ([documentation](doc/README.md))

## Features
- Backup application data to a flash drive.
- Restore application data from a flash drive.
- User-friendly encryption using a mnemonic phrase (BIP39).
- Automatic daily backups that run in the background.

## Requirements

SeedVault is developed along with AOSP releases.

We update it every time Google releases a new Android version, 
make any changes required for basic functionality,
and any improvements possible through API changes in the OS.

This means that for ROMs using SeedVault it's recommended
to use the same branch as your android version

- This current branch `android15` is meant for usage with Android 15
- This is indicated by the version name starting with `15`,
  and the version code starting with `35` - the Android 15 API version

For older versions of Android,
check out [the branches](https://github.com/seedvault-app/seedvault/branches).

Trying to use an older branch on a newer version may lead to issues
and is not something we can support.

## What makes this different?

This application is compiled with the operating system and does not require a rooted device for use.
It uses the same internal APIs as `adb backup` which is deprecated and thus needs a replacement.

## Permissions
* `android.permission.BACKUP` to back up application data.
* `android.permission.ACCESS_NETWORK_STATE` to check if there is internet access when network storage is used.
* `android.permission.MANAGE_USB` to access the serial number of USB mass storage devices.
* `android.permission.WRITE_SECURE_SETTINGS` to change system backup settings and enable call log backup.
* `android.permission.QUERY_ALL_PACKAGES` to get information about all installed apps for backup.
* `android.permission.QUERY_USERS` to get the name of the user profile that gets backed up.
* `android.permission.INSTALL_PACKAGES` to re-install apps when restoring from backup.
* `android.permission.MANAGE_EXTERNAL_STORAGE` to backup and restore files from device storage.
* `android.permission.ACCESS_MEDIA_LOCATION` to backup original media files e.g. without stripped EXIF metadata.
* `android.permission.FOREGROUND_SERVICE` to do periodic storage backups without interruption.
* `android.permission.FOREGROUND_SERVICE_DATA_SYNC` to do periodic storage backups without interruption.
* `android.permission.MANAGE_DOCUMENTS` to retrieve the available storage roots (optional) for better UX.
* `android.permission.USE_BIOMETRIC` to authenticate saving a new recovery code
* `android.permission.INTERACT_ACROSS_USERS_FULL` to use storage roots in other users (optional).
* `android.permission.POST_NOTIFICATIONS` to inform users about backup status and errors.

## Contributing
Bug reports and pull requests are welcome on GitHub at https://github.com/seedvault-app/seedvault.

See [DEVELOPMENT.md](app/development/DEVELOPMENT.md) for information
on developing Seedvault locally.

This project aims to adhere to the
[official Kotlin coding style](https://developer.android.com/kotlin/style-guide).

## Third-party tools

> **⚠ WARNING**: the Seedvault developers make no guarantees about external software projects.
> Please be aware that disclosing your secret recovery key to other software has security risks.

The [Seedvault backup parser](https://github.com/tlambertz/seedvault_backup_parser)
allows you to decrypt and inspect your backups (version 0 backup).
It can also re-encrypt them.

The [Seedvault extractor](https://github.com/jackwilsdon/seedvault-extractor)
allows you to decrypt and inspect your backups from newer versions of Seedvault (version 1 backup).
It is currently work-in-progress.

## License
This application is available as open source under the terms
of the [Apache-2.0 License](https://opensource.org/licenses/Apache-2.0).

## Funding

### Calyx Institute

This project is primarily developed and maintained by the [Calyx Institute](https://calyxinstitute.org/)
for usage in [CalyxOS](https://calyxos.org/).

### NGI0 PET Fund

This project was funded through the [NGI0 PET Fund](https://nlnet.nl/project/Seedvault/),
a fund established by [NLnet](https://nlnet.nl)
with financial support from the European Commission's Next Generation Internet programme,
under the aegis of DG Communications Networks, Content and Technology
under grant agreement No 825310.

### NGI0 Entrust Fund

This project was funded through the
[NGI0 Entrust Fund](https://nlnet.nl/project/SeedVault-Integrity/),
a fund established by [NLnet](https://nlnet.nl)
with financial support from the European Commission's Next Generation Internet programme,
under the aegis of DG Communications Networks, Content and Technology
under grant agreement No 101069594.
