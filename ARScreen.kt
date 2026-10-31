package com.tonix.wifireader

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Mini {
    /** minimap rectangle (top-left of the AR screen), shared by the map view and the HUD border */
    fun rect(w: Float, d: Float) = RectF(12f * d, 8f * d, w * 0.62f, 158f * d)
}

private fun sigColor(r: Int) = when {
    r >= -60 -> Color.parseColor("#2BFF5A")     // strong
    r >= -75 -> Color.parseColor("#FFE81F")     // medium
    else -> Color.parseColor("#FF2D2D")         // weak
}

/** Live camera + minimap + floating 3D Wi-Fi pins + guide arrow. All values are real (see comments). */
@Suppress("DEPRECATION")
class ARScreen(ctx: Context, private val targetBssid: String, private val targetSsid: String) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private var camera: Camera? = null
    private val surface = SurfaceView(ctx)
    private val tele = WifiTelemetry(ctx, targetBssid, targetSsid)
    private val beeper = Beeper { if (tele.inRange) tele.rssi else null }
    private val compass = Compass(ctx)
    private val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val map = MapView(ctx)
    private val mapBox = FrameLayout(ctx)
    private val mini = MiniOverlay(d)
    private val hud: HudView
    private val camMsg: TextView
    private val beepBtn: TextView
    private val anchorBtn: TextView
    private val listBtn: TextView
    private val fed = HashMap<String, Long>()
    private var lastLiveFeed = 0L
    private var lastLiveRssi = 999
    private var beepOn = false
    @Volatile private var me: DoubleArray? = null
    private var running = false
    private var lastFit = 0L
    private var centered = false

    private val locListener = LocationListener { l: Location ->
        me = doubleArrayOf(l.latitude, l.longitude)
        compass.setLocation(l)
    }

    private val tick = object : Runnable {
        override fun run() { if (!running) return; onTick(); handler.postDelayed(this, 250) }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // ----- minimap (satellite tiles) -----
        Configuration.getInstance().userAgentValue = ctx.packageName
        val sat = object : OnlineTileSourceBase("TonixSat", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
            override fun getTileURLString(i: Long): String =
                getBaseUrl() + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
        }
        map.setTileSource(sat)
        map.setBuiltInZoomControls(false)
        map.setMultiTouchControls(false)
        map.controller.setZoom(19.0)
        map.overlays.add(mini)
        map.setOnTouchListener { _, _ -> true }
        mapBox.addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapBox.clipToOutline = true
        mapBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 14f * d) }
        }
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val mr = Mini.rect(sw, d)
        addView(mapBox, LayoutParams(mr.width().toInt(), mr.height().toInt(), Gravity.TOP or Gravity.START).apply {
            setMargins(mr.left.toInt(), mr.top.toInt(), 0, 0)
        })

        hud = HudView(ctx, tele, compass, targetBssid) { me }
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        camMsg = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FF6B6B")); textSize = 12f; gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24); visibility = View.GONE
        }
        addView(camMsg, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        beepBtn = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#990B1622"))
            setOnClickListener { setBeep(!beepOn) }
        }
        addView(beepBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 14, (112 * d).toInt()) })
        setBeep(false)

        anchorBtn = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.parseColor("#FFE81F")); typeface = Typeface.MONOSPACE
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#990B1622"))
            setOnClickListener { toggleAnchor() }
        }
        addView(anchorBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 14, (150 * d).toInt()) })
        listBtn = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.parseColor("#00E5FF")); typeface = Typeface.MONOSPACE
            setPadding(24, 12, 24, 12); text = "📋 ALL NETWORKS"
            setBackgroundColor(Color.parseColor("#990B1622"))
            setOnClickListener { showList() }
        }
        addView(listBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, (166 * d).toInt(), 12, 0) })
        refreshAnchorBtn()

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { openCamera(holder) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { releaseCamera() }
        })
        startAll()
    }

    private fun startAll() {
        if (running) return
        running = true
        tele.start(); compass.start(); map.onResume()
        try {
            val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) { me = doubleArrayOf(last.latitude, last.longitude); compass.setLocation(last) }
        } catch (e: SecurityException) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { locMgr.requestLocationUpdates(p, 1000L, 0f, locListener) } catch (e: Exception) { }
        }
        handler.post(tick)
    }

    private fun stopAll() {
        running = false
        handler.removeCallbacksAndMessages(null)
        tele.stop(); compass.stop(); map.onPause()
        try { locMgr.removeUpdates(locListener) } catch (e: Exception) { }
    }

    /** feed REAL samples to the locator + move the minimap */
    private fun onTick() {
        try { tickBody() } catch (e: Exception) { }
    }

    private fun tickBody() {
        val m = me
        val nowMs = SystemClock.elapsedRealtime()
        if (m != null) ApLocator.checkSweepOrigin(m[0], m[1])
        // every scanned network: dBm together with the compass heading at the moment the radio measured it
        for (ap in tele.all) {
            if (fed[ap.bssid] == ap.tsMs) continue
            fed[ap.bssid] = ap.tsMs
            ApLocator.addSweep(ap.bssid, ap.level, compass.headingAt(ap.tsMs), ap.tsMs)
            if (m != null) ApLocator.add(ap.bssid, ap.level, m[0], m[1])
        }
        // connected target: live reading straight from the Wi-Fi chip
        val r = tele.rssi
        if (tele.connected && r != null && nowMs - lastLiveFeed >= 1000 && (r != lastLiveRssi || nowMs - lastLiveFeed >= 2500)) {
            lastLiveFeed = nowMs; lastLiveRssi = r
            ApLocator.addSweep(targetBssid, r, compass.headingAt(nowMs - 1200), nowMs)
            if (m != null) ApLocator.add(targetBssid, r, m[0], m[1])
        }
        updateMap()
        refreshAnchorBtn()
    }

    private fun refreshAnchorBtn() {
        anchorBtn.text = if (ApLocator.hasManual(targetBssid)) "✖ CLEAR PIN" else "📌 SET ROUTER HERE"
    }

    /** you aim the camera at the real router and press: the pin is fixed to that direction (always correct if you aimed right) */
    private fun toggleAnchor() {
        if (ApLocator.hasManual(targetBssid)) { ApLocator.clearManual(targetBssid); refreshAnchorBtn(); return }
        val m = me; val hd = compass.heading; val r = tele.rssi
        if (m == null || hd.isNaN() || r == null) {
            Toast.makeText(context, "Need GPS, compass and the Wi-Fi in range", Toast.LENGTH_SHORT).show(); return
        }
        val dm = SignalMath.estimateDistanceMeters(r).coerceIn(1.0, 60.0)
        val p = ApLocator.offset(m[0], m[1], hd.toDouble(), dm)
        ApLocator.setManual(targetBssid, p.first, p.second)
        refreshAnchorBtn()
        Toast.makeText(context, "Router pinned in the direction you are facing", Toast.LENGTH_SHORT).show()
    }

    /** every network around: name, dBm, distance, channel and the direction its signal comes from */
    private fun showList() {
        val m = me
        val all = tele.all.sortedByDescending { it.level }
        val rows = ArrayList<String>()
        for (ap in all) {
            val dm = SignalMath.estimateDistanceMeters(ap.level)
            var dir = "direction: not found yet"
            if (m != null) {
                val lc = ApLocator.locate(ap.bssid, m[0], m[1], ap.level)
                if (lc != null) dir = "from " + ApLocator.compassName(lc.bearing) + " " + lc.bearing.toInt() + "° (" + lc.label + ")"
            }
            val nm = if (ap.ssid.isEmpty()) "<hidden>" else ap.ssid
            val star = if (ap.bssid.equals(targetBssid, true)) "★ " else ""
            rows.add(star + nm + "\n" + ap.level + " dBm · ≈ " + ApLocator.fmtDist(dm) + " · " + ap.freq + " MHz CH " + ApLocator.channelOf(ap.freq) + "\n" + dir)
        }
        AlertDialog.Builder(context).setTitle(all.size.toString() + " networks around you")
            .setItems(rows.toTypedArray(), null).setPositiveButton("OK", null).show()
    }

    private fun updateMap() {
        val m = me ?: return
        val gp = GeoPoint(m[0], m[1])
        mini.me = gp
        mini.heading = compass.heading
        val r = tele.rssi
        mini.rangeM = if (tele.inRange && r != null) SignalMath.estimateDistanceMeters(r) else 0.0
        mini.tgtColor = if (r != null) sigColor(r) else Color.parseColor("#FF2D2D")
        mini.tgtLabel = if (targetSsid.length > 10) targetSsid.take(9) + "…" else targetSsid
        val loc = if (r != null) ApLocator.locate(targetBssid, m[0], m[1], r) else null
        if (loc != null) {
            val tp = GeoPoint(loc.lat, loc.lon)
            mini.tgt = tp
            val dist = ApLocator.distM(m[0], m[1], loc.lat, loc.lon)
            val now = SystemClock.elapsedRealtime()
            if (dist >= 3.0 && map.width > 0) {
                if (now - lastFit > 1500) {
                    lastFit = now
                    try { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(gp, tp)), false, (36 * d).toInt()) } catch (e: Exception) { }
                }
            } else { map.controller.setZoom(19.0); map.controller.setCenter(gp) }
        } else {
            mini.tgt = null
            map.controller.setCenter(gp)
            if (!centered) { map.controller.setZoom(19.0); centered = true }
        }
        map.invalidate()
    }

    private fun setBeep(on: Boolean) {
        beepOn = on
        beepBtn.text = if (on) "🔊 BEEP ON" else "🔈 BEEP OFF"
        if (on) beeper.start() else beeper.stop()
    }

    private fun displayRotation(): Int {
        val w = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (w.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun openCamera(holder: SurfaceHolder) {
        try {
            releaseCamera()
            val info = Camera.CameraInfo()
            var id = 0
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break }
            }
            Camera.getCameraInfo(id, info)
            val cam = Camera.open(id)

            val rot = displayRotation()
            val orient = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                (360 - (info.orientation + rot) % 360) % 360
            else (info.orientation - rot + 360) % 360
            cam.setDisplayOrientation(orient)
            val portraitLike = orient == 90 || orient == 270

            val vw = if (width > 0) width else resources.displayMetrics.widthPixels
            val vh = if (height > 0) height else resources.displayMetrics.heightPixels
            val params = cam.parameters
            val target = if (portraitLike) vh.toDouble() / vw else vw.toDouble() / vh
            val best = params.supportedPreviewSizes
                .filter { it.width * it.height <= 1920 * 1080 }
                .minWithOrNull(compareBy<Camera.Size>({ (abs(it.width.toDouble() / it.height - target) * 100).toInt() }, { -(it.width * it.height) }))
                ?: params.supportedPreviewSizes.first()
            params.setPreviewSize(best.width, best.height)
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true)
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            try { cam.parameters = params } catch (e: Exception) { }
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            camera = cam
            camMsg.visibility = View.GONE

            val dw = if (portraitLike) best.height else best.width
            val dh = if (portraitLike) best.width else best.height
            val scale = max(vw.toFloat() / dw, vh.toFloat() / dh)
            // real horizontal field of view that is visible on screen (sensor angle, reduced by the cover-crop)
            val fovDeg = if (portraitLike) params.verticalViewAngle else params.horizontalViewAngle
            if (fovDeg in 20f..120f) {
                val vis = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fovDeg / 2.0)) * vw / (dw * scale))).toFloat()
                hud.hfov = vis.coerceIn(30f, 110f)
            }
            val lp = LayoutParams((dw * scale).toInt(), (dh * scale).toInt(), Gravity.CENTER)
            post { surface.layoutParams = lp }
        } catch (e: Exception) {
            camMsg.text = "Camera unavailable (${e.message ?: "busy"}).\nClose other camera apps and reopen."
            camMsg.visibility = View.VISIBLE
        }
    }

    private fun releaseCamera() {
        try { camera?.stopPreview() } catch (e: Exception) { }
        try { camera?.release() } catch (e: Exception) { }
        camera = null
    }

    fun resume() { startAll(); if (beepOn) beeper.start() }
    fun pause() { beeper.stop(); releaseCamera(); stopAll() }
    fun stop() { pause() }
}

