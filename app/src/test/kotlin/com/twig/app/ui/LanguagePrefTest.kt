package com.twig.app.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LanguagePrefTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun resetLanguage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `selecting a regional language keeps its full tag and selected label`() {
        for ((tag, label) in listOf("pt-BR" to "Português (Brasil) (Brazilian Portuguese)", "zh-Hant" to "繁體中文 (Traditional Chinese)")) {
            LanguagePref.apply(LanguagePref.labels(context).indexOf(label))
            assertEquals(tag, AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals(label, LanguagePref.labels(context)[LanguagePref.current()])
        }
    }

    @Test
    fun `regional locales select the matching language and Chinese script`() {
        for ((tag, label) in listOf(
            "en-US" to "English", "de-AT" to "Deutsch (German)", "es-MX" to "Español (Spanish)",
            "zh-CN" to "简体中文 (Simplified Chinese)", "zh-SG" to "简体中文 (Simplified Chinese)", "zh-TW" to "繁體中文 (Traditional Chinese)",
            "zh-HK" to "繁體中文 (Traditional Chinese)", "zh-Hant-HK" to "繁體中文 (Traditional Chinese)",
            "zh-Hans-TW" to "简体中文 (Simplified Chinese)", "id-ID" to "Bahasa Indonesia (Indonesian)",
            "hi-IN" to "हिन्दी (Hindi)", "ar-EG" to "العربية (Arabic)", "ar-SA" to "العربية (Arabic)",
            "tr-TR" to "Türkçe (Turkish)", "vi-VN" to "Tiếng Việt (Vietnamese)",
        )) {
            assertEquals(tag, label, LanguagePref.labels(context)[LanguagePref.indexOf(Locale.forLanguageTag(tag))])
        }
        assertEquals(0, LanguagePref.indexOf(null))
        assertEquals(0, LanguagePref.indexOf(Locale.forLanguageTag("zz")))
    }

    @Test
    fun `new languages resolve translated resources and keep bilingual picker names`() {
        val names = LanguagePref.labels(context).drop(1)
        for ((tag, delete) in listOf(
            "de" to "Löschen", "es" to "Eliminar", "fr" to "Supprimer", "it" to "Elimina",
            "pt-BR" to "Excluir", "id" to "Hapus", "ja" to "削除", "ko" to "삭제",
            "zh-Hant" to "刪除",
            "hi" to "हटाएँ", "ar" to "حذف", "tr" to "Sil", "vi" to "Xóa",
        )) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val localized = context.createConfigurationContext(config)
            assertEquals(tag, delete, localized.getString(R.string.action_delete))
            assertEquals(tag, names, LanguagePref.labels(localized).drop(1))
            val formatted = localized.getString(R.string.backup_imported, 11, 22, 33, 44)
            for (number in listOf(11, 22, 33, 44)) {
                assertTrue(tag, formatted.contains(String.format(Locale.forLanguageTag(tag), "%d", number)))
            }
            assertTrue(tag, localized.resources.getQuantityString(R.plurals.exit_running_term, 2, 2)
                .contains(String.format(Locale.forLanguageTag(tag), "%d", 2)))
        }
    }

    @Test
    fun `Arabic uses RTL and the appropriate terminal session plural`() {
        val locale = Locale.forLanguageTag("ar")
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        assertEquals(View.LAYOUT_DIRECTION_RTL, localized.resources.configuration.layoutDirection)
        for ((count, expected) in listOf(
            0 to "لا توجد جلسات طرفية (%1\$d)",
            1 to "جلسة طرفية واحدة (%1\$d)",
            2 to "جلستان طرفيتان (%1\$d)",
            3 to "%1\$d جلسات طرفية",
            11 to "%1\$d جلسة طرفية",
            100 to "%1\$d جلسة طرفية",
        )) {
            assertEquals("count=$count", String.format(locale, expected, count),
                localized.resources.getQuantityString(R.plurals.exit_running_term, count, count))
        }
    }
}
