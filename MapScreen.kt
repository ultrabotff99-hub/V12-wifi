package com.tonix.wifireader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.pow

data class ApPin(val ssid: String, val point: GeoPoint, val approximate: Boolean, val distanceMeters: Double, val level: Int)

/** colour by real signal strength: green = strong, yellow = medium, red = weak */
fun wifiSignalColor(level: Int): Int = when {
    level >= -60 -> Color.parseColor("#2BFF5A")   // Strong  -30..-60
    level >= -75 -> Color.parseColor("#FFE81F")   // Medium  -61..-75
    else -> Color.parseColor("#FF3B30")           // Weak    -76 and below
}

class PulseOverlay(private val pins: () -> List<ApPin>) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wifiLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(225, 5, 12, 22) }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; isFakeBoldText = true; color = Color.WHITE }
    private val dbmP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; isFakeBoldText = true }
    fun tick() { phase = (phase + 0.02f) % 1f }

    /** small wifi glyph, coloured by real signal strength */
    private fun wifiIcon(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.style = Paint.Style.FILL; fill.color = Color.argb(235, 6, 14, 24)
        c.drawCircle(cx, cy, r, fill)
        dot.style = Paint.Style.STROKE; dot.strokeWidth = 2.5f; dot.color = color; dot.alpha = 255
        c.drawCircle(cx, cy, r, dot)
        wifiLine.color = color; wifiLine.alpha = 255
        for (i in 1..3) {
            val rr = i * r * 0.30f
            rect.set(cx - rr, cy + r * 0.26f - rr, cx + rr, cy + r * 0.26f + rr)
            wifiLine.strokeWidth = r * 0.16f
            c.drawArc(rect, 225f, 90f, false, wifiLine)
        }
        fill.style = Paint.Style.FILL; fill.color = color
        c.drawCircle(cx, cy + r * 0.26f, r * 0.14f, fill)
    }

    override fun draw(c: Canvas, proj: Projection) {
        for (pin in pins()) {
            val p = proj.toPixels(pin.point, null)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            val col = wifiSignalColor(pin.level)
            val rangePx = proj.metersToEquatorPixels(pin.distanceMeters.toFloat())
            fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 18
            c.drawCircle(px, py, rangePx, fill); fill.alpha = 255

            // pulse ring: solid once located, dashed while still "rough"
            ring.color = col; ring.alpha = (220 * (1 - phase)).toInt()
            c.drawCircle(px, py, 16f + 34f * phase, ring)

            wifiIcon(c, px, py, 22f, col)

            // name + dBm chip, colour-coded by real signal strength
            val name = if (pin.ssid.isEmpty()) "<hidden>" else pin.ssid
            val full = "📶 $name"
            nameP.textAlign = Paint.Align.LEFT
            val nameW = nameP.measureText(full)
            val dbmTxt = "${pin.level} dBm" + if (pin.approximate) "  ~" else ""
            dbmP.textAlign = Paint.Align.LEFT
            val dbmW = dbmP.measureText(dbmTxt)
            val gap = 14f
            val tw = nameW + gap + dbmW + 24f
            rect.set(px - tw / 2, py + 28f, px + tw / 2, py + 58f)
            chipBg.alpha = 225; c.drawRoundRect(rect, 10f, 10f, chipBg)
            dot.style = Paint.Style.STROKE; dot.strokeWidth = 2f; dot.color = col; dot.alpha = 200
            c.drawRoundRect(rect, 10f, 10f, dot)
            val x0 = px - tw / 2 + 12f
            nameP.color = Color.WHITE
            c.drawText(full, x0, py + 47f, nameP)
            dbmP.color = col
            c.drawText(dbmTxt, x0 + nameW + gap, py + 47f, dbmP)
        }
    }
}

class SelfPulseOverlay(private val point: () -> GeoPoint?) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.parseColor("#00FFC6") }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fun tick() { phase = (phase + 0.03f) % 1f }
    override fun draw(c: Canvas, proj: Projection) {
        val gp = point() ?: return
        val p = proj.toPixels(gp, null)
        ring.alpha = (255 * (1 - phase)).toInt()
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 16f + 50f * phase, ring)
        core.shader = RadialGradient(p.x.toFloat(), p.y.toFloat(), 22f, Color.WHITE, Color.parseColor("#0000E5FF"), Shader.TileMode.CLAMP)
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 22f, core); core.shader = null
        core.color = Color.parseColor("#00E5FF")
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, core)
        core.color = Color.WHITE
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 4f, core)
    }
}

/**
 * Live map: your position follows GPS continuously, and EVERY nearby Wi-Fi device is tracked live
 * (new scan every few seconds). A network's pin is estimated from real (GPS position, signal) samples
 * collected while you move - walk around and the pins sharpen. No random/fake positions.
 */
@Suppress("DEPRECATION")
class MapScreen(ctx: Context, myLocation: GeoPoint?, networks: List<ScanResult>) : FrameLayout(ctx) {
    private class Track(var ssid: String) {
        var level = -100
        var seen = 0L
        val samples = ArrayList<Pair<GeoPoint, Double>>()
    }

    private val map = MapView(ctx)
    private val tracks = LinkedHashMap<String, Track>()
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var selfPoint: GeoPoint? = myLocation      // null until a REAL GPS fix arrives
    private var accuracy = 0f
    private var gotFix = myLocation != null
    private var lastGpsT = 0L
    private var locReg = false
    private val pulse = PulseOverlay { buildPins() }
    private val selfPulse = SelfPulseOverlay { selfPoint }
    private val note: TextView
    private val followBtn: TextView
    private var follow = true
    private var running = false
    private var receiver: BroadcastReceiver? = null