/** Minimap overlay: "You" (with view cone), router pin, dashed arrow to it. */
class MiniOverlay(private val d: Float) : Overlay() {
    var me: GeoPoint? = null
    var tgt: GeoPoint? = null
    var tgtColor = Color.GREEN
    var tgtLabel = ""
    var rangeM = 0.0
    var heading = Float.NaN
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val rect = RectF()
    private val path = Path()

    private fun chip(c: Canvas, s: String, x: Float, y: Float, bg: Int, fg: Int, size: Float) {
        tp.textSize = size * d
        val w = tp.measureText(s) + 14 * d
        rect.set(x - w / 2, y, x + w / 2, y + size * d + 10 * d)
        p.style = Paint.Style.FILL; p.shader = null; p.color = bg
        c.drawRoundRect(rect, 6 * d, 6 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = fg; p.alpha = 220
        c.drawRoundRect(rect, 6 * d, 6 * d, p)
        tp.color = Color.WHITE
        c.drawText(s, x, y + size * d + 3 * d, tp)
    }

    override fun draw(c: Canvas, proj: Projection) {
        try { drawMini(c, proj) } catch (e: Exception) { }
    }

    private fun drawMini(c: Canvas, proj: Projection) {
        val m = me ?: return
        val mp = proj.toPixels(m, null)
        val mx = mp.x.toFloat(); val my = mp.y.toFloat()
        val tg = tgt
        val blue = Color.parseColor("#2979FF")
        val phase = (System.currentTimeMillis() % 1200L) / 1200f

        if (tg != null) {
            val tpnt = proj.toPixels(tg, null)
            val tx = tpnt.x.toFloat(); val ty = tpnt.y.toFloat()
            // dashed arrow You -> Router
            p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f * d; p.color = Color.parseColor("#29B6F6"); p.alpha = 255
            p.pathEffect = DashPathEffect(floatArrayOf(7 * d, 6 * d), -phase * 13 * d)
            c.drawLine(mx, my, tx, ty, p); p.pathEffect = null
            val ang = Math.atan2((ty - my).toDouble(), (tx - mx).toDouble())
            val hx = tx - (22 * d * Math.cos(ang)).toFloat(); val hy = ty - (22 * d * Math.sin(ang)).toFloat()
            path.reset()
            path.moveTo(hx + (10 * d * Math.cos(ang)).toFloat(), hy + (10 * d * Math.sin(ang)).toFloat())
            path.lineTo(hx + (7 * d * Math.cos(ang + 2.3)).toFloat(), hy + (7 * d * Math.sin(ang + 2.3)).toFloat())
            path.lineTo(hx + (7 * d * Math.cos(ang - 2.3)).toFloat(), hy + (7 * d * Math.sin(ang - 2.3)).toFloat())
            path.close(); p.style = Paint.Style.FILL; p.color = Color.parseColor("#29B6F6"); c.drawPath(path, p)

            // router marker
            rect.set(tx - 17 * d, ty - 28 * d, tx + 17 * d, ty - 2 * d)
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#E6071A10"); c.drawRoundRect(rect, 7 * d, 7 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = tgtColor; c.drawRoundRect(rect, 7 * d, 7 * d, p)
            for (i in 1..2) {
                val rr = i * 5f * d
                rect.set(tx - rr, ty - 16 * d - rr, tx + rr, ty - 16 * d + rr)
                p.strokeWidth = 2f * d; p.color = tgtColor
                c.drawArc(rect, 225f, 90f, false, p)
            }
            p.style = Paint.Style.FILL; c.drawCircle(tx, ty - 16 * d, 1.8f * d, p)
            chip(c, tgtLabel, tx, ty + 2 * d, Color.parseColor("#E6071A10"), tgtColor, 9f)
        } else if (rangeM > 0) {
            // router direction unknown yet: show the real signal range around you
            val rr = proj.metersToEquatorPixels(rangeM.toFloat()).coerceIn(14f * d, 90f * d)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = tgtColor; p.alpha = 220
            p.pathEffect = DashPathEffect(floatArrayOf(6 * d, 6 * d), phase * 12 * d)
            c.drawCircle(mx, my, rr, p); p.pathEffect = null
        }

        // view cone (where the camera points)
        if (!heading.isNaN()) {
            rect.set(mx - 46 * d, my - 46 * d, mx + 46 * d, my + 46 * d)
            p.style = Paint.Style.FILL; p.color = Color.argb(70, 41, 121, 255)
            c.drawArc(rect, heading - 90f - 28f, 56f, true, p)
        }
        // You
        p.style = Paint.Style.FILL; p.shader = RadialGradient(mx, my, 20 * d, Color.argb(190, 41, 121, 255), Color.argb(0, 41, 121, 255), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, 20 * d + 4 * d * phase, p); p.shader = null
        p.color = Color.WHITE; c.drawCircle(mx, my, 9 * d, p)
        p.color = blue; c.drawCircle(mx, my, 6.5f * d, p)
        chip(c, "You", mx, my + 12 * d, blue, Color.WHITE, 10f)
    }
}

/** Floating 3D pins, guide arrow, legend, compass, bottom status bar. Every number comes from real sensors/scans. */
class HudView(ctx: Context, private val t: WifiTelemetry, private val compass: Compass,
              private val targetBssid: String, private val meProvider: () -> DoubleArray?) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var phase = 0f
    private var shownRssi = -100f
    private var shownMbps = 0f
    /** horizontal field of view of what is visible on screen (set from the real camera parameters) */
    @Volatile var hfov = 52f
    private val smoothDir = HashMap<String, DoubleArray>()

