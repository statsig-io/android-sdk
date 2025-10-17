package com.statsig.androidsdk

import java.net.URL
import java.net.URLConnection

/**
 * Layer of indirection for Statsig classes opening HTTP connections, used as a test hook.
 */
interface UrlConnectionProvider {
    fun open(url: URL): URLConnection
}

internal val defaultProvider = object : UrlConnectionProvider {
    override fun open(url: URL): URLConnection = url.openConnection()
}
