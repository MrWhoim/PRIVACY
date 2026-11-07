package com.privacy.app.ui.face

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Everything that changes the face's look. The animator moves these; the renderer reads them. */
class FaceParams {
    var mouthOpen = 0f   // 0 closed … 1 wide open
    var mouthWide = 0f   // -1 round "oo" … +1 wide "ee"
    var smile = 0f       // -1 frown … +1 big smile
    var eyeOpen = 1f     // 0 closed … 1 open
    var lookX = 0f       // -1 left … +1 right
    var lookY = 0f       // -1 up … +1 down
    var brow = 0f        // -1 low … +1 raised
    var tilt = 0f        // brows: + angry (inner ends down), - worried (inner ends up)
    var headX = 0f       // head offset in pixels
    var headY = 0f
    var roll = 0f        // head tilt in radians
    var eyeArc = 0f      // 0 open eyes … 1 closed happy arcs ^ ^
    var wink = 0f        // -1 left eye closed, +1 right eye closed, 0 none
    internal var cr = 1f // cached cos/sin of -roll (set by the renderer)
    internal var sr = 0f
}

enum class Gesture { NONE, WAVE, POINT, THUMBS_UP, PRESENT, HEART, THINK, TYPE, CLAP, CHEER, SHRUG }

enum class FaceMood { NEUTRAL, HAPPY, SAD, SURPRISED, ANGRY, SLEEPY, WINK, LOVE, CONFUSED }
enum class FaceMode { IDLE, LISTENING, THINKING, SPEAKING }

fun moodOf(text: String): FaceMood {
    val t = text.lowercase()
    return when {
        Regex("annoy|frustrat|angry|furious").containsMatchIn(t) -> FaceMood.ANGRY
        Regex("\\blove\\b|❤|adore").containsMatchIn(t) -> FaceMood.LOVE
        Regex("good night|sleep|tired|rest well|late night").containsMatchIn(t) -> FaceMood.SLEEPY
        Regex("just kidding|😉|between us").containsMatchIn(t) -> FaceMood.WINK
        Regex("not sure|confus|unclear|hmm|don't know").containsMatchIn(t) -> FaceMood.CONFUSED
        Regex("sorry|unfortunately|couldn't|can't|cannot|failed|error|⚠").containsMatchIn(t) -> FaceMood.SAD
        Regex("great|awesome|done|added|saved|congrat|glad|happy|nice|perfect|welcome|!").containsMatchIn(t) -> FaceMood.HAPPY
        t.trimEnd().endsWith("?") -> FaceMood.SURPRISED
        else -> FaceMood.NEUTRAL
    }
}


/**
 * The pixel assistants: a 40×52 dot-matrix portrait (head, hair, face, shoulders, torso) in a male and a female version,
 * plus hands that make gestures. Every cell is decided by a small formula, so faces move smoothly and make any expression.
 */
object PixelFace {
    const val W = 40
    const val H = 52

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private fun bay(x: Int, y: Int): Float = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f

