package com.tonix.wifireader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

class RfAp(val ssid: String, val bssid: String, val level: Int, val freq: Int, val center: Int, val width: Int)

/**
 * Real-time radio capture: every Wi-Fi radio signal the phone hears, drawn by FREQUENCY (channel graph),
 * with its real frequency, channel, width and dBm. Data = what the Wi-Fi chip measured in the last scan.
 */
@Suppress("DEPRECATION")
class SpectrumScreen(ctx: Context) : LinearLayout(ctx) {
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val graph = SpectrumView(ctx)
    private val status: TextView
    private val advice: TextView
    private val listBox: LinearLayout
    private val bandBtn: TextView
    private var running = false
    private var band = -1                 // 0 = 2.4 GHz, 1 = 5 GHz, 2 = 6 GHz, -1 = pick the busiest on first data
    private var lastData = 0L
    private var scans = 0
    private var receiver: BroadcastReceiver? = null
    private var selected: String? = null
    private var aps: List<RfAp> = emptyList()

    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try { wm.startScan() } catch (e: Exception) { }
            handler.postDelayed(this, 3000)
        }
    }
    private val clock = object : Runnable {
        override fun run() { if (!running) return; refreshStatus(); handler.postDelayed(this, 500) }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#05070D"))
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(8, 6, 8, 6); gravity = Gravity.CENTER_VERTICAL }
        status = TextView(ctx).apply { setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f; typeface = Typeface.MONOSPACE }
        top.addView(status, LayoutParams(0, -2, 1f))
        bandBtn = TextView(ctx).apply {
            setTextColor(Color.parseColor("#00E5FF")); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(26, 14, 26, 14)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0D1420")); cornerRadius = 16f; setStroke(3, Color.parseColor("#00E5FF")) }
            setOnClickListener { band = (band + 1) % 3; applyBand(); redraw() }
        }
        top.addView(bandBtn)
        addView(top, LayoutParams(-1, -2))
        addView(graph, LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
        advice = TextView(ctx).apply { setTextColor(Color.parseColor("#FFE81F")); textSize = 11f; setPadding(16, 8, 16, 8) }
        addView(advice, LayoutParams(-1, -2))
        listBox = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(8, 4, 8, 16) }
        addView(ScrollView(ctx).apply { addView(listBox) }, LayoutParams(-1, 0, 1f))
        resume()
    }

    fun resume() {
        if (running) return
        running = true
        receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { capture() } }
        try { context.applicationContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) { }
        capture()
        handler.post(scanLoop); handler.post(clock)
    }

    fun pause() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { receiver?.let { context.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
    }

    fun stop() { pause() }

    private fun bandOf(f: Int) = if (f >= 5925) 2 else if (f >= 4900) 1 else 0

    private fun applyBand() {
        graph.band = band
        bandBtn.text = when (band) { 0 -> "2.4 GHz ▸"; 1 -> "5 GHz ▸"; else -> "6 GHz ▸" }
    }

    /** read what the Wi-Fi chip captured in its last scan */
    private fun capture() {
        val res: List<ScanResult> = try { wm.scanResults } catch (e: Exception) { emptyList() }
        scans++
        lastData = SystemClock.elapsedRealtime()
        aps = res.filter { !it.BSSID.isNullOrEmpty() }.map {
            val w = when (it.channelWidth) { 0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 80; 5 -> 320; else -> 20 }
            RfAp(if (it.SSID.isNullOrEmpty()) "<hidden>" else it.SSID, it.BSSID, it.level, it.frequency, if (it.centerFreq0 > 0) it.centerFreq0 else it.frequency, w)
        }
        if (band < 0) {
            val cnt = IntArray(3); for (a in aps) cnt[bandOf(a.freq)]++
            band = if (cnt[1] > cnt[0] && cnt[1] >= cnt[2]) 1 else if (cnt[2] > cnt[0] && cnt[2] > cnt[1]) 2 else 0
            applyBand()
        }
        redraw()
    }

    private fun redraw() {
        graph.aps = aps.filter { bandOf(it.freq) == band }
        graph.selected = selected
        listBox.removeAllViews()
        for (a in aps.filter { bandOf(it.freq) == band }.sortedByDescending { it.level }) {
            val col = when { a.level >= -60 -> "#2BFF5A"; a.level >= -75 -> "#FFE81F"; else -> "#FF2D2D" }
            val row = TextView(context).apply {
                text = a.ssid + "\n" + a.level + " dBm · " + a.freq + " MHz · CH " + ApLocator.channelOf(a.freq) + " · " + a.width + " MHz wide · ≈ " + ApLocator.fmtDist(SignalMath.estimateDistanceMeters(a.level))
                setTextColor(Color.parseColor("#E0FFFA")); textSize = 12f; setPadding(20, 14, 20, 14)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D1420")); cornerRadius = 14f
                    setStroke(if (a.bssid == selected) 6 else 3, Color.parseColor(col))
                }
                layoutParams = LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
                setOnClickListener { selected = if (selected == a.bssid) null else a.bssid; redraw() }
            }
            listBox.addView(row)
        }
        // least crowded 2.4 GHz channel (only 1, 6, 11 do not overlap each other)
        if (band == 0) {
            var bestCh = 1; var bestScore = Double.MAX_VALUE; var bestN = 0
            for (ch in intArrayOf(1, 6, 11)) {
                var score = 0.0; var n = 0
                for (a in aps) if (bandOf(a.freq) == 0 && abs(ApLocator.channelOf(a.center) - ch) <= 4) { score += 10.0.pow(a.level / 10.0); n++ }
                if (score < bestScore) { bestScore = score; bestCh = ch; bestN = n }
            }
            advice.text = "Least crowded 2.4 GHz channel right now: CH $bestCh ($bestN networks overlap it)"
        } else advice.text = "Tap a network below to highlight its radio shape on the graph"
        refreshStatus()
    }

    private fun refreshStatus() {
        val age = if (lastData == 0L) 0 else ((SystemClock.elapsedRealtime() - lastData) / 1000).toInt()
        val n24 = aps.count { bandOf(it.freq) == 0 }; val n5 = aps.count { bandOf(it.freq) == 1 }; val n6 = aps.count { bandOf(it.freq) == 2 }
        status.text = "● LIVE RF CAPTURE\n${aps.size} signals · 2.4G: $n24 · 5G: $n5 · 6G: $n6\nlast radio scan ${age}s ago · scans: $scans"
    }
}

