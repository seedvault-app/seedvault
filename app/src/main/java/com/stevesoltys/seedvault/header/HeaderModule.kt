/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.header

import org.koin.dsl.module

val headerModule = module {
    single<HeaderReader> { HeaderReaderImpl() }
}
