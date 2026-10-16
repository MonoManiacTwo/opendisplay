package app.opendisplay.android.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.opendisplay.android.R
import app.opendisplay.android.net.Advertiser
import app.opendisplay.android.net.ReceiverServer
import app.opendisplay.android.protocol.AnnexB
import app.opendisplay.android.protocol.Codec
import app.opendisplay.android.protocol.Messages
import app.opendisplay.android.protocol.Panel
import app.opendisplay.android.protocol.PenInput
import app.opendisplay.android.protocol.TouchGestures
import app.opendisplay.android.protocol.VideoCap
import app.opendisplay.android.protocol.WireProtocol
import app.opendisplay.android.video.DecoderCaps
import app.opendisplay.android.video.StreamStats
import app.opendisplay.android.video.VideoDecoder
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The whole receiver UI: the stream fills the screen, the sender's cursor
 * floats above it, and an idle panel says how to connect when nothing streams.
 */
class MainActivity : Activity(), ReceiverServer.Listener, VideoDecoder.Callbacks {
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var videoFrame: VideoFrame
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: CursorView
    private lateinit var blank: View
    private lateinit var idlePanel: LinearLayout
    private lateinit var idleTitle: TextView
    private lateinit var idleDetail: TextView
    private lateinit var statsText: TextView

    private lateinit var server: ReceiverServer
    private lateinit var advertiser: Advertiser
    private lateinit var decoder: VideoDecoder
    private lateinit var gestures: TouchGestures
    private lateinit var pen: PenInput
    private val stats = StreamStats()

    private lateinit var installId: String
    private lateinit var wifiLock: android.net.wifi.WifiManager.WifiLock

    // Read by network threads to build `hello`.
    @Volatile private var panel = Panel(0, 0, 1.0)
    @Volatile private var videoCaps: List<VideoCap> = emptyList()
    @Volatile private var displayMaxFrameRate = 60
    @Volatile private var deviceKind = "Android phone"
    @Volatile private var transport = "WiFi"
    @Volatile private var streamInfo: String? = null
    @Volatile private var senderAsleep = false
    @Volatile private var senderHealth: JSONObject? = null
    @Volatile private var unsupportedCodec = false

    // Main-thread state.
    private var connected = false
    private var hasPicture = false
    @Volatile private var blockingMessage: String? = null
    private var overlayVisible = false
    private var multiTouchToggle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installId = DeviceIdentity.installId(this)
        wifiLock = createWifiLock()
        buildViews()
        setContentView(root)
        enterImmersive()

