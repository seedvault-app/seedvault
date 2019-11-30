# Seedvault
[![Build Status](https://travis-ci.com/stevesoltys/seedvault.svg?branch=master)](https://travis-ci.com/stevesoltys/seedvault)

A backup application for the [Android Open Source Project](https://source.android.com/).

## Features
- Backup application data to a flash drive.
- Restore application data from a flash drive.
- User-friendly encryption using a mnemonic phrase (BIP39).
- Automatic daily backups that run in the background.

## Getting Started
- Check out [the wiki](https://github.com/stevesoltys/seedvault/wiki) for information on building the application with 
AOSP.

## What makes this different?
This application is compiled with the operating system and does not require a rooted device for use. It uses the same 
internal APIs as `adb backup` and requires a minimal number of permissions to achieve this.

## Permissions
* `android.permission.BACKUP` to back up application data.
* `android.permission.MANAGE_DOCUMENTS` to retrieve the available storage roots. 
* `android.permission.MANAGE_USB` to access the serial number of USB mass storage devices.
* `android.permission.WRITE_SECURE_SETTINGS` to change system backup settings.

## Contributing
Bug reports and pull requests are welcome on GitHub at https://github.com/stevesoltys/seedvault. 

## License
This application is available as open source under the terms of the [MIT License](http://opensource.org/licenses/MIT).
