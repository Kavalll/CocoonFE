package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.Names

/**
 * Gofile's contents API expects a short-lived website token:
 * sha256(userAgent + "::" + language + "::" + accountToken + "::" + unixSeconds/14400 + "::" + salt).
 * The salt rotates when Gofile changes their web app. Callers try the known salts in order.
 */
object GofileWebsiteToken {
    const val PRIMARY_SALT = "5d4f7g8sd45fsd"
    const val ALTERNATE_SALT = "gf2026x"
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