    fun hsh(x: Int, y: Int, s: Int): Float {
        var n = x * 374761393 + y * 668265263 + s * 1013904223
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0xFFFF) / 65535f
    }

    private fun clamp(v: Float, a: Float = 0f, b: Float = 1f): Float = if (v < a) a else if (v > b) b else v

    /** Is this cell part of the person (head + body, no hands)? */
    fun lit(ix: Int, iy: Int, s: FaceParams, female: Boolean): Boolean {
        val cx = W / 2f
        val cy = 17f
        val dx = ix + 0.5f - cx - s.headX
        val dy = iy + 0.5f - cy - s.headY
        val x = dx * s.cr - dy * s.sr
        val y = dx * s.sr + dy * s.cr
        val ax = abs(x)
        var v = false

        // ---- neck, shoulders, torso
        if (y > 11f) {
            if (ax >= 3.6f && ax <= 4.6f && y < 15.5f) v = true
            val shEdge = 15.2f + max(0f, ax - 4f) * 0.55f
            val lim = 14.4f - max(0f, 2.6f - (y - shEdge)) * 1.1f
            if (ax >= 3.6f && ax <= lim && y >= shEdge - 0.55f && y <= shEdge + 0.55f) v = true
            if (y > shEdge + 0.5f && ax >= 3f && ax <= lim) {
                val depth = y - shEdge
                if (bay(ix, iy) < clamp(0.5f - depth * 0.02f, 0.16f, 0.5f)) v = true
                if (ax > 4.6f && ax < 5.5f && y > shEdge) v = true
                if (lim - ax < 1.0f) v = true
            }
        }

        // ---- hair + head outline + cheek shading
        val hx = x / 10.9f
        val hy = (y + 0.8f) / 13.6f
        val he = hx * hx + hy * hy
        val rx = 9.3f
        val ry = 12.4f
        val e = (x / rx) * (x / rx) + (y / ry) * (y / ry)
        val inHair: Boolean
        if (!female) {
            val hairline = -8.4f + ax * 0.25f + 0.7f * sin(x * 0.45f)
            inHair = he <= 1f && (y < hairline || (ax > 8.2f && y < 3f))
        } else {
            val hairline = -7.0f + x * 0.28f + 0.6f * sin(x * 0.5f)
            inHair = he <= 1f && (y < hairline || (ax > 8.0f && y < 9f))
        }
        var longHair = false
        var outline = false
        if (female && e > 1f && y > -4f && y < 19f) {
            var outer = minOf(13.2f, 10.9f + (y + 4f) * 0.45f)
            if (y > 12f) outer = 13.2f - (y - 12f) * 1.05f
            outer += 0.5f * sin(y * 0.7f + (if (x > 0f) 1.6f else 0f))
            if (ax <= outer && !(y > 11f && ax < 5.6f)) {
                longHair = true
                outline = ax > outer - 1.1f || y > 16.5f
            }
        }
        if (inHair || longHair) {
            v = true
            val k = floor(x * 0.8f + (y + 20f) * 0.28f).toInt()
            if (Math.floorMod(k, 3) == 0 && !outline && y > -10.5f && (he < 0.82f || (longHair && e > 1.2f)) && hsh(ix, iy, 5) < 0.7f) v = false
        } else if (e <= 1f) {
            val edgeT = 1f - 1.35f / ry
            if (e > edgeT * edgeT) v = true
            val sh = clamp((x * 0.45f + y * 0.35f - 3f) / 9f)
            if (sh > 0f && bay(ix, iy) < sh * 0.55f && e > 0.5f) v = true
        }

        // ---- ears (the female's are hidden by her hair)
        if (!female) {
            for (sgn in intArrayOf(-1, 1)) {
                val ex = (x - sgn * 9.9f) / 1.4f
                val ey = (y - 0.6f) / 2.4f
                if (ex * ex + ey * ey <= 1f) v = true
            }
        }

        // ---- eyes and eyebrows
        for (sgn in intArrayOf(-1, 1)) {
            val bx = x - sgn * 3.9f
            val by = y + 0.6f
            val o = if (s.wink != 0f && sgn.toFloat() == s.wink) 0f else s.eyeOpen
            if (s.eyeArc > 0.5f && o > 0.2f) {
                if (abs(bx) <= 2.5f) {
                    val q = bx / 2.5f
                    val ya = -1.5f * (1f - q * q)
                    if (abs(by - ya) < 0.62f) v = true
                }
            } else if (o > 0.2f) {
                val rxx = if (female) 1.9f else 2.15f
                val ryy = if (female) 1.9f else 2.1f
                val a = (bx - s.lookX * 0.8f) / rxx
                val b = (by - s.lookY * 0.5f) / max(0.55f, ryy * o)
                if (a * a + b * b <= 1f) v = true
            } else if (abs(by) < 0.55f && abs(bx) <= 2.4f) v = true
            if (female && s.eyeArc <= 0.5f && o > 0.2f) {
                val ox = bx * sgn // toward the temple when positive
                if (ox >= 1.6f && ox <= 3.0f && by >= -2.6f && by <= -1.5f && abs((ox - 1.6f) * 0.9f - (-by - 1.5f)) < 0.7f) v = true
            }
            var yb = -5.3f - s.brow * 1.6f + (-bx * sgn) * s.tilt * 0.35f + 0.6f
            if (female) {
                val q = bx / 2.5f
                yb -= 0.4f * (1f - q * q)
            }
            if (abs(bx) <= 2.5f && abs(by - yb) < (if (female) 0.45f else 0.55f)) v = true
        }

        // ---- nose
        if (!female) {
            if (x >= 0f && x <= 1.2f && y >= 1.2f && y <= 4.4f && abs(x - 0.55f) < 0.62f) v = true
            if (y >= 3.8f && y <= 4.9f && x >= -1.9f && x <= 1.9f && abs(y - 4.35f) < 0.55f && ax > 0.2f) v = true
        } else {
            if (y >= 3.9f && y <= 4.8f && x >= -1.2f && x <= 1.2f && abs(y - 4.35f) < 0.5f && ax > 0.3f) v = true
        }

        // ---- mouth: lips, teeth, tongue
        val my = y - 7f
        val wide = max(1.6f, 2.8f + 2f * s.mouthWide) + (if (female) 0.2f else 0f)
        val tt = ax / max(wide, 0.1f)
        if (tt <= 1.05f) {
            val lipy = my + s.smile * 1.5f * tt * tt
            val openH = s.mouthOpen * 3.6f
            if (openH < 0.5f) {
                if (abs(lipy) < 0.58f) v = true
                if (female && lipy > 0.55f && lipy < 1.35f && tt < 0.55f) v = true
            } else {
                val bot = openH * (1f - tt * tt)
                if (abs(lipy) < 0.6f) v = true
                else if (lipy > 0.4f && lipy < bot - 0.2f) {
                    v = false
                    if (lipy < 1.5f && tt < 0.8f && s.mouthOpen > 0.45f) v = true
                    else if (lipy > bot - 1.8f && bay(ix, iy) < 0.45f) v = true
                } else if (abs(lipy - bot) < 0.6f && lipy > 0f) v = true
            }
        }
        return v
    }

    /** Faint drifting pixels around the head – the "vibes". Returns brightness 0..0.33 (0 = nothing). */
    fun aura(ix: Int, iy: Int, t: Float): Float {
        val d = hypot(ix + 0.5f - W / 2f, (iy + 0.5f - 17f) / 1.1f)
        var out = 0f
        if (d > 15f) {
            val p = exp(-(d - 15f) / 5f) * 0.5f
            if (hsh(ix, iy, (t * 5f).toInt()) < p * 0.18f) out = 0.33f
        }
        if (hsh(ix, iy, 77 + (t * 3f).toInt() % 7) > 0.992f) out = max(out, 0.2f)
        return out
    }

    // ------------------------------------------------------------------ hands & gestures
    private fun segd(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l = dx * dx + dy * dy
        val t = if (l == 0f) 0f else clamp(((px - ax) * dx + (py - ay) * dy) / l)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // Hands are drawn palm-forward with the thumb on the NEGATIVE x side; the renderer mirrors them so the thumb always faces the body.
    private fun openHand(x: Float, y: Float): Boolean {
        if (abs(x) <= 3.3f && y >= -5.2f && y <= 1.0f) return true
        val fx = floatArrayOf(-2.4f, -0.8f, 0.8f, 2.4f)
        val top = floatArrayOf(-10.6f, -11.8f, -11.4f, -9.4f)
        for (i in 0 until 4) if (abs(x - fx[i]) <= 0.5f && y >= top[i] && y <= -5.2f) return true
        return segd(x, y, -3.2f, -2f, -6.2f, -5.6f) <= 0.8f
    }

    private fun pointHand(x: Float, y: Float): Boolean {
        if (abs(x) <= 3.3f && y >= -4.2f && y <= 1.0f) return true
        if (abs(x + 1.4f) <= 0.55f && y >= -11.5f && y <= -4.2f) return true
        if (x >= -0.4f && x <= 3.3f && y >= -5.6f && y <= -4.2f) return true
        return segd(x, y, -3.2f, -1f, -5.4f, -3.6f) <= 0.8f
    }

    private fun thumbHand(x: Float, y: Float): Boolean {
        if (abs(x) <= 3.2f && y >= -4.2f && y <= 2f) {
            if (x > -1f && (abs(y + 1.5f) < 0.4f || abs(y - 0.3f) < 0.4f)) return false
            return true
        }
        return abs(x + 1.6f) <= 0.95f && y >= -10f && y <= -4.2f
    }

    private fun chinFist(x: Float, y: Float): Boolean {
        if (abs(x) <= 2.6f && y >= -2.5f && y <= 2.5f) return !(x > -1f && abs(y) < 0.4f)
        return abs(x - 1.4f) <= 0.55f && y >= -8.5f && y <= -2.5f
    }

    private fun heartF(x: Float, y: Float): Float {
        val a = x * x + y * y - 1f
        return a * a * a - x * x * y * y * y
    }

    private val QMARK = arrayOf("01110", "10001", "00001", "00010", "00100", "00000", "00100")
    private val BULB = arrayOf("0011100", "0100010", "1000001", "1000001", "0100010", "0011100", "0011100", "0001000")
    private val CARD = arrayOf("11111111111", "10000000001", "10111101101", "10000000001", "10111011101", "10000000001", "11111111111")

    private fun glyph(g: Array<String>, ox: Int, oy: Int, ix: Int, iy: Int): Boolean {
        val x = ix - ox
        val y = iy - oy
        return y >= 0 && y < g.size && x >= 0 && x < g[0].length && g[y][x] == '1'
    }

    /** One hand shape placed at a wrist position, rotated, optionally mirrored (the female's hand is on the other side). */
    private inline fun placed(wx: Float, wy: Float, ang: Float, mirror: Boolean, sc: Float, ix: Int, iy: Int, shape: (Float, Float) -> Boolean): Boolean {
        val x = (ix + 0.5f - wx) / sc
        val y = (iy + 0.5f - wy) / sc
        val c = cos(-ang)
        val s = sin(-ang)
        var lx = x * c - y * s
        val ly = x * s + y * c
        if (mirror) lx = -lx
        return shape(lx, ly)
    }

    /** Draws the whole figure. [Scratch.out] = bright cells, [Scratch.dim] = faint cells (the face behind a hand is dimmed, not erased). */
    class Scratch {
        val out = BooleanArray(W * H)
        val dim = BooleanArray(W * H)
        val face = BooleanArray(W * H)
        val hand = BooleanArray(W * H)
        val extra = BooleanArray(W * H)
    }

    fun render(sc: Scratch, s: FaceParams, female: Boolean, gesture: Gesture, amount: Float, t: Float) {
        s.cr = cos(-s.roll); s.sr = sin(-s.roll)
        for (iy in 0 until H) for (ix in 0 until W) sc.face[iy * W + ix] = lit(ix, iy, s, female)
        java.util.Arrays.fill(sc.dim, false)
        if (gesture == Gesture.NONE || amount < 0.03f) {
            System.arraycopy(sc.face, 0, sc.out, 0, W * H)
            return
        }
        val side = if (female) 1f else -1f
        val mirror = !female
        val lift = (1f - amount) * 14f
        val showExtra = amount > 0.6f
        val wx = if (female) 32.5f else 7.5f
        java.util.Arrays.fill(sc.hand, false)
        java.util.Arrays.fill(sc.extra, false)
        for (iy in 0 until H) for (ix in 0 until W) {
            var h = false
            var e = false
            when (gesture) {
                Gesture.WAVE -> {
                    val ang = Math.toRadians((side * 12f + 22f * sin(t * 7f)).toDouble()).toFloat()
                    h = placed(wx, 39f + lift, ang, mirror, 1.3f, ix, iy, { x, y -> openHand(x, y) })
                }
                Gesture.POINT -> {
                    h = placed(wx, 41f + lift, Math.toRadians((side * 8f).toDouble()).toFloat(), mirror, 1.3f, ix, iy, { x, y -> pointHand(x, y) })
                    if (showExtra) e = glyph(BULB, (if (female) wx - 9f else wx + 3f).toInt(), (23f + lift).toInt(), ix, iy)
                }
                Gesture.THUMBS_UP -> h = placed(wx, 41f + lift, Math.toRadians((side * 6f).toDouble()).toFloat(), mirror, 1.3f, ix, iy, { x, y -> thumbHand(x, y) })
                Gesture.PRESENT -> {
                    h = placed(if (female) 35f else 5f, 46f + lift, Math.toRadians(if (female) 84.0 else -84.0).toFloat(), mirror, 1.15f, ix, iy, { x, y -> openHand(x, y) })
                    if (showExtra) e = glyph(CARD, if (female) 20 else 8, (33f + lift).toInt(), ix, iy)
                }
                Gesture.THINK -> {
                    h = placed(W / 2f + (if (female) 5.5f else -5.5f), 30.5f + lift, 0f, mirror, 1.3f, ix, iy, { x, y -> chinFist(x, y) })
                    if (showExtra) e = glyph(QMARK, if (female) 2 else 33, 6, ix, iy)
                }
                Gesture.HEART -> {
                    val k = 1f + 0.06f * sin(t * 6f)
                    val hx = (ix + 0.5f - W / 2f) / (7.5f * k)
                    val hy = -(iy + 0.5f - (40f + lift)) / (6.5f * k) + 0.25f
                    val f = heartF(hx, hy)
                    h = f <= 0f && (f > -0.22f || bay(ix, iy) < 0.55f)
                }
                Gesture.TYPE -> {
                    for (k in 0..1) {
                        val wig = 1.0f * sin(t * 14f + k * 2f)
                        val lx = ix + 0.5f - (W / 2f + (if (k == 0) -5.5f else 5.5f))
                        val ly = iy + 0.5f - (46f + wig + lift)
                        if (abs(lx) <= 2.4f && ly >= -1.6f && ly <= 1.6f) h = true
                    }
                    if (iy == 48 && ix in 2..37) h = true
                }
                Gesture.CLAP -> {
                    val d = 3.4f + 2.2f * abs(sin(t * 10f))
                    h = placed(W / 2f - d, 42f + lift, -0.07f, true, 1.15f, ix, iy, { x, y -> openHand(x, y) }) ||
                        placed(W / 2f + d, 42f + lift, 0.07f, false, 1.15f, ix, iy, { x, y -> openHand(x, y) })
                }
                Gesture.CHEER -> {
                    val w2 = sin(t * 9f) * 0.9f
                    h = placed(6f, 36f + lift + w2, -0.24f, true, 1.3f, ix, iy, { x, y -> openHand(x, y) }) ||
                        placed(34f, 36f + lift - w2, 0.24f, false, 1.3f, ix, iy, { x, y -> openHand(x, y) })
                }
                Gesture.SHRUG -> {
                    h = placed(7f, 45f + lift, -0.66f, true, 1.2f, ix, iy, { x, y -> openHand(x, y) }) ||
                        placed(33f, 45f + lift, 0.66f, false, 1.2f, ix, iy, { x, y -> openHand(x, y) })
                }
                Gesture.NONE -> {}
            }
            sc.hand[iy * W + ix] = h
            sc.extra[iy * W + ix] = e
        }
        for (iy in 0 until H) for (ix in 0 until W) {
            val i = iy * W + ix
            if (sc.hand[i] || sc.extra[i]) { sc.out[i] = true; continue }
            if (!sc.face[i]) { sc.out[i] = false; continue }
            // distance (in cells) to the nearest hand cell: right next to it the face is removed, a little further it is dimmed
            var d = 9
            for (dy in -3..3) {
                val yy = iy + dy
                if (yy < 0 || yy >= H) continue
                for (dx in -3..3) {
                    val xx = ix + dx
                    if (xx < 0 || xx >= W) continue
                    if (sc.hand[yy * W + xx]) { val m = max(abs(dx), abs(dy)); if (m < d) d = m }
                }
            }
            when {
                d <= 1 -> sc.out[i] = false
                d <= 3 -> { sc.out[i] = false; sc.dim[i] = true }
                else -> sc.out[i] = true
            }
        }
    }
}