    private val listener = LocationListener { loc: Location -> onFix(loc) }

    private val pulseLoop = object : Runnable {
        override fun run() {
            if (!running) return
            pulse.tick(); selfPulse.tick(); map.invalidate()
            handler.postDelayed(this, 40)
        }
    }
    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try { wm.startScan() } catch (e: Exception) { }
            ensureLocation()
            handler.postDelayed(this, 4000)
        }
    }

    init {
        Configuration.getInstance().userAgentValue = ctx.packageName
        // satellite tiles (the same source that loads in the AR minimap)
        val sat = object : OnlineTileSourceBase("TonixSat", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
            override fun getTileURLString(i: Long): String =
                getBaseUrl() + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
        }
        map.setTileSource(sat)
        map.setMultiTouchControls(true)
        if (myLocation != null) { map.controller.setZoom(18.0); map.controller.setCenter(myLocation) }
        else { map.controller.setZoom(4.0); map.controller.setCenter(GeoPoint(20.5937, 78.9629)) }   // camera only, no "You" marker
        map.overlays.add(pulse)
        map.overlays.add(selfPulse)
        map.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_DOWN) setFollow(false); false }
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        note = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f; setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#CC05070D"))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        followBtn = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#00E5FF")); setPadding(28, 16, 28, 16)
            setBackgroundColor(Color.parseColor("#CC05070D"))
            setOnClickListener { setFollow(true); selfPoint?.let { map.controller.animateTo(it) } }
        }
        addView(followBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, 16, 16, 0) })
        setFollow(true)

        absorb(networks)
        onResumeMap()
    }

    private fun setFollow(on: Boolean) {
        follow = on
        followBtn.text = if (on) "◎ FOLLOWING YOU" else "◌ TAP TO FOLLOW"
    }

    /** add one real sample (my GPS position + the signal I measured there) for each network */
    private fun absorb(list: List<ScanResult>) {
        val now = SystemClock.elapsedRealtime()
        val me = selfPoint
        val okFix = me != null && (accuracy == 0f || accuracy <= 40f)
        for (r in list) {
            val t = tracks.getOrPut(r.BSSID) { Track(r.SSID) }
            t.ssid = if (r.SSID.isNullOrEmpty()) "<hidden>" else r.SSID
            t.level = r.level; t.seen = now
            if (okFix && me != null) {
                t.samples.add(Pair(GeoPoint(me.latitude, me.longitude), 10.0.pow(r.level / 10.0)))
                if (t.samples.size > 40) t.samples.removeAt(0)
            }
        }
        val it = tracks.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.seen > 30000) it.remove()
        refreshNote()
    }

    private fun refreshNote() {
        note.text = if (!gotFix)
            "⏳ Waiting for real GPS fix... turn ON Location and go near a window / outside\n● ${tracks.size} Wi-Fi devices found"
        else
            "● You: live GPS" + (if (accuracy > 0) " ±${accuracy.toInt()} m" else "") + "   ● ${tracks.size} Wi-Fi devices live (rescans every 4s)\n" +
            "Pins refine as you walk. Yellow = rough, blue = refined. Ring = signal range."
    }

    private fun buildPins(): List<ApPin> {
        val me = selfPoint
        val out = ArrayList<ApPin>(tracks.size)
        for (t in tracks.values) {
            var lat = 0.0; var lon = 0.0; var wsum = 0.0
            for ((p, w) in t.samples) { lat += p.latitude * w; lon += p.longitude * w; wsum += w }
            val pt = if (wsum > 0) GeoPoint(lat / wsum, lon / wsum) else me ?: continue
            out.add(ApPin(t.ssid, pt, t.samples.size < 4, SignalMath.estimateDistanceMeters(t.level), t.level))
        }
        return out
    }

    // ---------------- real GPS ----------------
    private fun hasLoc() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** (re)register GPS updates; retried every scan so it also starts after the permission is granted */
    private fun ensureLocation() {
        if (locReg || !hasLoc()) return
        try {
            val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            // only a recent fix counts as "you are here" (an old one could be a different place)
            if (last != null && System.currentTimeMillis() - last.time < 5 * 60 * 1000L) onFix(last)
        } catch (e: SecurityException) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { locMgr.requestLocationUpdates(p, 1000L, 0f, listener); locReg = true } catch (e: Exception) { }
        }
    }

    private fun onFix(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsT = now
        else if (now - lastGpsT < 10000) return          // keep the accurate GPS fix, ignore coarse network fixes
        val gp = GeoPoint(loc.latitude, loc.longitude)
        selfPoint = gp
        accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
        if (!gotFix) { gotFix = true; map.controller.setZoom(18.0); map.controller.setCenter(gp) }
        else if (follow) map.controller.animateTo(gp)
        refreshNote()
    }

    fun onResumeMap() {
        if (running) return
        running = true
        map.onResume()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                try { absorb(wm.scanResults) } catch (e: Exception) { }
            }
        }
        try { context.applicationContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) { }
        ensureLocation()
        handler.post(pulseLoop); handler.post(scanLoop)
    }

    fun onPauseMap() {
        running = false
        handler.removeCallbacksAndMessages(null)
        map.onPause()
        try { locMgr.removeUpdates(listener) } catch (e: Exception) { }
        locReg = false
        try { receiver?.let { context.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
    }
}