    private class Pin(val bssid: String, val ssid: String, val level: Int, val dist: Double, val rel: Float?, val target: Boolean, val line2: String, val src: String)

    private val loop = object : Runnable {
        override fun run() { phase = (System.currentTimeMillis() % 3000L) / 3000f; invalidate(); postDelayed(this, 40) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(loop) }
    override fun onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow() }

    private fun bars(r: Int) = when { r >= -55 -> 4; r >= -65 -> 3; r >= -75 -> 2; r >= -85 -> 1; else -> 0 }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, a: Paint.Align = Paint.Align.CENTER) {
        txt.textSize = size * d; txt.color = color; txt.textAlign = a
        txt.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        c.drawText(s, x, y, txt)
    }

    private fun glass(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float, accent: Int, bg: Int = Color.argb(185, 6, 14, 24)) {
        rect.set(l, tp, r, b)
        fill.style = Paint.Style.FILL; fill.shader = null; fill.color = bg
        c.drawRoundRect(rect, rad, rad, fill)
        for (i in 3 downTo 1) { line.color = accent; line.alpha = 26 * (4 - i); line.strokeWidth = (1f + i * 2f) * d; c.drawRoundRect(rect, rad, rad, line) }
        line.color = accent; line.alpha = 240; line.strokeWidth = 1.6f * d; c.drawRoundRect(rect, rad, rad, line)
    }

    private fun wifiGlyph(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, lit: Int = 3) {
        line.strokeCap = Paint.Cap.ROUND
        for (i in 1..3) {
            val rr = i * 6.2f * s
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.strokeWidth = 2.6f * s; line.color = color
            line.alpha = if (i <= lit) 255 else 70
            c.drawArc(rect, 225f, 90f, false, line)
        }
        line.strokeCap = Paint.Cap.BUTT
        fill.style = Paint.Style.FILL; fill.color = color; c.drawCircle(cx, cy, 2.4f * s, fill)
    }

    /** one floating 3D pin: ground ring, beam, glass label (dBm + Mbps/dist), teardrop with wifi glyph */
    private fun drawPin(c: Canvas, cx: Float, groundY: Float, s: Float, col: Int, level: Int, line2: String, name: String, seed: Float) {
        val bob = (sin((phase + seed) * 2 * Math.PI) * 5 * d * s).toFloat()
        // ground rings (pulsing)
        val pulse = (phase + seed) % 1f
        line.strokeWidth = 3f * d * s; line.color = col; line.alpha = 230
        rect.set(cx - 46 * d * s, groundY - 11 * d * s, cx + 46 * d * s, groundY + 11 * d * s); c.drawOval(rect, line)
        line.strokeWidth = 2f * d * s; line.alpha = (200 * (1 - pulse)).toInt()
        val pr = 46f + 34f * pulse
        rect.set(cx - pr * d * s, groundY - pr * 0.24f * d * s, cx + pr * d * s, groundY + pr * 0.24f * d * s); c.drawOval(rect, line)
        fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 40
        rect.set(cx - 40 * d * s, groundY - 9 * d * s, cx + 40 * d * s, groundY + 9 * d * s); c.drawOval(rect, fill); fill.alpha = 255
        // beam
        val boxBottom = groundY - 20 * d * s + bob
        line.strokeWidth = 2f * d * s; line.color = col; line.alpha = 140
        c.drawLine(cx, boxBottom, cx, groundY - 4 * d * s, line)
        // label
        val bw = 128f * d * s; val bh = 56f * d * s
        val boxTop = boxBottom - bh
        glass(c, cx - bw / 2, boxTop, cx + bw / 2, boxBottom, 12 * d * s, col)
        text(c, "$level dBm", cx, boxTop + 27 * d * s, 19f * s, Color.WHITE, true)
        text(c, line2, cx, boxTop + 47 * d * s, 13f * s, Color.parseColor("#E0F7FA"))
        // teardrop pin
        val pinY = boxTop - 40 * d * s
        path.reset()
        path.addCircle(cx, pinY, 25 * d * s, Path.Direction.CW)
        path.moveTo(cx - 12 * d * s, pinY + 19 * d * s); path.lineTo(cx + 12 * d * s, pinY + 19 * d * s); path.lineTo(cx, boxTop - 2 * d * s); path.close()
        fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 235; c.drawPath(path, fill); fill.alpha = 255
        line.color = Color.WHITE; line.alpha = 120; line.strokeWidth = 1.5f * d * s; c.drawCircle(cx, pinY, 25 * d * s, line)
        wifiGlyph(c, cx, pinY + 9 * d * s, d * s, Color.WHITE)
        // name chip
        var nm = name; if (nm.length > 16) nm = nm.take(15) + "…"
        text(c, nm, cx, pinY - 33 * d * s, 12f * s, Color.WHITE, true)
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, deg: Float, col: Int) {
        c.save(); c.translate(cx, cy); c.rotate(deg)
        path.reset(); path.moveTo(0f, -s); path.lineTo(s * 0.95f, s * 0.85f); path.lineTo(0f, s * 0.38f); path.lineTo(-s * 0.95f, s * 0.85f); path.close()
        for (i in 3 downTo 1) { line.color = col; line.alpha = 30 * (4 - i); line.strokeWidth = i * 4f * d; c.drawPath(path, line) }
        fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 235; c.drawPath(path, fill); fill.alpha = 255
        line.color = Color.WHITE; line.alpha = 200; line.strokeWidth = 1.5f * d; c.drawPath(path, line)
        c.restore()
    }

    /** shorten text with ... so it never runs into the next element */
    private fun fit(s: String, size: Float, bold: Boolean, maxW: Float): String {
        txt.textSize = size * d
        txt.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        if (txt.measureText(s) <= maxW) return s
        var t2 = s
        while (t2.length > 1 && txt.measureText(t2 + "…") > maxW) t2 = t2.dropLast(1)
        return t2 + "…"
    }

    /** bearing + label of the best direction method; without GPS only the compass sweep is possible */
    private fun smooth(bssid: String, b: Double): Double {
        val a = Math.toRadians(b)
        val s = smoothDir.getOrPut(bssid) { doubleArrayOf(sin(a), Math.cos(a)) }
        s[0] += (sin(a) - s[0]) * 0.10; s[1] += (Math.cos(a) - s[1]) * 0.10
        return (Math.toDegrees(Math.atan2(s[0], s[1])) + 360.0) % 360.0
    }

    private fun dirFor(bssid: String, level: Int, me: DoubleArray?): Pair<Double, String>? {
        if (me != null) {
            val lc = ApLocator.locate(bssid, me[0], me[1], level)
            if (lc != null) return Pair(smooth(bssid, lc.bearing), lc.label)
        } else {
            val sw = ApLocator.sweepInfo(bssid)
            val b = sw?.bearing
            val cf = sw?.conf ?: 0f
            if (b != null) return Pair(smooth(bssid, b), "SWEEP " + (cf * 100).toInt() + "%")
        }
        return null
    }

    private fun rel180(a: Double): Float = (((a + 540.0) % 360.0) - 180.0).toFloat()

    override fun onDraw(c: Canvas) {
        try { drawHud(c) } catch (e: Exception) { }      // never let a drawing error close the app
    }

    private fun drawHud(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val live = t.inRange && t.rssi != null
        val r = t.rssi ?: -100
        shownRssi += (r - shownRssi) * 0.35f
        val rs = Math.round(shownRssi)
        val col = if (live) sigColor(rs) else sigColor(-100)
        val me = meProvider()
        val heading = compass.heading
        val cyan = Color.parseColor("#00E5FF")

        // ---------- minimap border, compass, legend ----------
        val mr = Mini.rect(w, d)
        line.color = cyan; line.alpha = 255; line.strokeWidth = 2f * d
        rect.set(mr); c.drawRoundRect(rect, 14 * d, 14 * d, line)
        if (me == null) {
            rect.set(mr); fill.style = Paint.Style.FILL; fill.color = Color.argb(190, 5, 10, 18); c.drawRoundRect(rect, 14 * d, 14 * d, fill)
            text(c, "Waiting for GPS...", mr.centerX(), mr.centerY(), 12f, Color.parseColor("#FFC400"), true)
        }
        // compass
        val ccx = mr.left + 34 * d; val ccy = mr.bottom - 34 * d
        fill.style = Paint.Style.FILL; fill.color = Color.argb(215, 5, 14, 26); c.drawCircle(ccx, ccy, 25 * d, fill)
        line.color = cyan; line.alpha = 220; line.strokeWidth = 1.6f * d; c.drawCircle(ccx, ccy, 25 * d, line)
        if (!heading.isNaN()) {
            c.save(); c.rotate(-heading, ccx, ccy); text(c, "N", ccx, ccy - 12 * d, 11f, Color.WHITE, true); c.restore()
        } else text(c, "N", ccx, ccy - 12 * d, 11f, Color.WHITE, true)
        path.reset(); path.moveTo(ccx, ccy - 8 * d); path.lineTo(ccx + 7 * d, ccy + 10 * d); path.lineTo(ccx, ccy + 5 * d); path.lineTo(ccx - 7 * d, ccy + 10 * d); path.close()
        fill.color = Color.WHITE; c.drawPath(path, fill)
        // legend
        val ll = mr.right + 8 * d; val lr = w - 12 * d
        glass(c, ll, mr.top, lr, mr.bottom, 14 * d, cyan)
        val rows = arrayOf(Triple("Strong", "(-30 to -60 dBm)", sigColor(-50)), Triple("Medium", "(-61 to -75 dBm)", sigColor(-70)), Triple("Weak", "(-76 to -90 dBm)", sigColor(-85)))
        val rh = (mr.height() - 12 * d) / 3
        for ((i, row) in rows.withIndex()) {
            val y = mr.top + 6 * d + rh * i + rh / 2
            fill.style = Paint.Style.FILL; fill.color = row.third
            for (g in 3 downTo 1) { fill.alpha = 25 * (4 - g); c.drawCircle(ll + 20 * d, y, (8 + g * 2) * d, fill) }
            fill.alpha = 255; c.drawCircle(ll + 20 * d, y, 8 * d, fill)
            text(c, row.first, ll + 36 * d, y - 1 * d, 12f, Color.WHITE, true, Paint.Align.LEFT)
            text(c, row.second, ll + 36 * d, y + 13 * d, 8.5f, Color.parseColor("#9FD8E8"), false, Paint.Align.LEFT)
        }

        // ---------- AR pins: the target + every other network whose direction is known ----------
        val pins = ArrayList<Pin>()
        val dirs = HashMap<String, Pair<Double, String>>()
        val tr = t.rssi
        var located = 0
        var total = if (t.inRange && tr != null) 1 else 0
        if (t.inRange && tr != null) {
            val dm = SignalMath.estimateDistanceMeters(tr)
            val dr = dirFor(targetBssid, tr, me)
            var rel: Float? = null
            if (dr != null && !heading.isNaN()) { rel = rel180(dr.first - heading); located++ }
            val mb = t.downMbps
            val l2 = when {
                t.connected && mb != null -> "%.0f Mbps".format(shownMbps)
                t.connected -> "measuring..."
                else -> "≈ %.0f m".format(dm)
            }
            pins.add(Pin(targetBssid, t.targetSsid, tr, dm, rel, true, l2, dr?.second ?: ""))
        }
        for (o in t.all.sortedByDescending { it.level }) {
            if (o.bssid.equals(targetBssid, true)) continue
            total++
            if (heading.isNaN()) continue
            val dr = dirFor(o.bssid, o.level, me) ?: continue
            dirs[o.bssid] = dr
            located++
            if (pins.size < 7) {
                val dm = SignalMath.estimateDistanceMeters(o.level)
                pins.add(Pin(o.bssid, if (o.ssid.isEmpty()) "<hidden>" else o.ssid, o.level, dm, rel180(dr.first - heading), false, "≈ %.0f m".format(dm), dr.second))
            }
        }
        val mbNow = t.downMbps
        if (t.connected && mbNow != null) shownMbps += (mbNow.toFloat() - shownMbps) * 0.35f else if (!t.connected) shownMbps = 0f

        for (p in pins.sortedByDescending { it.dist }) {
            val pc = sigColor(p.level)
            val near = 1f - sqrt((p.dist.coerceIn(1.0, 40.0) / 40.0)).toFloat()
            val s = (0.70f + 0.45f * near) * (if (p.target) 1.1f else 0.85f)
            val gy = h * 0.52f + h * 0.08f * near
            val rel = p.rel
            if (rel == null) {
                // in range, direction not known yet -> floats in the middle
                drawPin(c, w / 2, gy, s, pc, p.level, p.line2, p.ssid, 0f)
            } else if (abs(rel) <= hfov / 2 + 6) {
                val half = Math.tan(Math.toRadians(hfov / 2.0))
                val px = w / 2 + (Math.tan(Math.toRadians(rel.coerceIn(-85f, 85f).toDouble())) / half * (w / 2)).toFloat()
                drawPin(c, px, gy, s, pc, p.level, p.line2, p.ssid, (p.bssid.hashCode() and 7) / 8f)
                if (p.src.isNotEmpty()) text(c, p.src, px, gy + 26 * d * s, 8.5f, pc, true)
            } else {
                // off screen -> edge arrow
                val left = rel < 0
                val ex = if (left) 26 * d else w - 26 * d
                arrow(c, ex, h * 0.5f, 14 * d, if (left) -90f else 90f, pc)
                text(c, fit(p.ssid, 9f, true, 70 * d), ex, h * 0.5f + 36 * d, 9f, Color.WHITE, true)
                text(c, "≈ %.0f m".format(p.dist), ex, h * 0.5f + 48 * d, 9f, pc)
            }
        }

        // ---------- network counter + compass warning ----------
        val chipTxt = "NETWORKS $total · $located LOCATED"
        txt.textSize = 10f * d; txt.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val chipW = txt.measureText(chipTxt) + 20 * d
        glass(c, mr.left, mr.bottom + 8 * d, mr.left + chipW, mr.bottom + 34 * d, 10 * d, cyan)
        text(c, chipTxt, mr.left + 10 * d, mr.bottom + 25 * d, 10f, Color.WHITE, true, Paint.Align.LEFT)
        if (!heading.isNaN() && compass.accuracy <= 1) {
            val cw2 = "CALIBRATE COMPASS · draw a figure 8"
            txt.textSize = 9f * d; txt.typeface = Typeface.DEFAULT
            val ww = txt.measureText(cw2) + 20 * d
            glass(c, mr.left, mr.bottom + 40 * d, mr.left + ww, mr.bottom + 62 * d, 8 * d, sigColor(-70))
            text(c, cw2, mr.left + 10 * d, mr.bottom + 55 * d, 9f, sigColor(-70), true, Paint.Align.LEFT)
        }

        // ---------- NEARBY networks: name, dBm and the direction their signal comes from ----------
        val nearby = t.all.filter { !it.bssid.equals(targetBssid, true) }.sortedByDescending { it.level }.take(5)
        if (nearby.isNotEmpty()) {
            val cw = 104 * d; val x0 = w - 10 * d - cw
            var y = mr.bottom + 56 * d
            text(c, "NEARBY " + nearby.size + " OF " + (t.all.size - (if (t.all.any { it.bssid.equals(targetBssid, true) }) 1 else 0)), w - 12 * d, y - 6 * d, 8.5f, Color.parseColor("#7FDBFF"), true, Paint.Align.RIGHT)
            for (ap in nearby) {
                val ac = sigColor(ap.level)
                glass(c, x0, y, x0 + cw, y + 34 * d, 8 * d, ac)
                val nm2 = if (ap.ssid.isEmpty()) "<hidden>" else ap.ssid
                text(c, fit(nm2, 9.5f, true, cw - 38 * d), x0 + 6 * d, y + 14 * d, 9.5f, Color.WHITE, true, Paint.Align.LEFT)
                text(c, "" + ap.level, x0 + cw - 6 * d, y + 14 * d, 10f, ac, true, Paint.Align.RIGHT)
                val dr = dirs[ap.bssid]
                val dtxt = if (dr == null) "direction: ?" else {
                    val rl = if (heading.isNaN()) 0f else rel180(dr.first - heading)
                    val ar = if (heading.isNaN()) "" else if (rl < -15) "◀ " else if (rl > 15) "▶ " else "▲ "
                    ar + ApLocator.compassName(dr.first) + " " + dr.first.toInt() + "°"
                }
                text(c, dtxt, x0 + 6 * d, y + 28 * d, 8.5f, if (dr == null) Color.parseColor("#9FB8C4") else Color.parseColor("#B2EBF2"), false, Paint.Align.LEFT)
                y += 38 * d
            }
        }

        // ---------- real-time RF sweep of the target (signal strength per compass direction) ----------
        val swTarget = ApLocator.sweepInfo(targetBssid)
        if (t.inRange && swTarget != null && swTarget.coverage > 0f) {
            val pcx = 58 * d; val pcy = h * 0.40f; val rr = 44 * d
            fill.style = Paint.Style.FILL; fill.color = Color.argb(150, 5, 14, 26); c.drawCircle(pcx, pcy, rr + 6 * d, fill)
            line.color = cyan; line.alpha = 120; line.strokeWidth = 1f * d
            c.drawCircle(pcx, pcy, rr, line); c.drawCircle(pcx, pcy, rr / 2, line)
            val hd = if (heading.isNaN()) 0f else heading
            var mn = 999.0; var mx = -999.0
            for (v in swTarget.bins) if (!v.isNaN()) { mn = min(mn, v); mx = max(mx, v) }
            for (k in 0 until ApLocator.BINS) {
                val v = swTarget.bins[k]
                if (v.isNaN()) continue
                val frac = ((v - (mn - 3.0)) / ((mx - mn) + 3.0)).coerceIn(0.12, 1.0).toFloat()
                rect.set(pcx - rr * frac, pcy - rr * frac, pcx + rr * frac, pcy + rr * frac)
                fill.color = sigColor(v.toInt()); fill.alpha = 170
                c.drawArc(rect, k * 30f - hd - 90f, 30f, true, fill)
            }
            fill.alpha = 255
            val sb = swTarget.bearing
            if (sb != null) {
                val ang = Math.toRadians(sb - hd - 90.0)
                line.color = Color.WHITE; line.alpha = 255; line.strokeWidth = 2.5f * d
                c.drawLine(pcx, pcy, pcx + (rr * Math.cos(ang)).toFloat(), pcy + (rr * Math.sin(ang)).toFloat(), line)
            }
            path.reset(); path.moveTo(pcx, pcy - rr - 8 * d); path.lineTo(pcx - 5 * d, pcy - rr - 1 * d); path.lineTo(pcx + 5 * d, pcy - rr - 1 * d); path.close()
            fill.color = cyan; c.drawPath(path, fill)
            text(c, "RF SWEEP " + (swTarget.coverage * 100).toInt() + "%", pcx, pcy + rr + 20 * d, 9f, Color.parseColor("#B2EBF2"), true)
        }

        // ---------- guide arrow / locate hint ----------
        val tp = pins.firstOrNull { it.target }
        val ay = h - 200 * d
        if (tp != null) {
            val rel = tp.rel
            if (rel != null) {
                val close = abs(rel) < 15
                val ac = if (close) sigColor(-50) else sigColor(-70)
                for (i in 0..2) {                                  // dotted trail moving ahead
                    val f = ((phase + i / 3f) % 1f)
                    fill.style = Paint.Style.FILL; fill.color = ac; fill.alpha = (200 * (1 - f)).toInt()
                    c.drawCircle(w / 2, ay - 34 * d - f * 70 * d, 3.5f * d, fill)
                }
                fill.alpha = 255
                arrow(c, w / 2, ay, 30 * d, rel.coerceIn(-80f, 80f), ac)
                val msg = if (tp.dist < 2.5) "YOU ARE AT THE ROUTER" else if (close) "GO STRAIGHT · ≈ %.0f m".format(tp.dist)
                    else (if (rel < 0) "TURN LEFT %d° · ≈ %.0f m" else "TURN RIGHT %d° · ≈ %.0f m").format(abs(rel).toInt(), tp.dist)
                text(c, msg, w / 2, ay + 56 * d, 12f, Color.WHITE, true)
            } else {
                val est = ApLocator.estimate(targetBssid)
                val cov = swTarget?.coverage ?: 0f
                val walkP = if (est == null) 0f else min(1f, (est.spreadM / 4.0).toFloat())
                val prog = max(cov, walkP)
                val pulse = 0.6f + 0.4f * sin(phase * 2 * Math.PI).toFloat()
                glass(c, w / 2 - 150 * d, ay - 20 * d, w / 2 + 150 * d, ay + 46 * d, 14 * d, sigColor(-70))
                val m1 = when {
                    heading.isNaN() -> "No compass on this phone - direction unavailable"
                    else -> "TURN SLOWLY 360° OR WALK 5-10 m"
                }
                text(c, m1, w / 2, ay + 2 * d, 11f, Color.argb((255 * pulse).toInt(), 255, 232, 31), true)
                rect.set(w / 2 - 110 * d, ay + 14 * d, w / 2 + 110 * d, ay + 22 * d)
                fill.style = Paint.Style.FILL; fill.color = Color.argb(70, 255, 255, 255); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
                rect.set(w / 2 - 110 * d, ay + 14 * d, w / 2 - 110 * d + 220 * d * prog, ay + 22 * d)
                fill.color = sigColor(-50); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
                text(c, "sweep " + (cov * 100).toInt() + "% · walk " + String.format("%.0f", est?.spreadM ?: 0.0) + " / 4 m", w / 2, ay + 38 * d, 9f, Color.parseColor("#B2EBF2"))
            }
        }

        // ---------- bottom status bar ----------
        val bl = 12 * d; val br = w - 12 * d; val bb = h - 12 * d; val bt = bb - 84 * d
        glass(c, bl, bt, br, bb, 20 * d, cyan)
        wifiGlyph(c, bl + 40 * d, bt + 50 * d, 2.1f * d, Color.parseColor("#2979FF"))
        val r1 = if (live) "$rs dBm" else "-- dBm"
        val r2 = when {
            t.connected && t.downMbps != null -> "%.0f Mbps".format(shownMbps)
            t.connected -> "measuring"
            else -> "-- Mbps"
        }
        txt.textSize = 15f * d; txt.typeface = Typeface.DEFAULT
        val rw = max(txt.measureText(r1), txt.measureText(r2))
        val barsW = 48 * d
        val barsX = br - 14 * d - rw - 14 * d - barsW
        val nameW = barsX - 12 * d - (bl + 76 * d)
        text(c, fit(t.targetSsid, 18f, true, nameW), bl + 76 * d, bt + 36 * d, 18f, Color.WHITE, true, Paint.Align.LEFT)
        val (stText, stCol) = when {
            t.connected -> Pair("Connected", sigColor(-50))
            t.inRange -> Pair("In range", sigColor(-70))
            else -> Pair("Not in range", sigColor(-85))
        }
        text(c, stText, bl + 76 * d, bt + 62 * d, 14f, stCol, true, Paint.Align.LEFT)
        val nb = if (live) bars(rs) else 0
        for (i in 0 until 4) {
            val bh2 = (10 + i * 9) * d
            rect.set(barsX + i * 13 * d, bb - 22 * d - bh2, barsX + i * 13 * d + 9 * d, bb - 22 * d)
            fill.style = Paint.Style.FILL; fill.color = if (i < nb) col else Color.argb(70, 255, 255, 255)
            c.drawRoundRect(rect, 2 * d, 2 * d, fill)
        }
        text(c, r1, br - 14 * d, bt + 38 * d, 15f, Color.WHITE, false, Paint.Align.RIGHT)
        text(c, r2, br - 14 * d, bt + 62 * d, 15f, Color.parseColor("#B2EBF2"), false, Paint.Align.RIGHT)
        // real-time RF capture line: source, frequency, channel, width, standard, compass heading
        val src = when {
            !t.inRange -> "TARGET NOT IN RANGE"
            t.source == "LIVE" -> "● LIVE"
            else -> "◌ scan " + t.scanAgeSec + "s old"
        }
        val rf = if (t.freq > 0) " · " + t.freq + " MHz CH " + t.channel() + " · " + t.width + " · " + t.standard else ""
        val hdg = if (heading.isNaN()) "" else " · HDG " + heading.toInt() + "°"
        text(c, fit(src + rf + hdg, 9f, false, w - 24 * d), bl + 4 * d, bt - 6 * d, 9f, Color.parseColor("#B2EBF2"), false, Paint.Align.LEFT)
        if (!t.locationOn) text(c, "Turn ON Location (GPS)", w / 2, h * 0.5f, 12f, Color.parseColor("#FFC400"), true)
    }
}
