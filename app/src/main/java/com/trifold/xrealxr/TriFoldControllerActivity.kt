package com.trifold.xrealxr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.trifold.xrealxr.hardware.PhoneLaserController
import com.trifold.xrealxr.hardware.XrealImuDriver
import com.trifold.xrealxr.render.GlassesSpatialPresentation
import com.trifold.xrealxr.spatial.*
import rikka.shizuku.Shizuku

class TriFoldControllerActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.trifold.xrealxr.USB_PERMISSION"
    }

    private lateinit var imuDriver: XrealImuDriver
    private lateinit var laserController: PhoneLaserController
    private lateinit var spatialManager: ShizukuSpatialManager
    private lateinit var displayManager: DisplayManager
    private var glassesPresentation: GlassesSpatialPresentation? = null

    private var activeWindow: SpatialAppWindow? = null
    private var touchDownTime: Long = 0L

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    imuDriver.start(device)
                    imuDriver.setDisplayMode(XrealImuDriver.DISPLAY_MODE_1080P_120HZ)
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attachToGlassesDisplayIfPresent()
        override fun onDisplayRemoved(displayId: Int) {
            if (glassesPresentation?.display?.displayId == displayId) {
                glassesPresentation?.dismiss()
                glassesPresentation = null
            }
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imuDriver = XrealImuDriver(this)
        laserController = PhoneLaserController(this).also { it.start() }
        spatialManager = ShizukuSpatialManager(this)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)

        requestShizukuIfNeeded()
        connectGlassesUsb()
        attachToGlassesDisplayIfPresent()

        setContentView(buildControllerLayout())
    }

    private fun requestShizukuIfNeeded() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
        }
    }

    private fun connectGlassesUsb() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = imuDriver.findGlassesDevice() ?: return
        if (usbManager.hasPermission(device)) {
            imuDriver.start(device)
            imuDriver.setDisplayMode(XrealImuDriver.DISPLAY_MODE_1080P_120HZ)
        } else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun attachToGlassesDisplayIfPresent() {
        if (glassesPresentation != null) return
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val externalDisplay: Display = presentationDisplays.firstOrNull() ?: return

        glassesPresentation = GlassesSpatialPresentation(
            this, externalDisplay, imuDriver, laserController, spatialManager
        ).also {
            it.show()
        }
    }

    private fun spawnNewAppWindow(packageName: String, label: String, ultrawide: Boolean = false) {
        val pres = glassesPresentation ?: run {
            Toast.makeText(this, "Plug in XREAL Air 2 Pro glasses first", Toast.LENGTH_SHORT).show()
            return
        }
        pres.glSurfaceView.queueEvent {
            val glTexId = pres.renderer.createExternalGlTexture()
            val offsetIndex = spatialManager.windows.size
            val yawOffset = (offsetIndex % 3 - 1) * 28f
            val win = spatialManager.createAppWindow(
                packageName = packageName,
                title = label,
                glTextureId = glTexId,
                initialYawDeg = yawOffset,
                ultrawide = ultrawide
            )
            activeWindow = win
            pres.renderer.activeWindowId = win.id
        }
    }

    private fun buildControllerLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Row 1: Recenter Head + Recenter Laser + Laser Toggle
        val calibrationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        calibrationRow.addView(MaterialButton(this).apply {
            text = "Recenter Head"
            setOnClickListener { imuDriver.recenter() }
        })
        calibrationRow.addView(MaterialButton(this).apply {
            text = "Recenter Laser"
            setOnClickListener { laserController.recenterLaser(imuDriver.getHeadOrientation()) }
        })
        calibrationRow.addView(MaterialButton(this).apply {
            text = "Input: 3DoF Laser"
            setOnClickListener {
                laserController.isLaserEnabled = !laserController.isLaserEnabled
                text = if (laserController.isLaserEnabled) "Input: 3DoF Laser" else "Input: Trackpad"
            }
        })
        root.addView(calibrationRow)

        // Row 2: Spatial Mode Switcher (Pinned 3DoF, Smooth Follow, Corner Stamp, Ultrawide)
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(MaterialButton(this).apply {
            text = "Pinned (3DoF)"
            setOnClickListener { activeWindow?.mode = SpatialDisplayMode.PINNED_3DOF }
        })
        modeRow.addView(MaterialButton(this).apply {
            text = "Smooth Follow"
            setOnClickListener { activeWindow?.mode = SpatialDisplayMode.SMOOTH_FOLLOW }
        })
        modeRow.addView(MaterialButton(this).apply {
            text = "Corner Stamp"
            setOnClickListener {
                activeWindow?.let { win ->
                    win.mode = SpatialDisplayMode.CORNER_STAMP_0DOF
                    val corners = StampCorner.values()
                    win.stampCorner = corners[(win.stampCorner.ordinal + 1) % corners.size]
                }
            }
        })
        modeRow.addView(MaterialButton(this).apply {
            text = "Toggle 21:9 Ultrawide"
            setOnClickListener {
                activeWindow?.let { win ->
                    win.isUltrawide = !win.isUltrawide
                    if (win.isUltrawide && win.curveAngleDeg < 35f) win.curveAngleDeg = 48f
                }
            }
        })
        root.addView(modeRow)

        // Sliders: Distance (1.0m - 8.0m), Scale (0.25x - 3.0x), and Cylinder Curvature (0 deg - 90 deg)
        val distLabel = TextView(this).apply { text = "Screen Distance (1.0m - 8.0m)" }
        val distSlider = Slider(this).apply {
            valueFrom = 1.0f; valueTo = 8.0f; value = 2.4f
            addOnChangeListener { _, value, _ -> activeWindow?.distanceMeters = value }
        }
        val scaleLabel = TextView(this).apply { text = "Screen Scale (0.25x - 3.0x)" }
        val scaleSlider = Slider(this).apply {
            valueFrom = 0.25f; valueTo = 3.0f; value = 1.0f
            addOnChangeListener { _, value, _ ->
                activeWindow?.scale = value
                activeWindow?.stampScale = (value * 0.32f).coerceIn(0.15f, 0.75f)
            }
        }
        val curveLabel = TextView(this).apply { text = "Curved Screen Wrap Angle (0° Flat - 90° Curved Cylinder)" }
        val curveSlider = Slider(this).apply {
            valueFrom = 0.0f; valueTo = 90.0f; value = 32.0f
            addOnChangeListener { _, value, _ -> activeWindow?.curveAngleDeg = value }
        }
        root.addView(distLabel); root.addView(distSlider)
        root.addView(scaleLabel); root.addView(scaleSlider)
        root.addView(curveLabel); root.addView(curveSlider)

        // Row 3: Quick App Spawner
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sampleApps = listOf(
            "com.android.chrome" to "Chrome",
            "com.google.android.youtube" to "YouTube",
            "com.spotify.music" to "Spotify",
            "com.discord" to "Discord"
        )
        for ((pkg, name) in sampleApps) {
            appRow.addView(MaterialButton(this).apply {
                text = "+ $name"
                setOnClickListener { spawnNewAppWindow(pkg, name, ultrawide = (name == "Chrome")) }
            })
        }
        root.addView(appRow)

        // Giant Multi-Touch Spatial Trackpad & 3DoF Laser Trigger Surface
        val trackpad = FrameLayout(this).apply {
            setBackgroundColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(TextView(context).apply {
                text = "3DoF Laser Mode: Point TriFold at any floating window & tap/drag here to click/scroll\n" +
                       "Trackpad Mode: 1-Finger Touch interacts with app | 2-Finger Drag moves window in 3D space"
                gravity = Gravity.CENTER
            })

            var lastTwoFingerX = 0f
            var lastTwoFingerY = 0f

            setOnTouchListener { v, event ->
                val pres = glassesPresentation
                val laserHit = pres?.renderer?.latestLaserHit
                val win = (if (laserController.isLaserEnabled && laserHit != null) laserHit.window else activeWindow)
                    ?: return@setOnTouchListener true

                if (event.pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) * 0.5f
                    val cy = (event.getY(0) + event.getY(1)) * 0.5f
                    if (event.actionMasked == MotionEvent.ACTION_MOVE && lastTwoFingerX != 0f) {
                        win.yawDeg += (cx - lastTwoFingerX) * 0.08f
                        win.pitchDeg = (win.pitchDeg - (cy - lastTwoFingerY) * 0.08f).coerceIn(-55f, 55f)
                    }
                    lastTwoFingerX = cx
                    lastTwoFingerY = cy
                    return@setOnTouchListener true
                } else {
                    lastTwoFingerX = 0f
                    lastTwoFingerY = 0f
                }

                // Determine target pixel coordinates from 3DoF Laser Raycast Hit OR Trackpad surface
                val targetX: Float
                val targetY: Float
                if (laserController.isLaserEnabled && laserHit != null) {
                    targetX = laserHit.pixelX
                    targetY = laserHit.pixelY
                } else {
                    targetX = (event.x / v.width.coerceAtLeast(1)) * win.widthPx
                    targetY = (event.y / v.height.coerceAtLeast(1)) * win.heightPx
                }

                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchDownTime = SystemClock.uptimeMillis()
                }
                spatialManager.injectTouchEvent(
                    displayId = win.displayId,
                    action = event.actionMasked,
                    downTime = touchDownTime,
                    xPx = targetX,
                    yPx = targetY
                )
                true
            }
        }
        root.addView(trackpad)

        return root
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        displayManager.unregisterDisplayListener(displayListener)
        glassesPresentation?.dismiss()
        laserController.stop()
        imuDriver.stop()
    }
}
