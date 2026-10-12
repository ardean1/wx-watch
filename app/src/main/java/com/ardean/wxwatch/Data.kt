package com.ardean.wxwatch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/** Same free, keyless feeds the marion-wx-map app uses. */
object Feeds {
    const val ESRI_BASE = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile"
    const val ESRI_REF = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile"
    const val IEM_TMS = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q"
    const val ADSB = "https://api.adsb.lol/v2/point"
    const val ZIP = "https://api.zippopotam.us/us"
    fun esri(base: String, z: Int, x: Int, y: Int) = "$base/$z/$y/$x"
    fun radar(z: Int, x: Int, y: Int, bust: Long) = "$IEM_TMS/$z/$x/$y.png?t=$bust"
}

data class Plane(
    val id: String, val call: String, val lat: Double, val lon: Double,
    val track: Double, val altFt: Int?, val gsKt: Double?
)

object Net {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("User-Agent", "wx-watch/0.1 (Wear OS)")
        }

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun tile(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url) ?: runCatching {
            val c = open(url)
            try {
                if (c.responseCode != 200) null
                else c.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(url, it) }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** adsb.lol /v2/point/{lat}/{lon}/{radius nm}; called directly over HTTPS, no proxy. */
    suspend fun aircraft(lat: Double, lon: Double, distNm: Int): List<Plane> {
        val j = JSONObject(text("${Feeds.ADSB}/${"%.4f".format(lat)}/${"%.4f".format(lon)}/$distNm"))
        val arr = j.optJSONArray("ac") ?: return emptyList()
        val out = ArrayList<Plane>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (!a.has("lat") || !a.has("lon")) continue
            val alt = a.opt("alt_baro")
            out += Plane(
                id = a.optString("hex"),
                call = a.optString("flight").trim().ifEmpty { a.optString("hex") },
                lat = a.getDouble("lat"), lon = a.getDouble("lon"),
                track = a.optDouble("track", 0.0),
                altFt = if (alt is Number) alt.toInt() else null,
                gsKt = if (a.has("gs")) a.optDouble("gs") else null
            )
        }
        return out
    }

    /** ZIP -> lat/lon via zippopotam.us (as in the phone app). */
    suspend fun zip(zip: String): Pair<Double, Double>? = runCatching {
        val p = JSONObject(text("${Feeds.ZIP}/$zip")).getJSONArray("places").getJSONObject(0)
        p.getString("latitude").toDouble() to p.getString("longitude").toDouble()
    }.getOrNull()
}

/** Web Mercator helpers (256px tiles). */
object Merc {
    fun worldX(lon: Double, z: Double) = (lon + 180.0) / 360.0 * 256.0 * 2.0.pow(z)
    fun worldY(lat: Double, z: Double): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 256.0 * 2.0.pow(z)
    }
    /** Fractional zoom so that radiusMiles spans halfPx pixels at this latitude. */
    fun zoomFor(radiusMiles: Double, lat: Double, halfPx: Double): Double {
        val mpp = radiusMiles * 1609.344 / halfPx
        return log2(156543.03392 * cos(Math.toRadians(lat)) / mpp).coerceIn(3.0, 15.0)
    }
}
