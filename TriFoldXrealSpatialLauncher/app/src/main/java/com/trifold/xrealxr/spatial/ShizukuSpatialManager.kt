package com.trifold.xrealxr.spatial

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

enum class SpatialDisplayMode {
    PINNED_3DOF,        // World-locked in 3D space at fixed yaw/pitch/distance
    SMOOTH_FOLLOW,      // Follows gaze with smooth Slerp damping & deadzone
    CORNER_STAMP_0DOF   // Head-locked mini HUD stamp in a corner of vision
}

enum class StampCorner(val ndcOffsetX: Float, val ndcOffsetY: Float) {
    TOP_RIGHT(0.62f, 0.58f),
    TOP_LEFT(-0.62f, 0.58f),
    BOTTOM_RIGHT(0.62f, -0.58f),
    BOTTOM_LEFT(-0.62f, -0.58f)
}

data class SpatialAppWindow(
    val id: Int,
    val title: String,
    val packageName: String,
    val glTextureId: Int,
    val surfaceTexture: SurfaceTexture,
    val surface: Surface,
    val virtualDisplay: VirtualDisplay,
    val widthPx: Int = 1280,
    val heightPx: Int = 800,
    val densityDpi: Int = 240,
    // Spatial Transform State
    var mode: SpatialDisplayMode = SpatialDisplayMode.PINNED_3DOF,
    var stampCorner: StampCorner = StampCorner.TOP_RIGHT,
    var yawDeg: Float = 0f,           // Horizontal placement in 3DoF space (-180..180)
    var pitchDeg: Float = 0f,         // Vertical placement in 3DoF space (-60..60)
    var distanceMeters: Float = 2.4f, // Virtual screen distance (1.0m .. 8.0m)
    var scale: Float = 1.0f,          // Window size multiplier (0.25x .. 3.0x)
    var stampScale: Float = 0.32f,    // Size when in CORNER_STAMP_0DOF mode
    var curveAngleDeg: Float = 32f,   // 0 deg = flat quad, up to 90 deg cylindrical ultrawide curve
    var isUltrawide: Boolean = false  // True = 21:9 aspect (2.33:1), False = 16:10 aspect (1.6:1)
) {
    val displayId: Int get() = virtualDisplay.display.displayId
    val quadWidthMeters: Float get() = if (isUltrawide) 2.33f else 1.60f
    val quadHeightMeters: Float get() = 1.00f
}

class ShizukuSpatialManager(private val context: Context) {
    companion object {
        private const val TAG = "ShizukuSpatialManager"

        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val windows = CopyOnWriteArrayList<SpatialAppWindow>()
    private var nextWindowId = 1

    private var inputManagerService: Any? = null

    init {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/MotionEvent;", "Landroid/hardware/input/")
    }

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun getPrivilegedInputManager(): Any? {
        if (inputManagerService != null) return inputManagerService
        if (!isShizukuReady()) return null
        return try {
            val rawBinder: IBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            inputManagerService = asInterface.invoke(null, wrappedBinder)
            inputManagerService
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind IInputManager via Shizuku", e)
            null
        }
    }

    fun createAppWindow(
        packageName: String,
        title: String,
        glTextureId: Int,
        initialYawDeg: Float = 0f,
        initialPitchDeg: Float = 0f,
        ultrawide: Boolean = false
    ): SpatialAppWindow {
        val widthPx = if (ultrawide) 2560 else 1280
        val heightPx = if (ultrawide) 1080 else 800
        val densityDpi = 240

        val surfaceTexture = SurfaceTexture(glTextureId).apply {
            setDefaultBufferSize(widthPx, heightPx)
        }
        val surface = Surface(surfaceTexture)

        val baseFlags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

        val virtualDisplay = try {
            val privilegedFlags = baseFlags or
                    VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            displayManager.createVirtualDisplay(
                "XrealSpatial-$title-${nextWindowId}",
                widthPx, heightPx, densityDpi, surface, privilegedFlags
            )
        } catch (se: SecurityException) {
            displayManager.createVirtualDisplay(
                "XrealSpatial-$title-${nextWindowId}",
                widthPx, heightPx, densityDpi, surface,
                VIRTUAL_DISPLAY_FLAG_PUBLIC or VIRTUAL_DISPLAY_FLAG_PRESENTATION or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        }

        val window = SpatialAppWindow(
            id = nextWindowId++,
            title = title,
            packageName = packageName,
            glTextureId = glTextureId,
            surfaceTexture = surfaceTexture,
            surface = surface,
            virtualDisplay = virtualDisplay,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            yawDeg = initialYawDeg,
            pitchDeg = initialPitchDeg,
            curveAngleDeg = if (ultrawide) 48f else 24f,
            isUltrawide = ultrawide
        )
        windows.add(window)

        launchPackageOnDisplay(packageName, window.displayId)
        return window
    }

    fun launchPackageOnDisplay(packageName: String, displayId: Int) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        } ?: return

        try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(launchIntent, options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "Standard startActivity on display $displayId blocked; falling back to Shizuku shell", e)
            val component = launchIntent.component?.flattenToShortString() ?: return
            runShizukuShellCommand("am start --display $displayId --windowingMode 1 -n $component")
        }
    }

    fun injectTouchEvent(displayId: Int, action: Int, downTime: Long, xPx: Float, yPx: Float) {
        val eventTime = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = xPx
            y = yPx
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        })

        val motionEvent = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        try {
            val setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
            setDisplayIdMethod.isAccessible = true
            setDisplayIdMethod.invoke(motionEvent, displayId)

            val im = getPrivilegedInputManager()
            if (im != null) {
                val injectMethod = im.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                injectMethod.invoke(im, motionEvent, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject MotionEvent onto displayId=$displayId", e)
        } finally {
            motionEvent.recycle()
        }
    }

    private fun runShizukuShellCommand(cmd: String) {
        if (!isShizukuReady()) return
        try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcess.isAccessible = true
            val proc = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku shell execution error: $cmd", e)
        }
    }

    fun closeWindow(windowId: Int) {
        val win = windows.firstOrNull { it.id == windowId } ?: return
        windows.remove(win)
        win.virtualDisplay.release()
        win.surface.release()
        win.surfaceTexture.release()
    }
}
