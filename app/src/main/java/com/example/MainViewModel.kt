package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.util.GoogleMapsParser
import com.example.util.SharedLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val _sharedText = MutableStateFlow("")
    val sharedText: StateFlow<String> = _sharedText.asStateFlow()

    private val _parsedLocation = MutableStateFlow<SharedLocation?>(null)
    val parsedLocation: StateFlow<SharedLocation?> = _parsedLocation.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val _isNaverInstalled = MutableStateFlow(false)
    val isNaverInstalled: StateFlow<Boolean> = _isNaverInstalled.asStateFlow()

    fun checkNaverInstalled(context: Context) {
        val installed = try {
            context.packageManager.getPackageInfo("com.nhn.android.nmap", 0)
            true
        } catch (e: Exception) {
            false
        }
        _isNaverInstalled.value = installed
    }

    fun handleReceivedText(text: String) {
        if (text.isBlank()) return
        _sharedText.value = text
        _isResolving.value = true
        _parsedLocation.value = null

        viewModelScope.launch {
            try {
                // 1. Basic parsing from original shared text
                val initialLocation = GoogleMapsParser.parseSharedText(text)
                
                if (initialLocation.googleMapsUrl.isNotEmpty()) {
                    // 2. Resolve short Google Maps URL (follows redirects)
                    val resolvedUrl = GoogleMapsParser.resolveShortUrl(initialLocation.googleMapsUrl)
                    if (resolvedUrl != null) {
                        // 3. Parse coordinates from resolved URL
                        val coords = GoogleMapsParser.extractCoordinates(resolvedUrl)
                        val lat = coords?.first
                        val lng = coords?.second
                        val inKorea = if (lat != null && lng != null) GoogleMapsParser.isLatLngInKorea(lat, lng) else false
                        
                        // Fallback place name from URL segment if it was generic
                        val decodedName = GoogleMapsParser.decodePlaceNameFromUrl(resolvedUrl)
                        val finalName = if (initialLocation.placeName == "Shared Location" && decodedName != null) {
                            decodedName
                        } else {
                            initialLocation.placeName
                        }

                        _parsedLocation.value = initialLocation.copy(
                            placeName = finalName,
                            resolvedUrl = resolvedUrl,
                            latitude = lat,
                            longitude = lng,
                            isKoreanLocation = inKorea
                        )
                    } else {
                        // If resolve failed, keep the initial text details
                        _parsedLocation.value = initialLocation
                    }
                } else {
                    // No URL, just text. Try to see if this raw text has coordinates inside it
                    val coords = GoogleMapsParser.extractCoordinates(text)
                    val lat = coords?.first
                    val lng = coords?.second
                    val inKorea = if (lat != null && lng != null) GoogleMapsParser.isLatLngInKorea(lat, lng) else false

                    _parsedLocation.value = initialLocation.copy(
                        latitude = lat,
                        longitude = lng,
                        isKoreanLocation = inKorea
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isResolving.value = false
            }
        }
    }

    fun clearState() {
        _sharedText.value = ""
        _parsedLocation.value = null
        _isResolving.value = false
    }

    /**
     * Builds URI schemes for Naver Map
     * Mode: "car" (Navigation/Driving), "walk" (Walking), "bus" (Transit)
     */
    fun launchNaverMap(context: Context, mode: String = "car") {
        val location = _parsedLocation.value ?: return
        val urlScheme: String
        
        if (location.latitude != null && location.longitude != null) {
            // Direct Route Scheme
            // Format: nmap://route/{mode}?dlat={dlat}&dlng={dlng}&dname={dname}&appname=com.example
            val encodedName = Uri.encode(location.placeName)
            urlScheme = "nmap://route/$mode?dlat=${location.latitude}&dlng=${location.longitude}&dname=$encodedName&appname=${context.packageName}"
        } else {
            // Text Search Scheme
            // Format: nmap://search?query={query}&appname=com.example
            val queryParam = Uri.encode(location.placeName.ifEmpty { location.address })
            urlScheme = "nmap://search?query=$queryParam&appname=${context.packageName}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlScheme)).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: If Naver Map scheme fails, open Web fallback, or open Play Store
            openWebFallback(context, location)
        }
    }

    fun openPlayStoreForNaver(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.nhn.android.nmap")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.nhn.android.nmap"))
            context.startActivity(webIntent)
        }
    }

    private fun openWebFallback(context: Context, location: SharedLocation) {
        val webUrl = if (location.latitude != null && location.longitude != null) {
            // Web view of specific coordinate
            "https://map.naver.com/v5/entry/address/${location.latitude},${location.longitude}"
        } else {
            // Web search fallback
            val querySegment = Uri.encode(location.placeName.ifEmpty { location.address })
            "https://map.naver.com/v5/search/$querySegment"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
        context.startActivity(intent)
    }
}
