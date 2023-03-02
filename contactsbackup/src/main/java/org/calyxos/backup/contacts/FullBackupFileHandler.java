/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.contacts;

import android.app.backup.FullBackupDataOutput;

import java.io.File;

interface FullBackupFileHandler {

    void fullBackupFile(File file, FullBackupDataOutput output);

}
