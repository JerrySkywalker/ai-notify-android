package io.github.jerryskywalker.ainotify.mobile

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Language(val tag: String, @get:StringRes val label: Int) {
    SYSTEM("", R.string.system), ENGLISH("en", R.string.english), CHINESE("zh-CN", R.string.chinese),
}

internal enum class ThemeMode(@get:StringRes val label: Int) {
    SYSTEM(R.string.system), LIGHT(R.string.theme_light), GRAPHITE(R.string.theme_graphite), AMOLED(R.string.theme_amoled),
}

internal enum class Accent(@get:StringRes val label: Int) {
    JADE(R.string.accent_jade), BLUE(R.string.accent_blue), VIOLET(R.string.accent_violet),
}

internal enum class SwipeAction(@get:StringRes val label: Int) {
    PIN(R.string.swipe_pin), ARCHIVE(R.string.move_archive), TRASH(R.string.move_trash), NONE(R.string.no_action),
}

internal data class PhonePreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.JADE,
    val left: SwipeAction = SwipeAction.ARCHIVE,
    val right: SwipeAction = SwipeAction.PIN,
    val pushEnabled: Boolean = false,
    val pushToken: String? = null,
    val fcmEnabled: Boolean = false,
    val fcmToken: String? = null,
    val fcmFid: String? = null,
)

internal class Preferences(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        PhonePreferences(
            storage.choice("theme", ThemeMode.SYSTEM),
            storage.choice("accent", Accent.JADE),
            storage.choice("left", SwipeAction.ARCHIVE),
            storage.choice("right", SwipeAction.PIN),
            storage.getBoolean("push_enabled", false),
            storage.getString("push_token", null),
            storage.getBoolean("fcm_enabled", false),
            storage.getString("fcm_token", null),
            storage.getString("fcm_fid", null),
        ),
    )
    val current = state.asStateFlow()

    fun update(change: (PhonePreferences) -> PhonePreferences) {
        val next = change(state.value)
        storage.edit {
            putString("theme", next.theme.name)
            putString("accent", next.accent.name)
            putString("left", next.left.name)
            putString("right", next.right.name)
            putBoolean("push_enabled", next.pushEnabled)
            putString("push_token", next.pushToken)
            putBoolean("fcm_enabled", next.fcmEnabled)
            putString("fcm_token", next.fcmToken)
            putString("fcm_fid", next.fcmFid)
        }
        state.value = next
    }

    fun getOrCreatePushToken(): String {
        val existing = state.value.pushToken
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString()
        update { it.copy(pushToken = created) }
        return created
    }

    companion object {
        private const val FILE = "phone-preferences"

        fun language(context: Context): Language {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.toLanguageTag()
            } else {
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("language", null)
            }
            return Language.entries.firstOrNull { it.tag == tag } ?: Language.SYSTEM
        }

        fun setLanguage(context: Context, choice: Language) {
            if (language(context) == choice) return
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(choice.tag)
            } else {
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("language", choice.tag) }
                (context as MainActivity).recreate()
            }
        }

        fun localized(context: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return context
            val choice = language(context)
            if (choice == Language.SYSTEM) return context
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(choice.tag))
            return context.createConfigurationContext(configuration)
        }
    }
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.choice(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == getString(key, null) } ?: default