        decoder = VideoDecoder(this).also { it.start() }
        server = ReceiverServer(this, ::buildHello)
        advertiser = Advertiser(this)
        val slop = 10 * resources.displayMetrics.density
        gestures = TouchGestures(
            output = object : TouchGestures.Output {
                override fun touch(phase: String, x: Double, y: Double) = server.sendTouch(phase, x, y)
                override fun scroll(dx: Double, dy: Double) = server.sendScroll(dx, dy)
            },
            scheduler = gestureScheduler,
            dragSlopPx = slop,
        )
        pen = PenInput(
            output = object : PenInput.Output {
                override fun pencil(phase: String, s: PenInput.Sample) = server.sendPencil(phase, s)
                override fun proximity(entering: Boolean, x: Double, y: Double) =
                    server.sendProximity(entering, x, y)
                override fun touch(phase: String, x: Double, y: Double) = server.sendTouch(phase, x, y)
            },
            scheduler = gestureScheduler,
            senderPv = { server.senderPv },
        )
        updatePanel()
        main.post(statsTick)
    }

    override fun onStart() {
        super.onStart()
        server.start()
        advertiser.register(DeviceIdentity.serviceName(this), installId)
        refreshIdle()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(resendHello)
        advertiser.unregister()
        // Locked or backgrounded: the sender keeps waiting for us to come
        // back (`sleeping`). Finishing ends the session for good (`closing`).
        server.stop(if (isFinishing) "closing" else "sleeping")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wifiLock.isHeld) wifiLock.release()
        main.removeCallbacksAndMessages(null)
        decoder.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // region Views

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame = VideoFrame(this)
        surfaceView = SurfaceView(this)
        cursorView = CursorView(this)
        videoFrame.addView(surfaceView)
        videoFrame.addView(cursorView)
        root.addView(videoFrame, FrameLayout.LayoutParams(MATCH, MATCH))

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = decoder.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = decoder.detachSurface()
        })

        // Covers a stale picture while the sender sleeps (6.2 `sleeping`).
        blank = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(blank, FrameLayout.LayoutParams(MATCH, MATCH))

        val pad = dp(24)
        idlePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        idleTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        idleDetail = TextView(this).apply {
            setTextColor(Color.rgb(170, 170, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        val logo = android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        idlePanel.addView(logo, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(20) })
        idlePanel.addView(idleTitle)
        idlePanel.addView(idleDetail, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        root.addView(idlePanel, FrameLayout.LayoutParams(MATCH, MATCH))
        idlePanel.setOnLongClickListener {
            showRenameDialog()
            true
        }

        statsText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        root.addView(statsText, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(32)
            leftMargin = dp(12)
        })

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) updatePanel()
        }
        root.setOnTouchListener { _, event -> onTouch(event) }
        root.setOnGenericMotionListener { _, event -> onHover(event) }
    }

    /** The Bonjour name is what the Mac lists; let people make it recognizable. */
    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(DeviceIdentity.serviceName(this@MainActivity))
            setSingleLine()
            selectAll()
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setMessage(R.string.rename_message)
            .setView(frame)
            .setPositiveButton(R.string.rename_save) { _, _ ->
                DeviceIdentity.setServiceName(this, input.text.toString())
                advertiser.register(DeviceIdentity.serviceName(this), installId)
                refreshIdle()
            }
            .setNeutralButton(R.string.rename_reset) { _, _ ->
                DeviceIdentity.setServiceName(this, null)
                advertiser.register(DeviceIdentity.serviceName(this), installId)
                refreshIdle()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /** Main thread: reflect connection/picture/sleep state in the views. */
    private fun refreshIdle() {
        val streaming = connected && hasPicture && !senderAsleep && blockingMessage == null
        idlePanel.visibility = if (streaming || (connected && senderAsleep)) View.GONE else View.VISIBLE
        blank.visibility = if (connected && senderAsleep) View.VISIBLE else View.GONE
        cursorView.visibility = if (streaming) View.VISIBLE else View.INVISIBLE
        // Input is gated on streaming: a gesture cut off here must still
        // release the button (and drop a pending hold) on the sender.
        if (!streaming) gestures.cancel()
        val keepOn = connected && !senderAsleep
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Wi-Fi power save parks the radio between beacons: on a Pixel the
        // RTT swung 60-240 ms. Hold the radio awake while a Wi-Fi session runs.
        val wantLock = keepOn && transport == "WiFi"
        if (wantLock && !wifiLock.isHeld) wifiLock.acquire()
        if (!wantLock && wifiLock.isHeld) wifiLock.release()

        val name = advertiser.registeredName ?: DeviceIdentity.serviceName(this)
        val message = blockingMessage
        when {
            message != null -> {
                idleTitle.text = getString(R.string.update_needed)
                idleDetail.text = message
            }
            connected -> {
                idleTitle.text = getString(R.string.connecting)
                idleDetail.text = getString(R.string.waiting_for_picture, transport)
            }
            else -> {
                idleTitle.text = getString(R.string.ready_title)
                val addrs = DeviceIdentity.ipv4Addresses().joinToString("  ")
                idleDetail.text = getString(R.string.ready_detail, name) +
                    (if (addrs.isNotEmpty()) "\n\n" + getString(R.string.addresses, addrs) else "") +
                    "\n\n" + getString(R.string.rename_hint)
            }
        }
    }

    // endregion

    @Suppress("DEPRECATION")
    private fun createWifiLock(): android.net.wifi.WifiManager.WifiLock {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        return wifi.createWifiLock(mode, "OpenDisplay:stream").apply { setReferenceCounted(false) }
    }

    // region Panel and hello

    private fun updatePanel() {
        val metrics = resources.displayMetrics
        var w = root.width
        var h = root.height
        if (w <= 0 || h <= 0) {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            w = size.x
            h = size.y
        }
        val next = Panel(w, h, (metrics.density * 1000).roundToInt() / 1000.0)
        deviceKind = DeviceIdentity.deviceKind(this)
        displayMaxFrameRate = maxRefreshRate()
        if (next == panel) return
        panel = next
        videoCaps = DecoderCaps.videoCaps(w, h)
        Log.i(TAG, "panel ${w}x$h @${next.scale}, caps $videoCaps")
        // 6.1: re-send hello on the live connection whenever the panel
        // changes. Settle first: locking rotates to the lock screen's
        // orientation just before onStop, and that hello would make the
        // sender rebuild its display for a session about to sleep.
        main.removeCallbacks(resendHello)
        if (connected) main.postDelayed(resendHello, 250)
    }

    private val resendHello = Runnable { Thread { server.sendHello() }.start() }

    @Suppress("DEPRECATION")
    private fun maxRefreshRate(): Int {
        val display = windowManager.defaultDisplay
        val rate = if (Build.VERSION.SDK_INT >= 23) {
            display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        } else {
            display.supportedRefreshRates.maxOrNull() ?: display.refreshRate
        }
        return rate.roundToInt().coerceAtLeast(30)
    }

    private fun buildHello(cursorPort: Int?): JSONObject =
        Messages.hello(panel, installId, deviceKind, displayMaxFrameRate, videoCaps, cursorPort)

    // endregion

    // region Input

    private val gestureScheduler = TouchGestures.Scheduler { delay, action ->
        val r = Runnable(action)
        main.postDelayed(r, delay)
        TouchGestures.Cancellable { main.removeCallbacks(r) }
    }

    private fun isStylus(event: MotionEvent, index: Int = 0): Boolean {
        val tool = event.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun penSample(event: MotionEvent, history: Int = -1): PenInput.Sample {
        fun axis(a: Int) = if (history < 0) event.getAxisValue(a, 0) else event.getHistoricalAxisValue(a, 0, history)
        val p = point(axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
        return PenInput.Sample.fromAxes(p.nx, p.ny, axis(MotionEvent.AXIS_PRESSURE),
            axis(MotionEvent.AXIS_ORIENTATION), axis(MotionEvent.AXIS_TILT))
    }

    private fun onPen(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestures.cancel() // a resting finger must not keep a press open
                pen.down(penSample(event))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) pen.move(penSample(event, h))
                pen.move(penSample(event))
            }
            MotionEvent.ACTION_UP -> pen.up(penSample(event))
            MotionEvent.ACTION_CANCEL -> pen.up(penSample(event), cancelled = true)
        }
        return true
    }

    /** Stylus hover arrives as generic motion, not touch. */
    private fun onHover(event: MotionEvent): Boolean {
        if (!connected || !hasPicture || senderAsleep || !isStylus(event)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> pen.hover(penSample(event))
            MotionEvent.ACTION_HOVER_EXIT -> penSample(event).let { pen.hoverExit(it.x, it.y) }
        }
        return true
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (!connected || !hasPicture || senderAsleep || blockingMessage != null) return true
        val action = event.actionMasked
        // Three fingers toggle the perf overlay and never reach the sender.
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 3) {
            gestures.cancel()
            multiTouchToggle = true
            overlayVisible = !overlayVisible
            statsText.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            return true
        }
        if (multiTouchToggle) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) multiTouchToggle = false
            return true
        }
        // The pen owns the gesture it starts; fingers wait while it is down
        // (palm rejection, as on iOS).
        if (isStylus(event) || pen.penDown) {
            return if (isStylus(event)) onPen(event) else true
        }
        val primary = point(event.getX(0), event.getY(0))
        when (action) {
            MotionEvent.ACTION_DOWN -> gestures.down(primary)
            MotionEvent.ACTION_POINTER_DOWN -> gestures.pointerDown(event.pointerCount, centroid(event))
            MotionEvent.ACTION_POINTER_UP -> gestures.pointerUp()
            MotionEvent.ACTION_MOVE -> {
                // Android batches touch samples per frame; forward each one so
                // the sender gets the full-rate drag, like the iOS receiver.
                if (event.pointerCount == 1) {
                    for (h in 0 until event.historySize) {
                        val past = point(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
                        gestures.move(1, past, past, videoFrame.videoScale)
                    }
                }
                gestures.move(event.pointerCount, primary, centroid(event), videoFrame.videoScale)
            }
            MotionEvent.ACTION_UP -> gestures.up(primary)
            MotionEvent.ACTION_CANCEL -> gestures.cancel()
        }
        return true
    }

    /** Root coordinates to normalized video coordinates (7: top-left origin). */
    private fun point(x: Float, y: Float): TouchGestures.Point {
        val left = videoFrame.left + videoFrame.videoLeft
        val top = videoFrame.top + videoFrame.videoTop
        val w = videoFrame.videoRectWidth.coerceAtLeast(1)
        val h = videoFrame.videoRectHeight.coerceAtLeast(1)
        return TouchGestures.Point(x, y, ((x - left) / w).toDouble().coerceIn(0.0, 1.0),
            ((y - top) / h).toDouble().coerceIn(0.0, 1.0))
    }

    private fun centroid(event: MotionEvent): TouchGestures.Point {
        var sx = 0f
        var sy = 0f
        var n = 0
        val lifting = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            sx += event.getX(i)
            sy += event.getY(i)
            n++
        }
        if (n == 0) return point(event.getX(0), event.getY(0))
        return point(sx / n, sy / n)
    }

    // endregion

    // region ReceiverServer.Listener (network threads)

    override fun onSessionStarted(transport: String) {
        this.transport = transport
        senderAsleep = false
        unsupportedCodec = false
        streamInfo = null
        hasPictureFlag = false
        decoder.reset()
        main.post {
            connected = true
            hasPicture = false
            blockingMessage = null
            // A previous sender's cursor must not ghost over the new one.
            cursorView.clear()
            refreshIdle()
        }
    }

    override fun onSessionEnded() {
        hasPictureFlag = false
        decoder.reset()
        main.post {
            connected = false
            hasPicture = false
            cursorView.clear()
            refreshIdle()
        }
    }

    override fun onVideoFrame(payload: ByteArray) {
        if (blockingMessage != null || unsupportedCodec) return
        val unit = AnnexB.parse(payload) ?: return
        stats.onFrameReceived(payload.size)
        if (senderAsleep) {
            // The next video frame means the sender is back (6.2).
            senderAsleep = false
            main.post { refreshIdle() }
        }
        decoder.submit(unit)
    }

    override fun onStreamConfig(codec: Codec?, codecToken: String, width: Int, height: Int, fps: Int) {
        Log.i(TAG, "streamConfig $codecToken ${width}x$height @$fps")
        val offered = codec != null && videoCaps.any { it.codec == codec }
        // Decoding it as the previous codec would only produce garbage and a
        // keyframe storm: ignore video until a stream we can decode is announced.
        unsupportedCodec = codec == null || !offered
        if (codec == null || !offered) {
            Log.w(TAG, "sender selected a codec we did not offer: $codecToken")
            return
        }
        streamInfo = "${codec.wire.uppercase()} ${width}x$height @$fps"
        decoder.setStream(codec, width, height)
    }

    override fun onCursor(x: Double, y: Double, visible: Boolean) {
        main.post { cursorView.update(x, y, visible) }
    }

    override fun onCursorImage(png: ByteArray, nw: Double, nh: Double, ax: Double, ay: Double) {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        main.post { cursorView.setSprite(bitmap, nw, nh, ax, ay) }
    }

    override fun onSenderSleeping() {
        Log.i(TAG, "sender went to sleep; blanking until it wakes")
        senderAsleep = true
        main.post { refreshIdle() }
    }

    override fun onSenderVersion(pv: Int) {
        if (pv < WireProtocol.MIN_SUPPORTED_PEER) {
            main.post {
                blockingMessage = getString(R.string.update_sender)
                refreshIdle()
            }
        }
    }

    override fun onUpdateRequired(message: String, store: String?) {
        main.post {
            blockingMessage = message
            refreshIdle()
        }
    }

    override fun onSenderHealth(obj: JSONObject) {
        senderHealth = obj
    }

    override fun statsSnapshot(): JSONObject =
        stats.toJson(transport, server.clock.lastRttMs, server.clock.offsetMs != null,
            decoder.codecName, decoder.droppedFrames)

    // endregion

    // region VideoDecoder.Callbacks (decoder threads)

    override fun requestKeyframe() = server.requestKeyframe()

    override fun onVideoSize(width: Int, height: Int) {
        main.post { videoFrame.setVideoSize(width, height) }
    }

    override fun onFrameRendered(receivedNanos: Long, captureMs: Double?) {
        val decodeMs = (SystemClock.elapsedRealtimeNanos() - receivedNanos) / 1e6
        val e2e = captureMs?.let { cap ->
            server.clock.senderNow(System.currentTimeMillis().toDouble())?.minus(cap)
        }
        stats.onFrameRendered(decodeMs, e2e)
        if (!hasPictureFlag) {
            hasPictureFlag = true
            main.post {
                hasPicture = true
                refreshIdle()
            }
        }
    }

    @Volatile private var hasPictureFlag = false

    override fun onDecoderError(message: String) {
        Log.w(TAG, message)
        main.post {
            idleDetail.text = message
        }
    }

    // endregion

    private val statsTick = object : Runnable {
        override fun run() {
            val s = stats.roll()
            if (overlayVisible) {
                val health = senderHealth
                statsText.text = buildString {
                    append(streamInfo ?: "stream ?").append("  ").append(transport).append('\n')
                    append("fps %.0f  %.1f Mb/s\n".format(s.fps, s.mbps))
                    append("e2e p50 %s  p95 %s ms\n".format(fmt(s.e2e50), fmt(s.e2e95)))
                    append("decode p50 %s ms  rtt %s ms\n".format(fmt(s.dec50), fmt(server.clock.lastRttMs)))
                    append("decoder ${decoder.codecName ?: "-"}  drops ${decoder.droppedFrames}")
                    health?.let {
                        append("\nsender cap ${it.optInt("capFps")} fps  enc drops ${it.optInt("encDrops")}" +
                            "  net drops ${it.optInt("netDrops")}")
                    }
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun fmt(v: Double?) = v?.let { "%.0f".format(it) } ?: "-"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "OpenDisplay"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
