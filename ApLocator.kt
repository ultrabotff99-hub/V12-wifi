package com.tonix.wifireader

import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the DIRECTION of Wi-Fi routers using only real measurements. A phone has ONE antenna, so a single
 * reading can never give a direction - three real methods are combined, the most confident one wins:
 *
 *  1. SET   - you point the camera at the router and press "SET ROUTER HERE" (always right when you are right).
 *  2. WALK  - while you walk, (GPS position, dBm) samples are stored; a plane is fitted to them and its slope
 *             points to where the signal gets stronger. Router = strongest sample + signal distance along it.
 *  3. SWEEP - turn slowly 360 degrees: your body shadows the signal from behind, so dBm per compass heading
 *             (12 bins of 30 degrees) peaks towards the router. One sweep gives the direction of ALL networks.
 */
object ApLocator {
    const val BINS = 12

    class Estimate(val lat: Double, val lon: Double, val spreadM: Double, val samples: Int, val conf: Float) {
        val locked: Boolean get() = samples >= 4 && spreadM >= 4.0 && conf >= 0.25f
    }

    class Located(val lat: Double, val lon: Double, val bearing: Double, val source: String, val conf: Float) {
        val label: String get() = if (source == "SET") "SET" else "$source ${(conf * 100).toInt()}%"
    }

    class SweepInfo(val bins: DoubleArray, val coverage: Float, val bearing: Double?, val conf: Float)

    private class Track {
        val lat = ArrayList<Double>(); val lon = ArrayList<Double>()
        val lvl = ArrayList<Int>(); val t = ArrayList<Long>()
        var lastLat = 0.0; var lastLon = 0.0; var has = false
    }

    private class SweepData {
        val ema = DoubleArray(BINS) { Double.NaN }
        val ts = LongArray(BINS)
    }

    private val tracks = HashMap<String, Track>()
    private val sweeps = HashMap<String, SweepData>()
    private val manual = HashMap<String, DoubleArray>()
    private var originLat = 0.0
    private var originLon = 0.0
    private var originSet = false

