#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2020 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

set -ex

adb shell setprop log.tag.BackupManagerService VERBOSE
adb shell setprop log.tag.BackupManagerConstants VERBOSE
adb shell setprop log.tag.BackupTransportManager VERBOSE
adb shell setprop log.tag.KeyValueBackupJob VERBOSE
adb shell setprop log.tag.KeyValueBackupTask VERBOSE
adb shell setprop log.tag.TransportClient VERBOSE
adb shell setprop log.tag.PMBA VERBOSE
