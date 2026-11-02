package com.ysplayer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.lifecycle.lifecycleScope
import com.ysplayer.ui.LegalScreen
import kotlinx.coroutines.*

class LegalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notices = assets.open("legal/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        val privacy = assets.open("legal/PRIVACY.txt").bufferedReader().use { it.readText() }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    if (uri != null) lifecycleScope.launch {
                        val message = try {
                            withContext(Dispatchers.IO) {
                                val output = contentResolver.openOutputStream(uri) ?: error("无法写入所选文件")
                                output.use { destination -> assets.open("legal/juniversalchardet-1.0.3-source.zip").use { it.copyTo(destination) } }
                            }
                            "已导出原版源码、构建说明与 MPL 1.1 许可"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { "导出失败：${e.message}" }
                        Toast.makeText(this@LegalActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
                LegalScreen(notices, privacy, { finish() }, { export.launch("juniversalchardet-1.0.3-source.zip") })
            }
        }
    }
}
