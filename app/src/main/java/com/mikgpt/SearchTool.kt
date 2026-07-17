package com.mikgpt

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SearchTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun performSearch(query: String): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // DuckDuckGo Lite version is perfect for headless HTML scraping as it does not require JS
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Search failed with code: ${response.code}"
                val html = response.body?.string() ?: return "Empty search results"
                parseResults(html)
            }
        } catch (e: IOException) {
            "Failed to reach search provider: ${e.message}"
        }
    }

    private fun parseResults(html: String): String {
        val doc = Jsoup.parse(html)
        val resultElements = doc.select(".result")
        if (resultElements.isEmpty()) {
            return "No search results found."
        }

        val formattedResults = StringBuilder()
        for (i in 0 until minOf(resultElements.size, 5)) {
            val element = resultElements[i]
            val titleElement = element.select(".result__title").first()
            val snippetElement = element.select(".result__snippet").first()
            val urlElement = element.select(".result__url").first()

            val title = titleElement?.text() ?: "No Title"
            val snippet = snippetElement?.text() ?: "No Snippet"
            val url = urlElement?.text() ?: ""

            formattedResults.append("Result ${i + 1}:\n")
            formattedResults.append("Title: $title\n")
            formattedResults.append("Snippet: $snippet\n")
            if (url.isNotEmpty()) {
                formattedResults.append("URL: https://$url\n")
            }
            formattedResults.append("\n")
        }
        return formattedResults.toString()
    }
}
