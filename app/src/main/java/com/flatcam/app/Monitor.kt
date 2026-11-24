package com.flatcam.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Monitor-only image analysis, run on a worker thread:
 *  - focus assist: ISOLATE (out-of-focus areas are gently dimmed so the sharp zone simply "stays bright")
 *    or classic PEAKING (coloured dots on sharp edges), plus a focus score for the focus meter
 *  - LUT preview
 * Nothing here touches the recorded picture.
 */
class Monitor {
    @Volatile var mode = 0          // 0 off, 1 isolate, 2 peaking
    @Volatile var sens = 1          // 0 low, 1 medium, 2 high
    @Volatile var peakColor = 0xFFFF3B30.toInt()
    @Volatile var lut: Lut3D? = null

    // results (read on the UI thread after process() returns)
    @Volatile var lutBmp: Bitmap? = null
    @Volatile var focusBmp: Bitmap? = null
    @Volatile var score = 0f

    private val lutBufs = arrayOfNulls<Bitmap>(2)
    private val peakBufs = arrayOfNulls<Bitmap>(2)
    private var flip = 0
    private var lutPx = IntArray(0)
    private var luma = IntArray(0)
    private var ii = IntArray(0)
    private var pk = IntArray(0)
    private var prevMask = FloatArray(0)

    fun process(px: IntArray, w: Int, h: Int) {
        flip = flip xor 1
        if (mode != 0) focus(px, w, h) else { focusBmp = null; prevMask = FloatArray(0); score = 0f }
        val l = lut
        if (l != null) {
            if (lutPx.size < w * h) lutPx = IntArray(w * h)
            l.apply(px, lutPx, w * h)
            var b = lutBufs[flip]
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); lutBufs[flip] = b }
            b.setPixels(lutPx, 0, w, 0, 0, w, h)
            lutBmp = b
        } else lutBmp = null
    }

    private fun focus(px: IntArray, w: Int, h: Int) {
        val n = w * h
        if (luma.size < n) luma = IntArray(n)
        for (i in 0 until n) { val p = px[i]; luma[i] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8 }
        val iw = w + 1
        if (ii.size < iw * (h + 1)) ii = IntArray(iw * (h + 1))
        for (x in 0..w) ii[x] = 0
        for (y in 1..h) {
            var row = 0
            ii[y * iw] = 0
            for (x in 1..w) { row += luma[(y - 1) * w + x - 1]; ii[y * iw + x] = ii[(y - 1) * iw + x] + row }
        }
        fun box(x: Int, y: Int, r: Int): Float {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1); val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            val s = ii[y1 * iw + x1] - ii[y0 * iw + x1] - ii[y1 * iw + x0] + ii[y0 * iw + x0]
            return s.toFloat() / ((x1 - x0) * (y1 - y0))
        }
        val bs = 8
        val bw = w / bs; val bh = h / bs
        if (bw < 2 || bh < 2) return
        val fS = FloatArray(bw * bh); val cS = FloatArray(bw * bh)
        val tF = when (sens) { 0 -> 14f; 1 -> 9f; else -> 5f }
        val peak = mode == 2
        if (peak) { if (pk.size < n) pk = IntArray(n); java.util.Arrays.fill(pk, 0, n, 0) }
        val col = peakColor
        for (y in 1 until h - 1) {
            val by = min(y / bs, bh - 1)
            for (x in 1 until w - 1) {
                val b3 = box(x, y, 1)
                val f = abs(luma[y * w + x] - b3)
                val c = abs(b3 - box(x, y, 4))
                val bi = by * bw + min(x / bs, bw - 1)
                fS[bi] += f; cS[bi] += c
                if (peak && f > tF) pk[y * w + x] = col
            }
        }
        val lo = when (sens) { 0 -> 0.32f; 1 -> 0.22f; else -> 0.14f }
        val inv = 1f / (bs * bs)
        val mask = FloatArray(bw * bh)
        val scores = FloatArray(bw * bh)
        for (i in mask.indices) {
            val f = fS[i] * inv; val c = cS[i] * inv
            val s = f / (c + 0.5f)
            val gate = smooth(1.0f, 2.5f, c)
            val m = smooth(lo, lo + 0.25f, s) * gate
            mask[i] = m
            scores[i] = if (c > 1.5f) s else 0f
        }
        if (prevMask.size == mask.size) for (i in mask.indices) mask[i] = 0.5f * prevMask[i] + 0.5f * mask[i]
        prevMask = mask
        // focus score: average of the best few blocks (0..1)
        val top = scores.sortedDescending().take(max(4, scores.size / 20))
        val sc = (top.average().toFloat() / 0.9f).coerceIn(0f, 1f)
        score = score * 0.6f + sc * 0.4f
        if (peak) {
            var b = peakBufs[flip]
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); peakBufs[flip] = b }
            b.setPixels(pk, 0, w, 0, 0, w, h)
            focusBmp = b
        } else {
            val mb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val a = IntArray(bw * bh)
            for (i in a.indices) a[i] = ((1f - mask[i]) * 0.45f * 255f).toInt().coerceIn(0, 255) shl 24
            mb.setPixels(a, 0, bw, 0, 0, bw, bh)
            focusBmp = mb
        }
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
