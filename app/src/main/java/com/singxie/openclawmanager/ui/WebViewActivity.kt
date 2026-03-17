package com.singxie.openclawmanager.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.singxie.openclawmanager.ui.theme.OpenClawManagerTheme

/**
 * 独立 Activity，用于展示用户协议或隐私政策 HTML 页面。
 * 通过 Intent 传入 EXTRA_PAGE：user_agreement / privacy_policy。
 * 返回后回到调用方（如隐私弹窗），仅点击「同意」才进入主页。
 */
class WebViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: "privacy_policy"
        val titleText = when (page) {
            PAGE_USER_AGREEMENT -> "用户协议"
            PAGE_PRIVACY_POLICY -> "隐私政策"
            else -> "隐私政策"
        }
        setTitle(titleText)

        setContent {
            OpenClawManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WebViewScreen(
                        page = page,
                        title = titleText,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_USER_AGREEMENT = "user_agreement"
        const val PAGE_PRIVACY_POLICY = "privacy_policy"

        fun intent(context: Context, page: String): Intent {
            return Intent(context, WebViewActivity::class.java).putExtra(EXTRA_PAGE, page)
        }
    }
}

@Composable
private fun WebViewScreen(
    page: String,
    title: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    loadUrl("file:///android_asset/$page.html")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        )
    }
}