/** Moves the face like a person: breathing, blinking, wandering eyes, expressions, and a mouth that follows the spoken words. */
class FaceAnimator {
    val p = FaceParams()
    var mode = FaceMode.IDLE
    var mood = FaceMood.NEUTRAL
    var level = 0f // microphone loudness 0..1 while listening
    var voiceLevel = -1f // loudness of the audio being spoken (0..1), or -1 when unknown
    var processing = false // turning your speech into text
    var time = 0f
        private set

    /** Hand gesture currently shown (hands rise from below while [gestureAmount] goes 0 → 1). */
    var shownGesture = Gesture.NONE
        private set
    var gestureAmount = 0f
        private set
    /** When true the assistant makes gestures by itself (thinking pose, pointing while talking …). Off for the small companion. */
    var autoGestures = true
    private var forced = Gesture.NONE
    private var forcedUntil = -1f
    private var nextAuto = 4f

    /** If set, the eyes look this way (-1..1) instead of wandering. */
    var externalLookX: Float? = null
    var externalLookY: Float? = null

    /** Make a gesture now, for [seconds]. */
    fun play(g: Gesture, seconds: Float = 2.4f) { forced = g; forcedUntil = time + seconds }

    private var nextBlink = 2f
    private var blinkStart = -1f
    private var lookTx = 0f
    private var lookTy = 0f
    private var nextLook = 1f
    private var word = ""
    private var wordStart = 0f
    private var wordSeq = 0
    private var browPunch = 0f
    private val rnd = java.util.Random(7)