    // ---------------- small geo helpers ----------------
    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val n = (lat2 - lat1) * 110540.0
        val e = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return sqrt(n * n + e * e)
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val n = (lat2 - lat1) * 110540.0
        val e = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
    }

    /** point `distM` metres away from (lat, lon) in compass direction `bearingDeg` */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        return Pair(lat + distM * cos(b) / 110540.0, lon + distM * sin(b) / (111320.0 * cos(Math.toRadians(lat))))
    }

    fun compassName(b: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((((b % 360.0) + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8]

    fun channelOf(f: Int): Int = when { f <= 0 -> 0; f < 3000 -> (f - 2407) / 5; f >= 5925 -> (f - 5950) / 5; else -> (f - 5000) / 5 }

    fun fmtDist(m: Double): String = when {
        m >= 1000 -> String.format("%.2f km", m / 1000)
        m >= 10 -> String.format("%.0f m", m)
        else -> String.format("%.1f m", m)
    }

    // ---------------- 1. manual anchor ----------------
    fun setManual(bssid: String, lat: Double, lon: Double) { manual[bssid] = doubleArrayOf(lat, lon) }
    fun clearManual(bssid: String) { manual.remove(bssid) }
    fun hasManual(bssid: String) = manual.containsKey(bssid)

    // ---------------- 2. walking samples ----------------
    fun add(bssid: String, level: Int, lat: Double, lon: Double) {
        val t = tracks.getOrPut(bssid) { Track() }
        if (t.has && distM(t.lastLat, t.lastLon, lat, lon) < 1.5) return
        t.lat.add(lat); t.lon.add(lon); t.lvl.add(level); t.t.add(System.currentTimeMillis())
        if (t.lat.size > 60) { t.lat.removeAt(0); t.lon.removeAt(0); t.lvl.removeAt(0); t.t.removeAt(0) }
        t.lastLat = lat; t.lastLon = lon; t.has = true
    }

    fun estimate(bssid: String): Estimate? {
        val t = tracks[bssid] ?: return null
        val n = t.lat.size
        if (n < 4) return null
        val now = System.currentTimeMillis()
        val lat0 = t.lat[0]; val lon0 = t.lon[0]
        val kE = 111320.0 * cos(Math.toRadians(lat0)); val kN = 110540.0
        val e = DoubleArray(n); val q = DoubleArray(n); val l = DoubleArray(n); val w = DoubleArray(n)
        var sw = 0.0; var me = 0.0; var mq = 0.0; var ml = 0.0
        var minE = 1e12; var maxE = -1e12; var minQ = 1e12; var maxQ = -1e12
        for (i in 0 until n) {
            e[i] = (t.lon[i] - lon0) * kE; q[i] = (t.lat[i] - lat0) * kN; l[i] = t.lvl[i].toDouble()
            w[i] = exp(-((now - t.t[i]) / 1000.0) / 120.0)              // newer samples count more
            sw += w[i]; me += w[i] * e[i]; mq += w[i] * q[i]; ml += w[i] * l[i]
            minE = min(minE, e[i]); maxE = max(maxE, e[i]); minQ = min(minQ, q[i]); maxQ = max(maxQ, q[i])
        }
        me /= sw; mq /= sw; ml /= sw
        val spread = hypot(maxE - minE, maxQ - minQ)
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxl = 0.0; var syl = 0.0; var sll = 0.0
        for (i in 0 until n) {
            val de = e[i] - me; val dq = q[i] - mq; val dl = l[i] - ml
            sxx += w[i] * de * de; sxy += w[i] * de * dq; syy += w[i] * dq * dq
            sxl += w[i] * de * dl; syl += w[i] * dq * dl; sll += w[i] * dl * dl
        }
        val gE: Double; val gN: Double
        val det = sxx * syy - sxy * sxy
        if (det > 1e-4 * (sxx + syy) * (sxx + syy) && det > 1e-9) {
            gE = (sxl * syy - syl * sxy) / det
            gN = (syl * sxx - sxl * sxy) / det
        } else {                                                          // walked in a straight line: slope along it
            val ang = 0.5 * atan2(2 * sxy, sxx - syy)
            val ux = cos(ang); val uy = sin(ang)
            var sp = 0.0; var spp = 0.0
            for (i in 0 until n) { val p = (e[i] - me) * ux + (q[i] - mq) * uy; sp += w[i] * p * (l[i] - ml); spp += w[i] * p * p }
            if (spp < 1e-6) return null
            gE = sp / spp * ux; gN = sp / spp * uy
        }
        val gm = hypot(gE, gN)                                            // dB per metre towards the stronger side
        var ssr = 0.0
        for (i in 0 until n) { val r = (l[i] - ml) - (gE * (e[i] - me) + gN * (q[i] - mq)); ssr += w[i] * r * r }
        val r2 = if (sll > 1e-9) (1.0 - ssr / sll).coerceIn(0.0, 1.0) else 0.0
        var bi = 0
        for (i in 1 until n) if (l[i] > l[bi]) bi = i
        val dir = atan2(gE, gN)
        val d = SignalMath.estimateDistanceMeters(l[bi].toInt()).coerceIn(1.0, 80.0)
        val pe = e[bi] + d * sin(dir); val pq = q[bi] + d * cos(dir)
        val conf = if (gm < 0.04) 0f else (r2 * min(1.0, spread / 8.0) * min(1.0, gm / 0.25)).toFloat()
        return Estimate(lat0 + pq / kN, lon0 + pe / kE, spread, n, conf)
    }

    // ---------------- 3. rotation sweep ----------------
    /** a sweep is only valid for the spot where it was made: moving > 8 m starts a new one */
    fun checkSweepOrigin(lat: Double, lon: Double) {
        if (!originSet) { originLat = lat; originLon = lon; originSet = true; return }
        if (distM(originLat, originLon, lat, lon) > 8.0) { sweeps.clear(); originLat = lat; originLon = lon }
    }

    fun addSweep(bssid: String, level: Int, heading: Float, tMs: Long) {
        if (heading.isNaN()) return
        val s = sweeps.getOrPut(bssid) { SweepData() }
        val bin = ((((heading % 360f) + 360f) % 360f) / (360f / BINS)).toInt().coerceIn(0, BINS - 1)
        val now = SystemClock.elapsedRealtime()
        s.ema[bin] = if (s.ema[bin].isNaN() || now - s.ts[bin] > 180000L) level.toDouble() else s.ema[bin] * 0.5 + level * 0.5
        s.ts[bin] = now
    }

    fun sweepInfo(bssid: String): SweepInfo? {
        val s = sweeps[bssid] ?: return null
        val now = SystemClock.elapsedRealtime()
        val bins = DoubleArray(BINS) { i -> if (s.ema[i].isNaN() || now - s.ts[i] > 180000L) Double.NaN else s.ema[i] }
        var cnt = 0; var mx = -999.0; var sum = 0.0
        for (v in bins) if (!v.isNaN()) { cnt++; sum += v; if (v > mx) mx = v }
        if (cnt == 0) return SweepInfo(bins, 0f, null, 0f)
        val cov = cnt.toFloat() / BINS
        val contrast = mx - sum / cnt
        var brg: Double? = null; var conf = 0f
        if (cnt >= 9 && contrast >= 3.0) {
            var sx = 0.0; var sy = 0.0
            for (i in 0 until BINS) {
                val v = bins[i]; if (v.isNaN()) continue
                val wg = 1.0 - (mx - v) / 4.0; if (wg <= 0.0) continue         // only bins within 4 dB of the peak
                val a = Math.toRadians((i + 0.5) * (360.0 / BINS))
                sx += wg * sin(a); sy += wg * cos(a)
            }
            brg = (Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0
            conf = (min(1.0, contrast / 8.0) * cov).toFloat()
        }
        return SweepInfo(bins, cov, brg, conf)
    }

    // ---------------- result for the AR screen / map ----------------
    fun locate(bssid: String, meLat: Double, meLon: Double, level: Int): Located? {
        val man = manual[bssid]
        if (man != null) return Located(man[0], man[1], bearing(meLat, meLon, man[0], man[1]), "SET", 1f)
        val w = estimate(bssid)?.takeIf { it.locked }
        val sw = sweepInfo(bssid)
        val sb = sw?.bearing
        val swConf = sw?.conf ?: 0f
        if (w != null && (sb == null || w.conf >= swConf))
            return Located(w.lat, w.lon, bearing(meLat, meLon, w.lat, w.lon), "WALK", w.conf)
        if (sb != null) {
            val d = SignalMath.estimateDistanceMeters(level).coerceIn(1.0, 80.0)
            val p = offset(meLat, meLon, sb, d)
            return Located(p.first, p.second, sb, "SWEEP", swConf)
        }
        return null
    }

    fun clear() { tracks.clear(); sweeps.clear(); manual.clear(); originSet = false }
}
