// SPDX-FileCopyrightText: 2020, Torsten Grote <t@grobox.de>
// SPDX-License-Identifier: Apache-2.0


# Local Contacts Backup

A backup application that backs up local on-device contacts via the system's backup API.
This explicitly excludes contacts that are synced via sync accounts
such as [DAVx⁵](https://www.davx5.com/).

## Permissions

* `android.permission.READ_CONTACTS` to back up local contacts.
* `android.permission.WRITE_CONTACTS` to restore local contacts to the device.
