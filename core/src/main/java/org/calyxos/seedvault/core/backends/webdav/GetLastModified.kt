/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.property.webdav.NS_WEBDAV
import org.xmlpull.v1.XmlPullParser

/**
 * A fake version of [at.bitfire.dav4jvm.property.webdav.GetLastModified] which we register
 * so we don't need to depend on `org.apache.commons.lang3` which is used for date parsing.
 */
internal class GetLastModified : Property {
    companion object {
        @JvmField
        val NAME = Property.Name(NS_WEBDAV, "getlastmodified")
    }

    object Factory : PropertyFactory {
        override fun getName() = NAME
        override fun create(parser: XmlPullParser): GetLastModified? = null
    }
}
