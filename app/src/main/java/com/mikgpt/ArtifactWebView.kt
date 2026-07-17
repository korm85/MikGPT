package com.mikgpt

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactWebView(htmlContent: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
            }
        },
        update = { webView ->
            // Wrap in basic HTML structure if not already fully formatted to make it look professional
            val finalHtml = if (!htmlContent.contains("<html", ignoreCase = true)) {
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                            margin: 0;
                            padding: 16px;
                            background-color: #121212;
                            color: #e0e0e0;
                        }
                    </style>
                </head>
                <body>
                    $htmlContent
                </body>
                </html>
                """.trimIndent()
            } else {
                htmlContent
            }
            webView.loadDataWithBaseURL(null, finalHtml, "text/html", "UTF-8", null)
        }
    )
}
