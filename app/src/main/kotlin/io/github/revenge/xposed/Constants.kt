package io.github.revenge.xposed

object RevengeConstants {
    const val TARGET_PACKAGE = "com.discord"
    const val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

    // @TODO: Migration to revenge named dir
    const val FILES_DIR = "files/pyoncord"
    const val CACHE_DIR = "cache/revenge"
    const val MAIN_SCRIPT_FILE = "bundle.js"
    const val PRELOADS_DIR = "preloads"

    const val LOADER_NAME = "EsharqMobile"
    val LOADER_VERSION
        get() = BuildConfig.VERSION_NAME
    val USER_AGENT
        get() = "EsharqMobile/$LOADER_VERSION"

    /**
     * Esharq is served, never shipped.
     *
     * Upstream keeps a copy of the mod inside this APK and runs it whenever the download fails.
     * That is exactly the door this fork exists to close: a bundled copy would run for anyone who
     * installed the app, member or not, and no amount of checking elsewhere would matter. There is
     * deliberately no fallback constant here — see [io.github.revenge.xposed.tweaks.revengeScriptLoader].
     */
    const val BUNDLE_URL = "https://esharq.org/api/mobile/bundle"
    const val GRANT_URL = "https://esharq.org/api/mobile/grant"

    /**
     * Written into the patched APK by the Esharq installer once Discord has confirmed the account
     * is in the Esharq server. Absent when someone side-loads this module on its own, which leaves
     * the loader with nothing to authenticate as and therefore nothing to run.
     */
    const val INSTALL_ASSET = "assets/esharq-install.json"

    /**
     * The per-launch grant lands here. `preloads/` already runs, sorted, before the main bundle,
     * so the leading digits keep it first without inventing a new mechanism.
     */
    const val GRANT_PRELOAD_FILE = "00-esharq-grant.js"

    /** Where the server's renewed receipt is kept, since the one in the APK cannot be rewritten. */
    const val RENEWED_TOKEN_FILE = "esharq-token.txt"
}

/**
 * Hermes bytecode assets shipped inside the Xposed module APK.
 */
val scriptAssets = emptyList<String>()
