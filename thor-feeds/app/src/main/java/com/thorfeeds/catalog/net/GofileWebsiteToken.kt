package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.Names

/**
 * Gofile's contents API expects a short-lived website token:
 * sha256(userAgent + "::" + language + "::" + accountToken + "::" + unixSeconds/14400 + "::" + salt).
 * The salt rotates when Gofile changes their web app. Callers try the known salts in order.
 */
object GofileWebsiteToken {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val LANGUAGE = "en-US"
    private const val SLOT_SECONDS = 14_400L

    fun compute(
        userAgent: String,
        language: String,
        accountToken: String,
        unixSeconds: Long,
        salt: String,
    ): String {
        val slot = unixSeconds / SLOT_SECONDS
        return Names.sha256("$userAgent::$language::$accountToken::$slot::$salt")
    }
}
