# Backup
[![Build Status](https://travis-ci.com/stevesoltys/backup.svg?branch=master)](https://travis-ci.com/stevesoltys/backup)

A backup application for the [Android Open Source Project](https://source.android.com/).

## Features
- Backup application data to a zip file.
- Restore application data from a zip file.
- Password-based encryption.

## Getting Started
- Check out [the wiki](https://github.com/stevesoltys/backup/wiki) for information on building the application with 
AOSP.

## What makes this different?
This application is compiled with the operating system and does not require a rooted device for use. It uses the same 
internal APIs as `adb backup` and only requires the permission `android.permission.BACKUP` for this.

## Contributing
Bug reports and pull requests are welcome on GitHub at https://github.com/stevesoltys/backup. 

## Permissions

* `android.permission.BACKUP` to be allowed to back up apps
* `android.permission.RECEIVE_BOOT_COMPLETED` to schedule automatic backups after boot

## License
This application is available as open source under the terms of the [MIT License](http://opensource.org/licenses/MIT).
