#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2021 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

adb shell setprop log.tag.JobScheduler DEBUG
adb shell setprop log.tag.JobScheduler.ContentObserver DEBUG