/** frequency (x) vs signal strength (y) graph, every network drawn with its real centre frequency and channel width */
class SpectrumView(ctx: Context) : View(ctx) {
    var band = 0
    var aps: List<RfAp> = emptyList()
    var selected: String? = null
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val shown = HashMap<String, Float>()
    private var phase = 0f

    private val loop = object : Runnable {
        override fun run() { phase = (System.currentTimeMillis() % 2000L) / 2000f; invalidate(); postDelayed(this, 33) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(loop) }
    override fun onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow() }

    private fun range(): Pair<Int, Int> = when (band) { 0 -> Pair(2397, 2487); 1 -> Pair(5170, 5835); else -> Pair(5945, 7125) }

    private fun ticks(): List<Pair<Int, String>> = when (band) {
        0 -> (1..13).map { Pair(2412 + 5 * (it - 1), it.toString()) }
        1 -> listOf(36, 48, 64, 100, 116, 132, 149, 165).map { Pair(5000 + 5 * it, it.toString()) }
        else -> listOf(1, 33, 65, 97, 129, 161, 193, 225).map { Pair(5950 + 5 * it, it.toString()) }
    }

    private fun color(bssid: String): Int {
        val hue = ((bssid.hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 1f))
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, a: Paint.Align = Paint.Align.CENTER) {
        txt.textSize = size * d; txt.color = color; txt.textAlign = a
        txt.typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        c.drawText(s, x, y, txt)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pl = 34 * d; val pr = w - 10 * d; val pt = 14 * d; val pb = h - 26 * d
        val (f0, f1) = range()
        fun xOf(f: Float) = pl + (f - f0) / (f1 - f0) * (pr - pl)
        fun yOf(dbm: Float) = pb - (pb - pt) * ((dbm.coerceIn(-100f, -30f) + 100f) / 70f)

        fill.style = Paint.Style.FILL; fill.color = Color.parseColor("#0A111C")
        c.drawRect(0f, 0f, w, h, fill)
        // grid
        for (g in -100..-30 step 10) {
            val y = yOf(g.toFloat())
            line.color = Color.WHITE; line.alpha = if (g == -100) 90 else 28; line.strokeWidth = 1f * d
            c.drawLine(pl, y, pr, y, line)
            text(c, "$g", pl - 4 * d, y + 3 * d, 8f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)
        }
        for ((f, lab) in ticks()) {
            val x = xOf(f.toFloat())
            if (x < pl || x > pr) continue
            line.color = Color.WHITE; line.alpha = 28; c.drawLine(x, pt, x, pb, line)
            text(c, lab, x, pb + 14 * d, 8.5f, Color.parseColor("#B2EBF2"))
        }
        text(c, "CHANNEL", pr, h - 3 * d, 8f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)
        text(c, "dBm", pl - 4 * d, pt - 3 * d, 8f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)

        // networks: strongest drawn last
        val labels = ArrayList<RectF>()
        val sorted = aps.sortedBy { it.level }
        for (a in sorted) {
            val cur = shown[a.bssid]
            val lv = if (cur == null) a.level.toFloat() else cur + (a.level - cur) * 0.18f      // smooth glide to the new real reading
            shown[a.bssid] = lv
            val col = color(a.bssid)
            val sel = a.bssid == selected
            val xl = xOf(a.center - a.width / 2f); val xr = xOf(a.center + a.width / 2f); val xc = xOf(a.center.toFloat())
            val yt = yOf(lv); val yb = pb
            val ink = (xr - xl) * 0.07f
            path.reset(); path.moveTo(xl, yb); path.lineTo(xl + ink, yt); path.lineTo(xr - ink, yt); path.lineTo(xr, yb); path.close()
            fill.color = col; fill.alpha = if (sel) (110 + 50 * sin(phase * 2 * Math.PI)).toInt() else 60
            c.drawPath(path, fill)
            line.color = col; line.alpha = 240; line.strokeWidth = (if (sel) 3.2f else 1.8f) * d
            c.drawPath(path, line)
            // name above the peak, moved up when it would cover another name
            var ly = yt - 5 * d
            txt.textSize = 8.5f * d
            val tw = txt.measureText(a.ssid.take(14)) + 4 * d
            var tries = 0
            while (tries < 5 && labels.any { it.left < xc + tw / 2 && it.right > xc - tw / 2 && abs(it.centerY() - ly) < 10 * d }) { ly -= 10 * d; tries++ }
            labels.add(RectF(xc - tw / 2, ly - 9 * d, xc + tw / 2, ly))
            text(c, a.ssid.take(14), xc, ly, 8.5f, col, true)
        }
        if (aps.isEmpty()) text(c, "listening... (turn ON Location + Wi-Fi)", w / 2, h / 2, 11f, Color.parseColor("#FFC400"), true)
        // live dot
        fill.color = Color.parseColor("#2BFF5A"); fill.alpha = (120 + 135 * (0.5 + 0.5 * sin(phase * 2 * Math.PI))).toInt()
        c.drawCircle(pr - 6 * d, pt + 2 * d, 4 * d, fill); fill.alpha = 255
    }
}
