package com.filmtarget.filtarview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Big round shutter / record button. */
class RecordButton(c: Context) : View(c) {
    var recording = false
        set(v) { field = v; invalidate() }
    var photo = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth
        cv.drawCircle(cx, cy, r, ring)
        if (recording) {
            fill.color = Color.parseColor("#FF3B30")
            val s = r * 0.48f
            cv.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.3f, s * 0.3f, fill)
        } else {
            fill.color = if (photo) Color.WHITE else Color.parseColor("#FF3B30")
            cv.drawCircle(cx, cy, r * 0.78f, fill)
        }
    }
}

/** Touch layer + gamepad cursor drawn above the picture. */
class TouchLayer(c: Context) : View(c) {
    var cx = -1f
    var cy = -1f
    var showCursor = false
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE }
    override fun onDraw(cv: Canvas) {
        if (!showCursor || cx < 0f) return
        cv.drawCircle(cx, cy, 16f * d, p)
        cv.drawLine(cx - 24f * d, cy, cx - 8f * d, cy, p); cv.drawLine(cx + 8f * d, cy, cx + 24f * d, cy, p)
        cv.drawLine(cx, cy - 24f * d, cx, cy - 8f * d, p); cv.drawLine(cx, cy + 8f * d, cx, cy + 24f * d, p)
    }
}

