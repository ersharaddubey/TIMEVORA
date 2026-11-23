package com.timevora.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.concurrent.ConcurrentHashMap

/** Smart app categories. Apps are grouped automatically; the user can change any app by hand. */
enum class AppCategory(val label: String, val emoji: String, val color: Long) {
    SOCIAL("Social", "📸", 0xFFFF3F6C),
    CHAT("Chat", "💬", 0xFF26A69A),
    GAMES("Games", "🎮", 0xFF7E57C2),
    VIDEO("Video", "🎬", 0xFFEF5350),
    MUSIC("Music", "🎵", 0xFF29B6F6),
    STUDY("Study", "📚", 0xFF66BB6A),
    WORK("Work", "💼", 0xFF3F51B5),
    SHOPPING("Shopping", "🛒", 0xFFFFA726),
    OTHER("Other", "📦", 0xFF9E9E9E);

    companion object {
        fun fromName(name: String?): AppCategory? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Decides which category an app belongs to, in this order:
 * 1. a category the user picked by hand,
 * 2. a built-in list of popular apps,
 * 3. well-known publisher prefixes (game studios),
 * 4. the category the app itself declares to Android,
 * 5. keywords in the package name or app name,
 * 6. otherwise "Other".
 * Everything runs on the device; no app names are sent anywhere.
 */
object AppCategorizer {
    @Volatile private var overrides: Map<String, AppCategory> = emptyMap()
    @Volatile private var loaded = false
    private val auto = ConcurrentHashMap<String, AppCategory>()

    /** Reads the user's manual choices from the database. Call again after changing them. */
    suspend fun reload(c: Context) {
        val rows = timevoraDatabase.get(c).usageDao().appCategoryOverrides()
        overrides = rows.mapNotNull { r -> AppCategory.fromName(r.category)?.let { r.packageName to it } }.toMap()
        loaded = true
    }

    suspend fun ensureLoaded(c: Context) {
        if (!loaded) reload(c)
    }

    fun isCustom(pkg: String): Boolean = overrides.containsKey(pkg)

    fun of(c: Context, pkg: String, label: String): AppCategory =
        overrides[pkg] ?: auto.getOrPut(pkg) { detect(c, pkg, label) }

    /** The automatic guess only, ignoring manual choices (used to show what "reset" would give). */
    fun autoOf(c: Context, pkg: String, label: String): AppCategory = auto.getOrPut(pkg) { detect(c, pkg, label) }

    private fun group(cat: AppCategory, vararg pkgs: String): List<Pair<String, AppCategory>> = pkgs.map { it to cat }

    private val exact: Map<String, AppCategory> = (
        group(AppCategory.SOCIAL,
            "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.lite", "com.twitter.android",
            "com.snapchat.android", "com.reddit.frontpage", "com.pinterest", "com.linkedin.android", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "in.mohalla.sharechat", "in.mohalla.video", "com.tumblr", "com.bumble.app", "com.tinder",
            "com.quora.android", "com.koo.app", "com.lemon8", "com.vkontakte.android", "com.ss.android.ugc.aweme") +
        group(AppCategory.CHAT,
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram", "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.discord", "com.tencent.mm", "jp.naver.line.android", "com.viber.voip", "com.skype.raider",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.truecaller", "com.hike.chat.stickers",
            "com.snapchat.android.chat") +
        group(AppCategory.VIDEO,
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.netflix.mediaclient", "in.startv.hotstar",
            "com.hotstar.android", "com.jio.media.ondemand", "com.sonyliv", "com.graymatrix.did", "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro", "org.videolan.vlc", "tv.twitch.android.app", "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus", "com.zee5.android", "com.google.android.videos", "com.bsbportal.music.video", "com.mxtakatak.android",
            "com.google.android.apps.photos.video") +
        group(AppCategory.MUSIC,
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.gaana", "com.jio.media.jiobeats", "com.bsbportal.music",
            "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android", "com.audiomack", "in.krosbits.musicolet",
            "com.shazam.android") +
        group(AppCategory.STUDY,
            "com.duolingo", "org.khanacademy.android", "com.byjus.thelearningapp", "com.unacademyapp", "com.unacademy.consumption",
            "com.physicswallah.app", "xyz.penpencil.physicswala", "com.vedantu.app", "org.coursera.android", "com.udemy.android",
            "com.quizlet.quizletandroid", "com.microsoft.mobile.photomath", "com.google.android.apps.classroom", "co.brainly",
            "com.doubtnutapp", "com.ichi2.anki", "org.edx.mobile", "com.toppr.app", "com.testbook.tbapp", "com.adda247.app",
            "com.google.android.apps.dictionary", "com.google.android.apps.translate", "com.wolfram.android.alpha", "com.socratic.app",
            "org.moodle.moodlemobile", "com.byjus.tutorplus", "com.eduardogo.pw") +
        group(AppCategory.WORK,
            "com.google.android.gm", "com.google.android.calendar", "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides", "com.google.android.apps.meetings",
            "com.google.android.apps.tasks", "com.google.android.keep", "com.microsoft.office.outlook", "com.microsoft.teams",
            "com.microsoft.office.word", "com.microsoft.office.excel", "com.microsoft.office.powerpoint", "com.microsoft.office.officehubrow",
            "com.microsoft.skydrive", "com.Slack", "us.zoom.videomeetings", "com.notion.id", "com.trello", "com.asana.app",
            "com.atlassian.android.jira.core", "com.github.android", "com.adobe.reader", "com.dropbox.android", "com.evernote",
            "com.todoist", "com.linkedin.android.jobs", "com.microsoft.office.onenote", "com.google.android.apps.docs.editors.keep",
            "com.cisco.webex.meetings", "com.zoho.mail", "com.zoho.projects") +
        group(AppCategory.SHOPPING,
            "com.amazon.mShop.android.shopping", "in.amazon.mShop.android.shopping", "com.flipkart.android", "com.myntra.android",
            "com.meesho.supply", "com.ril.ajio", "com.fsn.nykaa", "in.swiggy.android", "com.application.zomato", "com.zepto.app",
            "com.grofers.customerapp", "com.dunzo.user", "com.bigbasket.mobileapp", "com.olx.southasia", "com.snapdeal.main",
            "com.jio.jiomart", "com.ebay.mobile", "com.shopify.mobile", "com.etsy.android", "com.tatadigital.tcp", "com.lenskart.app",
            "com.ubercab.eats", "com.zomato.hyperpure", "club.cred.android") +
        group(AppCategory.GAMES,
            "com.tencent.ig", "com.pubg.imobile", "com.pubg.krmobile", "com.dts.freefireth", "com.dts.freefiremax", "com.ludo.king",
            "com.mojang.minecraftpe", "com.roblox.client", "com.activision.callofduty.shooter", "com.kiloo.subwaysurf",
            "com.innersloth.spacemafia", "com.mobile.legends", "com.konami.pesam", "com.ea.gp.fifamobile", "com.imangi.templerun2",
            "com.chess", "com.playrix.homescapes", "com.playrix.gardenscapes", "com.moonactive.coinmaster", "com.fungames.sniper3d",
            "com.mobilelegends.mi", "com.garena.game.codm", "com.nianticlabs.pokemongo", "com.epicgames.fortnite", "com.google.android.play.games",
            "com.gameloft.android.ANMP.GloftA9HM", "com.dream11.fantasy.cricket", "com.mpl.android", "com.games24x7.rummycircle")
    ).toMap() + mapOf("com.timevora.app" to AppCategory.OTHER)

    private val gamePrefixes = listOf(
        "com.supercell.", "com.king.", "com.mojang.", "com.mihoyo.", "com.hoyoverse.", "com.garena.", "com.activision.", "com.gameloft.",
        "com.rovio.", "com.zynga.", "com.ea.game", "com.playrix.", "com.miniclip.", "com.netmarble.", "com.nexon.", "com.tencent.tmgp.",
        "com.ubisoft.", "com.square_enix.", "com.bandainamcoent.", "com.sega.", "com.outfit7.", "com.halfbrick.", "com.nianticlabs.",
        "com.epicgames.", "com.proximabeta.", "com.lilithgame.", "com.camelgames.", "io.supercent."
    )

    private val gameWords = listOf(".game", "games", "ludo", "pubg", "bgmi", "freefire", "minecraft", "roblox", "chess", "carrom", "puzzle", "solitaire", "poker", "rummy", "arcade", "racing", "legends", "clash", "candy", "survival")
    private val studyWords = listOf("byju", "unacademy", "vedantu", "duolingo", "khanacademy", "coursera", "udemy", "quizlet", "photomath", "classroom", "doubt", "toppr", "edtech", ".edu", "learn", "study", "exams", "tutor", "school", "academy", "ncert", "upsc", "ebook", "kindle")
    private val workWords = listOf("office", "docs", "spreadsheet", "presentation", "mail", "calendar", "slack", "jira", "trello", "notion", "acrobat", "workspace", "zoom", "webex", "teams", "crm", "invoice")
    private val videoWords = listOf("video", "netflix", "hotstar", "primevideo", "youtube", "movie", "cinema", "film", "twitch", "vlc", "hulu", "disney")
    private val musicWords = listOf("music", "spotify", "gaana", "saavn", "wynk", "podcast", "radio")
    private val shoppingWords = listOf("shopping", "amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "swiggy", "zomato", "blinkit", "zepto", "bigbasket", "olx", "snapdeal", "jiomart", "dmart", "grocer")
    private val socialWords = listOf("social", "instagram", "facebook", "snapchat", "twitter", "reddit", "pinterest", "tiktok", "sharechat", "threads", "linkedin", "tumblr", "dating")
    private val chatWords = listOf("whatsapp", "telegram", "messenger", "discord", "wechat", "messaging", "messages", "chat")

    private fun anyWord(text: String, words: List<String>) = words.any { text.contains(it) }

    private fun detect(c: Context, pkg: String, label: String): AppCategory {
        exact[pkg]?.let { return it }
        val p = pkg.lowercase()
        if (gamePrefixes.any { p.startsWith(it) }) return AppCategory.GAMES
        fromAndroid(c, pkg)?.let { return it }
        val text = p + " " + label.lowercase()
        return when {
            anyWord(text, gameWords) -> AppCategory.GAMES
            anyWord(text, studyWords) -> AppCategory.STUDY
            anyWord(text, workWords) -> AppCategory.WORK
            anyWord(text, videoWords) -> AppCategory.VIDEO
            anyWord(text, musicWords) -> AppCategory.MUSIC
            anyWord(text, shoppingWords) -> AppCategory.SHOPPING
            anyWord(text, socialWords) -> AppCategory.SOCIAL
            anyWord(text, chatWords) -> AppCategory.CHAT
            else -> AppCategory.OTHER
        }
    }

    /** The category the app declares itself (Android 8+). Many apps leave it empty, so this is only one signal. */
    @Suppress("DEPRECATION")
    private fun fromAndroid(c: Context, pkg: String): AppCategory? {
        return try {
            val info = c.packageManager.getApplicationInfo(pkg, 0)
            when {
                info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0 -> AppCategory.GAMES
                info.category == ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
                info.category == ApplicationInfo.CATEGORY_AUDIO -> AppCategory.MUSIC
                info.category == ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
                info.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.WORK
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Sums per-app usage into one total per category. */
    fun totals(c: Context, rows: List<AppUsageTotal>): Map<AppCategory, Long> {
        val out = HashMap<AppCategory, Long>()
        rows.forEach { r ->
            val cat = of(c, r.packageName, r.appLabel)
            out[cat] = (out[cat] ?: 0L) + r.durationMillis
        }
        return out
    }
}
