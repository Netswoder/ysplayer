package com.ysplayer

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ysplayer.ui.LegalScreen
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class LegalScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun licensesAreAvailableOfflineAndExportAndPrivacyAreReachable() {
        var exported = false
        var returned = false
        compose.setContent { MaterialTheme {
            LegalScreen("Apache License 2.0\nMPL 1.1", "自动补全会向 DLsite 发送 RJ 编号", { returned = true }, { exported = true })
        } }
        compose.onNodeWithText("Apache License 2.0", substring = true).assertExists()
        compose.onNodeWithText("导出 MPL 库源码包").performClick()
        assertTrue(exported)
        compose.onNodeWithText("隐私说明").performClick()
        compose.onNodeWithText("自动补全会向 DLsite 发送 RJ 编号").assertExists()
        compose.onNodeWithText("导出 MPL 库源码包").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        assertTrue(returned)
    }
    @Test fun shippedAssetsContainLicenseTextsAndExactLibrarySourcesWithBuildDescriptor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notices = context.assets.open("legal/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        assertTrue(notices.contains("com.googlecode.juniversalchardet:juniversalchardet:1.0.3"))
        assertTrue(notices.contains("Mozilla Public License"))
        assertTrue(notices.contains("org.jsoup:jsoup:1.18.3"))
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(context.assets.open("legal/juniversalchardet-1.0.3-source.zip")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { entries[entry.name] = zip.readBytes(); entry = zip.nextEntry }
        }
        assertTrue(entries.getValue("pom.xml").decodeToString().contains("<version>1.0.3</version>"))
        assertTrue(entries.getValue("LICENSE-MPL-1.1.txt").decodeToString().contains("Mozilla Public License"))
        assertTrue(entries.getValue("BUILD.txt").decodeToString().contains("javac"))
        var detectorSource = ""
        ZipInputStream(ByteArrayInputStream(entries.getValue("juniversalchardet-1.0.3-sources.jar"))).use { jar ->
            var entry = jar.nextEntry
            while (entry != null) {
                if (entry.name == "org/mozilla/universalchardet/UniversalDetector.java") detectorSource = jar.readBytes().decodeToString()
                entry = jar.nextEntry
            }
        }
        assertTrue(detectorSource.contains("class UniversalDetector"))
        assertTrue(detectorSource.contains("MPL"))
        val privacy = context.assets.open("legal/PRIVACY.txt").bufferedReader().use { it.readText() }
        assertTrue(privacy.contains("自动补全默认开启"))
    }
}