class MainActivity : Activity() {
    private val amber = Color.parseColor("#FFB300")
    private val red = Color.parseColor("#FF3B30")
    private val green = Color.parseColor("#4CD964")
    private val glass = 0x70000000
    private val hair = 0x33FFFFFF
    private val gray = 0xFFB0B0B0.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("filtarview", MODE_PRIVATE) }

    private var link: Link? = null
    private var stopDisc: (() -> Unit)? = null

    // connect screen
    private var status: TextView? = null
    private var foundBox: LinearLayout? = null
    private val found = HashSet<String>()
    private var ipEt: EditText? = null
    private var pinEt: EditText? = null

    // monitor screen
    private lateinit var img: ImageView
    private lateinit var touch: TouchLayer
    private lateinit var recBtn: RecordButton
    private lateinit var timerTv: TextView
    private lateinit var segV: TextView
    private lateinit var segP: TextView
    private lateinit var fmtTv: TextView
    private lateinit var afChip: TextView
    private lateinit var meterChip: TextView
    private lateinit var micTv: TextView
    private lateinit var padTv: TextView
    private lateinit var lensChip: TextView
    private lateinit var flipChip: TextView
    private lateinit var nightChip: TextView
    private val pillBox = HashMap<Int, LinearLayout>()
    private val pillVal = HashMap<Int, TextView>()
    private lateinit var presetRow: LinearLayout
    private lateinit var presetScroll: HorizontalScrollView
    private lateinit var strip: LinearLayout
    private lateinit var stripLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var focusRow: LinearLayout
    private val markChips = arrayOfNulls<TextView>(3)
    private lateinit var rackChip: TextView
    private lateinit var lockChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var meterCard: LinearLayout
    private lateinit var meterText: TextView
    private lateinit var settingsScroll: ScrollView
    private lateinit var settingsCol: LinearLayout
    private var dragging = false
    private var lastSend = 0L
    private var bmpW = 16
    private var bmpH = 9
    private var sel = 0
    private var rowSig = ""
    private var preSig = "\u0000"
    private val rowChips = ArrayList<TextView?>()
    private val rowData = ArrayList<List<String>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun round(color: Int, r: Int = 12, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun label(t: String, size: Float = 12f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }
    private fun chip(t: String, onClick: () -> Unit) = label(t, 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)); background = round(glass, 14, hair)
        maxLines = 1; setOnClickListener { onClick() }
    }
    private fun ll(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)

    // =====================================================================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        showConnect()
    }

    override fun onDestroy() { stopDisc?.invoke(); link?.close(); super.onDestroy() }

    override fun onBackPressed() { if (link != null) showConnect() else super.onBackPressed() }

    // =====================================================================
    // Connect screen
    // =====================================================================
    private fun showConnect() {
        link?.close(); link = null
        stopDisc?.invoke()
        found.clear()
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF0B0C0E.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        sv.addView(col)
        col.addView(label("FilTarView", 28f, amber, true))
        col.addView(label("Monitor and remote control for FilTarPro by filmtarget", 12f, gray))
        fun head(t: String) = col.addView(label(t, 11f, amber, true).apply { letterSpacing = 0.15f; setPadding(0, dp(22), 0, dp(6)) })
        fun field(h: String, num: Boolean) = EditText(this).apply {
            hint = h; setHintTextColor(0xFF707070.toInt()); setTextColor(Color.WHITE); setSingleLine(true)
            if (num) inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = round(0x22FFFFFF, 10, hair)
        }
        head("WI-FI OR HOTSPOT")
        col.addView(label("On FilTarPro tap MON (top centre) to see its address and PIN. Both devices must share a Wi-Fi network, or one phone's hotspot.", 11f, gray))
        foundBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(foundBox)
        ipEt = field("FilTarPro address, e.g. 192.168.43.1", false).apply { setText(prefs.getString("ip", "")) }
        pinEt = field("PIN shown on FilTarPro", true).apply { setText(prefs.getString("pin", "")) }
        col.addView(ipEt, ll(-1, -2).apply { topMargin = dp(10) })
        col.addView(pinEt, ll(-1, -2).apply { topMargin = dp(8) })
        col.addView(chip("CONNECT  (Wi-Fi)") { connectWifi() }.apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setTextColor(Color.BLACK); background = round(amber, 14) }, ll(-1, -2).apply { topMargin = dp(12) })
        head("BLUETOOTH")
        col.addView(label("Pair both devices in Android Bluetooth settings, switch the Bluetooth link on in FilTarPro (MON), enter the PIN above, then choose the device. Bluetooth is slower, so the picture is smaller.", 11f, gray))
        col.addView(chip("CHOOSE PAIRED DEVICE  (Bluetooth)") { chooseBt() }.apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }, ll(-1, -2).apply { topMargin = dp(10) })
        status = label("", 12f, Color.WHITE).apply { setPadding(0, dp(18), 0, 0) }
        col.addView(status)
        setContentView(sv)
        stopDisc = Net.discover { name, ip -> ui.post { addFound(name, ip) } }
    }

    private fun addFound(name: String, ip: String) {
        if (!found.add(ip)) return
        foundBox?.addView(chip("$name   $ip") { ipEt?.setText(ip) }.apply { setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(green) },
            ll(-1, -2).apply { topMargin = dp(6) })
    }

    private fun pinText() = pinEt?.text?.toString()?.trim() ?: ""

    private fun connectWifi() {
        val ip = ipEt?.text?.toString()?.trim() ?: ""; val pin = pinText()
        if (ip.isEmpty() || pin.isEmpty()) { status?.text = "Enter the address and PIN"; return }
        prefs.edit().putString("ip", ip).putString("pin", pin).apply()
        status?.text = "Connecting..."
        Thread {
            try { val l = Net.connectTcp(ip, pin); ui.post { onConnected(l) } }
            catch (e: Exception) { ui.post { status?.text = "Could not connect: ${e.message}" } }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun chooseBt() {
        val pin = pinText()
        if (pin.isEmpty()) { status?.text = "Enter the PIN first"; return }
        prefs.edit().putString("pin", pin).apply()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1); return
        }
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (ad == null || !ad.isEnabled) { status?.text = "Turn Bluetooth on first"; return }
        val devs = ad.bondedDevices.toList()
        if (devs.isEmpty()) { status?.text = "No paired devices. Pair with the FilTarPro phone in Android settings."; return }
        AlertDialog.Builder(this).setTitle("Paired devices")
            .setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                status?.text = "Connecting over Bluetooth..."
                Thread {
                    try { val l = Net.connectBt(devs[i], pin); ui.post { onConnected(l) } }
                    catch (e: Exception) { ui.post { status?.text = "Bluetooth connect failed: ${e.message}. Is the Bluetooth link on in FilTarPro?" } }
                }.start()
            }.show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 1 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) chooseBt()
        else status?.text = "Bluetooth permission is needed"
    }

    // =====================================================================
    // Connected: link plumbing
    // =====================================================================
    private fun onConnected(l: Link) {
        stopDisc?.invoke(); stopDisc = null
        link = l
        rowSig = ""; preSig = "\u0000"
        buildMonitor()
        Thread {
            try {
                while (true) {
                    val m = Proto.read(l.din)
                    when (m.first) {
                        Proto.FRAME -> {
                            val bm = BitmapFactory.decodeByteArray(m.second, 0, m.second.size)
                            if (bm != null) runOnUiThread { bmpW = bm.width; bmpH = bm.height; img.setImageBitmap(bm) }
                        }
                        Proto.STATE -> {
                            val map = HashMap<String, String>()
                            for (line in String(m.second).split('\n')) { val i = line.indexOf('='); if (i > 0) map[line.substring(0, i)] = line.substring(i + 1) }
                            runOnUiThread { applyState(map) }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                ui.post { if (link === l) { toast("Disconnected from FilTarPro"); showConnect() } }
            }
        }.start()
    }

    private fun send(cmd: String) {
        val l = link ?: return
        sender.execute { try { l.send(cmd) } catch (_: Exception) {} }
    }

    private fun sendThrottled(cmd: String, gap: Long = 40) {
        val n = SystemClock.elapsedRealtime()
        if (n - lastSend >= gap) { lastSend = n; send(cmd) }
    }

    // =====================================================================
    // Monitor screen
    // =====================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun buildMonitor() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        root.addView(img, lp(-1, -1))
        touch = TouchLayer(this)
        root.addView(touch, lp(-1, -1))
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tapAt(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) { send("release") }
        })
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { sendThrottled("zoomf ${d.scaleFactor}", 60); return true }
        })
        touch.setOnTouchListener { _, e -> sg.onTouchEvent(e); if (!sg.isInProgress) gd.onTouchEvent(e); true }

        // top bar
        timerTv = label("", 13f, red, true).apply { typeface = Typeface.MONOSPACE; setPadding(dp(4), 0, dp(10), 0) }
        segV = chip("VIDEO") { send("mode v") }
        segP = chip("PHOTO") { send("mode p") }
        fmtTv = label("", 10f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(8), 0) }
        afChip = chip("AF") { send("release") }
        meterChip = chip("METER") { send("meter") }
        micTv = label("", 10f, green, true).apply { setPadding(dp(8), 0, dp(8), 0) }
        padTv = label("", 10f, green, true).apply { setPadding(dp(4), 0, dp(8), 0) }
        lensChip = chip("LENS") { send("lens") }
        flipChip = chip("FRONT") { send("flip") }
        nightChip = chip("NIGHT") { }
        val settingsChip = chip("SETTINGS") { settingsScroll.visibility = if (settingsScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val discChip = chip("DISCONNECT") { showConnect() }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(14)) }
        row.addView(timerTv); row.addView(segV); row.addView(segP, ll(-2, -2).apply { leftMargin = dp(4) })
        row.addView(fmtTv); row.addView(afChip); row.addView(meterChip, ll(-2, -2).apply { leftMargin = dp(6) })
        row.addView(micTv); row.addView(padTv)
        for (c in listOf(lensChip, flipChip, nightChip, settingsChip, discChip)) row.addView(c, ll(-2, -2).apply { leftMargin = dp(6) })
        val topScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(row)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        }
        root.addView(topScroll, lp(-1, -2, Gravity.TOP))

        recBtn = RecordButton(this).apply { setOnClickListener { send("shutter") } }
        root.addView(recBtn, lp(dp(70), dp(70), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(14) })

        // bottom: pills, presets, strip
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(10)) }
        val pills = LinearLayout(this)
        for ((id, name) in listOf(1 to "ISO", 2 to "SHUTTER", 3 to "FOCUS", 4 to "WB", 5 to "EV")) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4), dp(5), dp(4), dp(5)); background = round(glass, 10, hair)
                setOnClickListener { send("sel ${if (sel == id) 0 else id}") }
            }
            box.addView(label(name, 9f, gray).apply { letterSpacing = 0.1f })
            val v = label("-", 13f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE; maxLines = 1 }
            box.addView(v)
            pillBox[id] = box; pillVal[id] = v
            pills.addView(box, ll(0, -2, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        bottom.addView(pills, ll(-1, -2).apply { bottomMargin = dp(6) })
        presetRow = LinearLayout(this)
        presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow); visibility = View.GONE }
        bottom.addView(presetScroll, ll(-1, -2).apply { bottomMargin = dp(6) })
        strip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = round(glass, 18, hair); visibility = View.GONE }
        val top2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stripLabel = label("", 12f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE }
        seek = SeekBar(this).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(amber); thumbTintList = ColorStateList.valueOf(amber)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) sendThrottled("slider $p", 35) }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) { dragging = false; send("slider ${sb.progress}") }
            })
        }
        top2.addView(stripLabel, ll(dp(104), -2)); top2.addView(seek, ll(0, -2, 1f))
        strip.addView(top2, ll(-1, -2))
        focusRow = LinearLayout(this).apply { visibility = View.GONE }
        for (i in 0..2) {
            val c = chip("ABC"[i].toString()) { send("mark $i") }
            c.setOnLongClickListener { send("markset $i"); true }
            c.setPadding(dp(8), dp(6), dp(8), dp(6))
            markChips[i] = c
            focusRow.addView(c, ll(-2, -2).apply { leftMargin = dp(3) })
        }
        rackChip = chip("2.0s") { send("rack") }.apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        focusRow.addView(rackChip, ll(-2, -2).apply { leftMargin = dp(3) })
        lockChip = chip("LOCK") { send("lock") }
        modeChip = chip("AUTO") { send("smode") }
        val chips = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        chips.addView(focusRow); chips.addView(lockChip, ll(-2, -2).apply { leftMargin = dp(6) }); chips.addView(modeChip, ll(-2, -2).apply { leftMargin = dp(4) })
        strip.addView(chips, ll(-1, -2).apply { topMargin = dp(6) })
        bottom.addView(strip, ll(-1, -2))
        root.addView(bottom, lp(-1, -2, Gravity.BOTTOM).apply { rightMargin = dp(92) })

        meterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = round(0xDD101114.toInt(), 16, 0x66FFFFFF); visibility = View.GONE
        }
        meterText = label("", 12f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE }
        meterCard.addView(meterText)
        val mr = LinearLayout(this)
        mr.addView(chip("APPLY") { send("apply 0") }); mr.addView(chip("APPLY + LOCK") { send("apply 1") }, ll(-2, -2).apply { leftMargin = dp(6) })
        mr.addView(chip("CLOSE") { send("closemeter") }, ll(-2, -2).apply { leftMargin = dp(6) })
        meterCard.addView(mr, ll(-2, -2).apply { topMargin = dp(8) })
        root.addView(meterCard, lp(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(56) })

        settingsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        settingsScroll = ScrollView(this).apply { background = round(0xEE101114.toInt(), 18, hair); addView(settingsCol); visibility = View.GONE }
        val sw = resources.displayMetrics.widthPixels
        root.addView(settingsScroll, lp(min(dp(340), sw - dp(110)), -1, Gravity.END).apply { rightMargin = dp(96); topMargin = dp(50); bottomMargin = dp(10) })
        setContentView(root)
    }

    private fun imgRect(): RectF {
        val vw = touch.width.toFloat(); val vh = touch.height.toFloat()
        val s = min(vw / bmpW, vh / bmpH)
        val w = bmpW * s; val h = bmpH * s
        return RectF((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
    }

    private fun tapAt(x: Float, y: Float) {
        val r = imgRect()
        if (!r.contains(x, y)) return
        send("tap ${(x - r.left) / r.width()} ${(y - r.top) / r.height()}")
    }

    private fun styleChip(c: TextView, on: Boolean) { c.setTextColor(if (on) Color.BLACK else Color.WHITE); c.background = round(if (on) amber else glass, 14, hair) }

    private fun applyState(m: Map<String, String>) {
        fun g(k: String) = m[k] ?: ""
        recBtn.recording = g("rec") == "1"; recBtn.photo = g("vid") == "0"
        timerTv.text = g("time")
        val vid = g("vid") == "1"
        segV.setTextColor(if (vid) Color.BLACK else Color.WHITE); segV.background = if (vid) round(amber, 14) else round(glass, 14, hair)
        segP.setTextColor(if (!vid) Color.BLACK else Color.WHITE); segP.background = if (!vid) round(amber, 14) else round(glass, 14, hair)
        fmtTv.text = g("fmt"); afChip.text = g("af"); micTv.text = g("mic")
        padTv.text = if (g("pad") == "1") "PAD" else ""
        styleChip(meterChip, g("marm") == "1")
        g("lens").let { lensChip.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE; lensChip.text = it }
        g("flip").let { flipChip.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE; flipChip.text = it }
        val ni = g("night")
        nightChip.visibility = if (ni == "-1" || ni.isEmpty()) View.GONE else View.VISIBLE
        styleChip(nightChip, ni == "1")
        sel = g("sel").toIntOrNull() ?: 0
        val locks = g("pl")
        for (i in 1..5) {
            pillVal[i]?.text = g("p$i")
            val col = when { locks.getOrNull(i - 1) == '1' -> red; i == sel -> amber; else -> hair }
            pillBox[i]?.background = round(glass, 10, col)
        }
        strip.visibility = if (sel == 0) View.GONE else View.VISIBLE
        stripLabel.text = g("sl")
        if (!dragging) seek.progress = g("sp").toIntOrNull() ?: 0
        val locked = g("sk") == "1"
        seek.isEnabled = !locked
        modeChip.text = g("sm")
        lockChip.text = if (locked) "LOCKED" else "LOCK"; lockChip.background = round(if (locked) red else glass, 14, hair)
        focusRow.visibility = if (sel == 3) View.VISIBLE else View.GONE
        g("marks").split('\u0001').forEachIndexed { i, t -> if (i < 3) markChips[i]?.text = t }
        rackChip.text = g("rack")
        val pre = g("pre")
        if (pre != preSig) {
            preSig = pre
            presetRow.removeAllViews()
            if (pre.isNotEmpty()) pre.split('\u0001').forEachIndexed { i, t ->
                presetRow.addView(chip(t) { send("preset $i") }, ll(-2, -2).apply { rightMargin = dp(5) })
            }
            presetScroll.visibility = if (presetRow.childCount > 0 && sel != 0) View.VISIBLE else View.GONE
        } else presetScroll.visibility = if (presetRow.childCount > 0 && sel != 0) View.VISIBLE else View.GONE
        val mt = g("meter")
        meterCard.visibility = if (mt.isEmpty()) View.GONE else View.VISIBLE
        meterText.text = mt
        applyRows(m)
    }

    // ---- settings panel generated from the rows FilTarPro publishes
    private fun applyRows(m: Map<String, String>) {
        val n = m["nrows"]?.toIntOrNull() ?: return
        val rows = ArrayList<List<String>>()
        val sig = StringBuilder()
        for (i in 0 until n) {
            val p = (m["r$i"] ?: "").split('\t')
            if (p.size < 4) { rows.add(listOf("0", "", "0", "")); continue }
            rows.add(p)
            sig.append(p[0]).append(p[1]).append(p[3]).append('|')
        }
        if (sig.toString() != rowSig) {
            rowSig = sig.toString()
            settingsCol.removeAllViews(); rowChips.clear()
            val cust = chip("CUSTOM ASPECT RATIO...") { askRatio() }
            settingsCol.addView(cust, ll(-2, -2).apply { topMargin = dp(8) })
            for ((i, p) in rows.withIndex()) {
                val kind = p[0].toIntOrNull() ?: 0
                if (kind == 0) {
                    settingsCol.addView(label(p[1], 11f, amber, true).apply { letterSpacing = 0.15f; setPadding(0, dp(14), 0, dp(4)) })
                    rowChips.add(null); continue
                }
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
                r.addView(label(p[1], 12f), ll(0, -2, 1f))
                val opts = p[3].split('\u0001')
                val c = chip(opts.getOrNull(p[2].toIntOrNull() ?: 0) ?: "-") { 
                    val od = rowData.getOrNull(i) ?: return@chip
                    val os = od[3].split('\u0001'); val cur = od[2].toIntOrNull() ?: 0
                    send("set $i ${(cur + 1) % max(os.size, 1)}")
                }
                r.addView(c); settingsCol.addView(r); rowChips.add(c)
            }
        }
        rowData.clear(); rowData.addAll(rows)
        for ((i, p) in rows.withIndex()) {
            val c = rowChips.getOrNull(i) ?: continue
            val t = p[3].split('\u0001').getOrNull(p[2].toIntOrNull() ?: 0) ?: "-"
            if (c.text.toString() != t) c.text = t
        }
    }

    private fun askRatio() {
        val et = EditText(this).apply { hint = "e.g. 2.2 or 11:5"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Custom aspect ratio").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val p = et.text.toString().trim().replace(',', '.').split(':', 'x', '/')
                val a = p.getOrNull(0)?.toFloatOrNull(); val b = if (p.size > 1) p[1].toFloatOrNull() else 1f
                if (a != null && b != null) send("ratio $a $b")
            }.setNegativeButton("Cancel", null).show()
    }

    // =====================================================================
    // Gamepad (PS4 / PS5 / Xbox) -> commands for FilTarPro
    // =====================================================================
    private var pLX = 0f; private var pLY = 0f; private var pRX = 0f; private var pRY = 0f
    private var pLT = 0f; private var pRT = 0f
    private var hatX = 0
    private var l2Key = false; private var r2Key = false
    private var curX = -1f; private var curY = -1f
    private var padRunning = false
    private var lastT = 0L
    private var sentAdj = 0f; private var sentPull = 0f; private var sentZv = 0f

    private fun isPad(src: Int) = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    private fun dead(v: Float): Float { val a = abs(v); return if (a < 0.14f) 0f else Math.signum(v) * (a - 0.14f) / 0.86f }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (link == null || !(isPad(e.source) || KeyEvent.isGamepadButton(e.keyCode))) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { hatX = if (down) -1 else if (hatX == -1) 0 else hatX; kick(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { hatX = if (down) 1 else if (hatX == 1) 0 else hatX; kick(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) send("vert 1"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down && e.repeatCount == 0) send("vert -1"); return true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { l2Key = down; kick(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { r2Key = down; kick(); return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (down && e.repeatCount == 0) button(e.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun button(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> send("shutter")
            KeyEvent.KEYCODE_BUTTON_B -> if (settingsScroll.visibility == View.VISIBLE) settingsScroll.visibility = View.GONE else send("back")
            KeyEvent.KEYCODE_BUTTON_X -> send("meter")
            KeyEvent.KEYCODE_BUTTON_Y -> send("mode ${if (recBtn.photo) "v" else "p"}")
            KeyEvent.KEYCODE_BUTTON_L1 -> send("sel ${(sel + 5) % 6}")
            KeyEvent.KEYCODE_BUTTON_R1 -> send("sel ${(sel + 1) % 6}")
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                val r = imgRect()
                val x = if (curX < 0f) r.centerX() else curX; val y = if (curY < 0f) r.centerY() else curY
                touch.cx = x; touch.cy = y; touch.showCursor = true; touch.invalidate()
                tapAt(x, y)
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (sel != 0) send("lock")
            KeyEvent.KEYCODE_BUTTON_START -> settingsScroll.visibility = if (settingsScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            KeyEvent.KEYCODE_BUTTON_SELECT -> send("flip")
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (link != null && isPad(ev.source) && ev.action == MotionEvent.ACTION_MOVE) { readPad(ev); kick(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun readPad(ev: MotionEvent) {
        val d = ev.device
        fun has(a: Int) = d?.getMotionRange(a) != null
        fun trig(v: Float, a: Int): Float {
            val lo = d?.getMotionRange(a)?.min ?: 0f
            val n = if (lo < -0.5f) (v + 1f) / 2f else v
            return if (n < 0.05f) 0f else n.coerceAtMost(1f)
        }
        pLX = dead(ev.getAxisValue(MotionEvent.AXIS_X)); pLY = dead(ev.getAxisValue(MotionEvent.AXIS_Y))
        val zrz = has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)
        val rxry = has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)
        val stdTrig = has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE)
        if (stdTrig) {
            pLT = trig(max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)), MotionEvent.AXIS_LTRIGGER)
            pRT = trig(max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)), MotionEvent.AXIS_RTRIGGER)
            if (zrz) { pRX = dead(ev.getAxisValue(MotionEvent.AXIS_Z)); pRY = dead(ev.getAxisValue(MotionEvent.AXIS_RZ)) }
            else if (rxry) { pRX = dead(ev.getAxisValue(MotionEvent.AXIS_RX)); pRY = dead(ev.getAxisValue(MotionEvent.AXIS_RY)) }
        } else if (zrz && rxry) {
            pRX = dead(ev.getAxisValue(MotionEvent.AXIS_Z)); pRY = dead(ev.getAxisValue(MotionEvent.AXIS_RZ))
            pLT = trig(ev.getAxisValue(MotionEvent.AXIS_RX), MotionEvent.AXIS_RX); pRT = trig(ev.getAxisValue(MotionEvent.AXIS_RY), MotionEvent.AXIS_RY)
        } else if (rxry) {
            pRX = dead(ev.getAxisValue(MotionEvent.AXIS_RX)); pRY = dead(ev.getAxisValue(MotionEvent.AXIS_RY))
        }
        if (l2Key) pLT = 1f
        if (r2Key) pRT = 1f
        val hx = Math.round(ev.getAxisValue(MotionEvent.AXIS_HAT_X))
        val hy = Math.round(ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        if (has(MotionEvent.AXIS_HAT_X) && hx != hatX) hatX = hx
        if (hy != lastHatY) { lastHatY = hy; if (hy != 0) send("vert ${if (hy < 0) 1 else -1}") }
    }
    private var lastHatY = 0

    private fun kick() { if (!padRunning) { padRunning = true; lastT = SystemClock.elapsedRealtime(); ui.post(loop) } }

    private val loop = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - lastT) / 1000f).coerceIn(0.001f, 0.1f); lastT = now
            var busy = false
            if (pLX != 0f || pLY != 0f) {
                busy = true
                val r = imgRect()
                if (curX < 0f) { curX = r.centerX(); curY = r.centerY() }
                val sp = 0.9f * min(r.width(), r.height())
                curX = (curX + pLX * sp * dt).coerceIn(r.left, r.right); curY = (curY + pLY * sp * dt).coerceIn(r.top, r.bottom)
                touch.cx = curX; touch.cy = curY; touch.showCursor = true; touch.invalidate()
            }
            val adj = if (pRX != 0f) pRX * abs(pRX) else hatX * 0.2f
            val pull = pRT - pLT
            fun changed(a: Float, b: Float) = abs(a - b) > 0.01f
            if (changed(adj, sentAdj) || (adj != 0f && now - lastSend > 60)) { sentAdj = adj; send("adj $adj"); lastSend = now }
            if (changed(pull, sentPull)) { sentPull = pull; send("pull $pull") }
            if (changed(pRY, sentZv)) { sentZv = pRY; send("zv $pRY") }
            if (adj != 0f || pull != 0f || pRY != 0f) busy = true
            if (busy) ui.postDelayed(this, 30) else { padRunning = false; ui.postDelayed({ touch.showCursor = false; touch.invalidate() }, 3500) }
        }
    }
}
