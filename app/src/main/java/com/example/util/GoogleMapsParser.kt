package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

data class SharedLocation(
    val originalText: String,
    val placeName: String = "",
    val address: String = "",
    val googleMapsUrl: String = "",
    val resolvedUrl: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isKoreanLocation: Boolean = false
)

object GoogleMapsParser {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun extractUrl(text: String): String? {
        val urlRegex = Regex("https?://[^\\s\\n]+")
        return urlRegex.find(text)?.value
    }

    suspend fun resolveShortUrl(urlString: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(urlString)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                client.newCall(request).execute().use { response ->
                    response.request.url.toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun decodePlaceNameFromUrl(url: String): String? {
        try {
            val placeRegex = Regex("/place/([^/@?]+)")
            val match = placeRegex.find(url) ?: Regex("place/([^/@?]+)").find(url)
            if (match != null) {
                val encodedName = match.groupValues[1]
                return URLDecoder.decode(encodedName.replace("+", " "), "UTF-8")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun extractCoordinates(url: String): Pair<Double, Double>? {
        // 1. Try @lat,lng
        val atRegex = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
        val atMatch = atRegex.find(url)
        if (atMatch != null) {
            val lat = atMatch.groupValues[1].toDoubleOrNull()
            val lng = atMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) return Pair(lat, lng)
        }

        // 2. Try !3d!4d Custom maps serialize formats
        val d3d4Regex = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
        val d3d4Match = d3d4Regex.find(url)
        if (d3d4Match != null) {
            val lat = d3d4Match.groupValues[1].toDoubleOrNull()
            val lng = d3d4Match.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) return Pair(lat, lng)
        }

        // 3. Try standard query q= or query=
        val qRegex = Regex("[?&](?:q|query|ll)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
        val qMatch = qRegex.find(url)
        if (qMatch != null) {
            val lat = qMatch.groupValues[1].toDoubleOrNull()
            val lng = qMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) return Pair(lat, lng)
        }

        return null
    }

    fun parseSharedText(text: String): SharedLocation {
        val trimmed = text.trim()
        val lines = trimmed.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        
        if (lines.isEmpty()) {
            return SharedLocation(originalText = text)
        }

        val url = extractUrl(trimmed) ?: ""
        var placeName = lines[0]
        
        if (placeName.startsWith("http")) {
            placeName = decodePlaceNameFromUrl(placeName) ?: "Shared Location"
        }

        val addressLines = lines.filter { line ->
            line != placeName && !line.startsWith("http") && !line.contains("maps.app.goo.gl")
        }
        val address = addressLines.joinToString(", ")

        return SharedLocation(
            originalText = text,
            placeName = placeName,
            address = address,
            googleMapsUrl = url
        )
    }

    /**
     * South Korea lat/lng bounds for warning check
     */
    fun isLatLngInKorea(lat: Double, lng: Double): Boolean {
        // Approx South Korea bounding box
        return lat in 33.0..39.0 && lng in 124.0..132.0
    }
}
