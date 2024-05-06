/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import org.junit.Assume.assumeFalse
import org.junit.jupiter.api.Assertions.fail

object WebDavTestConfig {

    fun getConfig(): WebDavConfig {
        assumeFalse(System.getenv("NEXTCLOUD_URL").isNullOrEmpty())
        return WebDavConfig(
            url = System.getenv("NEXTCLOUD_URL") ?: fail(),
            username = System.getenv("NEXTCLOUD_USER") ?: fail(),
            password = System.getenv("NEXTCLOUD_PASS") ?: fail(),
        )
    }

}
