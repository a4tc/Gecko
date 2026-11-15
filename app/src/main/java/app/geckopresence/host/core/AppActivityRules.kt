package app.geckopresence.host.core

import java.util.Locale

/** Conservative package/label filters. Tracking is allow-list only; these are extra deny rules. */
object AppActivityRules {
    private val sensitiveTokens = listOf(
        "bank", "banking", "bca", "mybca", "bni", "wondr", "bri", "brimo", "mandiri", "livin",
        "seabank", "bankbkem", "bankneo", "jago", "blu", "bsi", "ocbc", "cimb", "danamon",
        "permata", "maybank", "dbs", "uob", "jenius", "btn", "wallet", "finance", "financial", "fintech",
        "payment", "pay", "money", "invest", "broker", "stock", "trading", "crypto", "exchange",
        "dana", "ovo", "gopay", "shopeepay", "linkaja", "flip", "paypal", "payoneer", "wise",
        "kredivo", "akulaku", "spaylater", "paylater", "bibit", "ajaib", "bareksa", "pluang",
        "stockbit", "indodax", "nanovest", "pintu", "binance", "coinbase", "bybit", "kraken",
        "authenticator", "authentication", "2fas", "authy", "aegis", "bitwarden", "lastpass",
        "1password", "protonpass", "otp", "password", "passkey", "health", "medical", "hospital",
        "clinic", "doctor", "bpjs", "jkn", "satusehat", "halodoc", "alodokter", "insurance",
        "government", "identity", "tax", "pajak", "coretax", "npwp", "credential", "vault",
        "loan", "credit", "kredit", "pinjaman", "debt", "dukcapil", "passport", "immigration", "imigrasi",
        "e-ktp", "ktp", "polri", "samsat", "pharmacy", "apotek", "medicine", "medication", "medicalrecords",
        "whatsapp", "telegram", "signal",
        "messenger", "kakaotalk", "wechat", "gmail", "outlook", "webmail", "email", "mail", "inbox", "messages", "message",
        "sms", "slack", "teams", "discord", "contacts", "dialer", "calllog", "phone", "camera",
        "gallery", "photos", "filemanager", "files", "documents", "drive", "scanner", "privatefiles", "calendar", "notes", "diary", "journal",
        "settings", "systemui", "launcher", "packageinstaller", "inputmethod", "keyboard",
    )

    private val browserTokens = listOf(
        "browser", "firefox", "fennec", "tor", "chrome", "chromium", "vivaldi",
        "opera", "brave", "edge", "duckduckgo", "samsunginternet", "kiwi",
    )

    fun isSensitiveApp(packageName: String, label: String): Boolean {
        val haystack = "${packageName.lowercase(Locale.ROOT)} ${label.lowercase(Locale.ROOT)}"
        return sensitiveTokens.any { token ->
            if (token.length <= 3) {
                Regex("(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])").containsMatchIn(haystack)
            } else {
                haystack.contains(token)
            }
        }
    }

    fun isBrowserApp(packageName: String, label: String): Boolean {
        val haystack = "${packageName.lowercase(Locale.ROOT)} ${label.lowercase(Locale.ROOT)}"
        return browserTokens.any { token -> haystack.contains(token) }
    }

    fun isSelectableApp(packageName: String, label: String, hostPackage: String): Boolean {
        if (packageName.isBlank() || packageName == hostPackage) return false
        if (isSensitiveApp(packageName, label)) return false
        if (isBrowserApp(packageName, label)) return false
        return true
    }

    fun isKnownApp(packageName: String, label: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val name = label.lowercase(Locale.ROOT)
        return pkg.contains("musically") || pkg.contains("tiktok") || name.contains("tiktok") ||
            pkg == "com.instagram.android" || name.contains("instagram") ||
            pkg.startsWith("com.google.android.youtube") || pkg.contains("youtube.music") || name == "youtube" || name.contains("youtube music") ||
            name.contains("netflix") || name.contains("prime video") || name.contains("disney+") ||
            name.contains("twitch") || name.contains("crunchyroll") || name == "viu" || name == "vidio" ||
            pkg == "com.spotify.music" || name.contains("spotify") ||
            pkg.contains("chess") || name.contains("chess")
    }

    fun defaultActivityDetails(packageName: String, label: String): String {
        val pkg = packageName.lowercase(Locale.ROOT)
        val name = label.lowercase(Locale.ROOT)
        return when {
            pkg.contains("musically") || pkg.contains("tiktok") || name.contains("tiktok") -> "Watching short videos"
            pkg == "com.instagram.android" || name.contains("instagram") -> "Watching Reels"
            pkg.contains("youtube.music") || name.contains("youtube music") -> "Listening to music"
            pkg.startsWith("com.google.android.youtube") || name == "youtube" -> "Watching videos"
            pkg == "com.spotify.music" || name.contains("spotify") -> "Listening to music"
            name.contains("netflix") || name.contains("prime video") || name.contains("disney+") ||
                name.contains("twitch") || name.contains("crunchyroll") || name == "viu" || name == "vidio" -> "Watching"
            pkg.contains("chess") || name.contains("chess") -> "Playing chess"
            else -> "Using ${label.ifBlank { "app" }}"
        }
    }
}