    fun beginSpeaking() { word = ""; wordStart = time }

    /** Called for every word the voice speaks. */
    fun onWord(w: String, seq: Int) {
        if (seq == wordSeq) return
        wordSeq = seq
        word = w
        wordStart = time
        if (w.endsWith("?") || w.endsWith("!")) browPunch = 1f
    }

    private fun lerp(cur: Float, target: Float, k: Float, dt: Float): Float = cur + (target - cur) * (1f - exp(-k * dt))

    private fun viseme(c: Char): Pair<Float, Float> = when (c.lowercaseChar()) {
        'a' -> 0.9f to 0.4f
        'e' -> 0.55f to 0.8f
        'i', 'y' -> 0.35f to 1f
        'o' -> 0.8f to -0.5f
        'u' -> 0.5f to -0.9f
        'w' -> 0.35f to -0.8f
        'm', 'b', 'p' -> 0f to 0f
        'f', 'v' -> 0.15f to 0.5f
        in 'a'..'z' -> 0.28f to 0.1f
        else -> (0.25f + (c.code % 5) * 0.14f) to (((c.code % 3) - 1) * 0.4f) // other alphabets: lively but neutral
    }

    fun update(dt: Float) {
        time += dt
        val t = time
        var mouthOpenT = 0f
        var mouthWideT = 0f
        var smileT = 0.1f
        var browT = 0f
        var tiltT = 0f
        var eyeT = 1f
        var headXT = sin(t * 0.6f) * 0.2f
        var headYT = sin(t * 1.4f) * 0.25f // breathing
        var rollT = sin(t * 0.5f) * 0.015f

        // gaze wanders; looks up-right when thinking
        if (t > nextLook) {
            lookTx = (rnd.nextFloat() - 0.5f) * 1.6f
            lookTy = (rnd.nextFloat() - 0.5f) * 0.8f
            nextLook = t + 1.2f + rnd.nextFloat() * 2.5f
        }
        var lookXT = lookTx
        var lookYT = lookTy
        // someone (the small companion) can make the eyes follow a point on the screen
        externalLookX?.let { lookXT = it; lookYT = externalLookY ?: 0f }

        when (mode) {
            FaceMode.IDLE -> {}
            FaceMode.LISTENING -> {
                browT = 0.45f; smileT = 0.15f
                lookXT *= 0.3f; lookYT *= 0.3f
                headYT = -0.3f + level * 0.5f * (0.5f + 0.5f * sin(t * 13f))
                headXT = 0f
            }
            FaceMode.THINKING -> {
                if (processing) {
                    // transcribing: eyes sweep side to side as if reading
                    lookXT = sin(t * 3f) * 0.9f; lookYT = 0.35f
                } else {
                    // thinking: looks up and to the side
                    lookXT = 0.9f + sin(t * 2f) * 0.2f; lookYT = -0.8f
                }
                browT = 0.25f; tiltT = -0.35f; smileT = -0.1f
                headXT = 0.4f; rollT = 0.05f
                mouthOpenT = 0f
            }
            FaceMode.SPEAKING -> {
                headXT = sin(t * 1.7f) * 0.35f
                headYT = sin(t * 2.3f) * 0.25f
                rollT = sin(t * 1.1f) * 0.03f
                lookXT *= 0.4f; lookYT *= 0.4f
                val el = t - wordStart
                val len = max(1, word.length)
                val dur = max(0.18f, len * 0.072f)
                if (voiceLevel >= 0f) {
                    // real audio: the mouth opens exactly as loud as the voice; the word decides how wide/round it is
                    mouthOpenT = if (voiceLevel < 0.06f) 0.04f else (0.12f + voiceLevel * 0.9f).coerceAtMost(1f)
                    mouthWideT = if (word.isNotEmpty()) viseme(word[((el / dur) * len).toInt().coerceIn(0, len - 1)]).second else 0f
                } else if (word.isNotEmpty() && el < dur + 0.12f) {
                    val idx = ((el / dur) * len).toInt().coerceIn(0, len - 1)
                    val (o, w) = viseme(word[idx])
                    mouthOpenT = o; mouthWideT = w
                } else if (el > 0.7f) {
                    // the voice engine reports no words: keep the jaw moving anyway
                    val flap = 0.5f + 0.5f * sin(t * 17f)
                    mouthOpenT = 0.15f + 0.6f * flap * (0.6f + 0.4f * sin(t * 5.3f))
                    mouthWideT = 0.3f * sin(t * 9f)
                } else {
                    mouthOpenT = 0.05f
                }
                headYT += mouthOpenT * 0.25f
            }
        }

        // mood colours the expression
        when (mood) {
            FaceMood.HAPPY -> { smileT = if (mode == FaceMode.THINKING) smileT else 0.85f; browT += 0.2f }
            FaceMood.SAD -> if (mode != FaceMode.LISTENING && mode != FaceMode.THINKING) { smileT = -0.7f; browT -= 0.2f; tiltT = -0.8f; rollT = 0.06f }
            FaceMood.SURPRISED -> if (mode == FaceMode.SPEAKING || mode == FaceMode.IDLE) { browT += 0.6f }
            FaceMood.ANGRY -> { smileT = -0.35f; browT -= 0.5f; tiltT = 0.9f }
            FaceMood.SLEEPY -> { eyeT = 0.38f; smileT = 0.02f; browT -= 0.2f; headYT += 0.7f; rollT = -0.05f }
            FaceMood.WINK -> { smileT = 0.9f; browT += 0.15f }
            FaceMood.LOVE -> { smileT = 1f; browT += 0.3f }
            FaceMood.CONFUSED -> { smileT = -0.1f; tiltT = -0.7f; browT += 0.4f; rollT = 0.09f }
            FaceMood.NEUTRAL -> {}
        }
        browPunch *= exp(-3f * dt)
        browT += browPunch * 0.6f

        // gestures
        var want = Gesture.NONE
        if (forcedUntil > t) want = forced
        else if (autoGestures) {
            if (mode == FaceMode.THINKING && !processing) want = Gesture.THINK
            else if (mode == FaceMode.SPEAKING) {
                if (t > nextAuto) {
                    val pool = if (mood == FaceMood.HAPPY) arrayOf(Gesture.THUMBS_UP, Gesture.HEART, Gesture.CLAP, Gesture.CHEER)
                    else if (mood == FaceMood.CONFUSED) arrayOf(Gesture.SHRUG, Gesture.THINK, Gesture.SHRUG)
                    else arrayOf(Gesture.POINT, Gesture.PRESENT, Gesture.POINT)
                    forced = pool[rnd.nextInt(pool.size)]
                    forcedUntil = t + 2.2f
                    nextAuto = t + 5f + rnd.nextFloat() * 5f
                    want = forced
                }
            } else nextAuto = t + 3f
        }
        if (want != Gesture.NONE) shownGesture = want
        gestureAmount = lerp(gestureAmount, if (want != Gesture.NONE) 1f else 0f, 7f, dt)
        if (want == Gesture.NONE && gestureAmount < 0.02f) { shownGesture = Gesture.NONE; gestureAmount = 0f }
        val eyeArcT = if ((mood == FaceMood.HAPPY && mode != FaceMode.THINKING && smileT > 0.6f) || mood == FaceMood.LOVE ||
            shownGesture == Gesture.HEART || shownGesture == Gesture.THUMBS_UP) 1f else 0f
        p.eyeArc = eyeArcT
        p.wink = if (mood == FaceMood.WINK) 1f else 0f

        // blinking
        var blink = 1f
        if (blinkStart < 0f && t > nextBlink) blinkStart = t
        if (blinkStart >= 0f) {
            val k = (t - blinkStart) / 0.16f
            if (k >= 1f) {
                blinkStart = -1f
                nextBlink = t + (if (mode == FaceMode.THINKING) 1.2f else 2f) + rnd.nextFloat() * 3.5f
            } else blink = 1f - sin(k * PI.toFloat())
        }
        if (mood == FaceMood.SURPRISED && mode != FaceMode.THINKING) eyeT = 1.0f

        p.eyeOpen = eyeT * blink
        p.mouthOpen = lerp(p.mouthOpen, mouthOpenT, 22f, dt)
        p.mouthWide = lerp(p.mouthWide, mouthWideT, 18f, dt)
        p.smile = lerp(p.smile, smileT, 7f, dt)
        p.brow = lerp(p.brow, browT.coerceIn(-1f, 1.3f), 9f, dt)
        p.tilt = lerp(p.tilt, tiltT, 7f, dt)
        p.lookX = lerp(p.lookX, lookXT, 8f, dt)
        p.lookY = lerp(p.lookY, lookYT, 8f, dt)
        p.headX = lerp(p.headX, headXT, 5f, dt)
        p.headY = lerp(p.headY, headYT, 6f, dt)
        p.roll = lerp(p.roll, rollT, 5f, dt)
    }
}
