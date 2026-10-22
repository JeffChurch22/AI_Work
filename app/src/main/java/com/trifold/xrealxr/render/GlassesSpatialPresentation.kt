package com.trifold.xrealxr.render

import android.app.Presentation
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.Display
import com.trifold.xrealxr.hardware.PhoneLaserController
import com.trifold.xrealxr.hardware.Quaternion
import com.trifold.xrealxr.hardware.XrealImuDriver
import com.trifold.xrealxr.spatial.ShizukuSpatialManager
import com.trifold.xrealxr.spatial.SpatialAppWindow
import com.trifold.xrealxr.spatial.SpatialDisplayMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class GlassesSpatialPresentation(
    context: Context,
    display: Display,
    private val imuDriver: XrealImuDriver,
    private val laserController: PhoneLaserController,
    private val spatialManager: ShizukuSpatialManager
) : Presentation(context, display) {

    lateinit var glSurfaceView: GLSurfaceView
    lateinit var renderer: SpatialRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glSurfaceView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            renderer = SpatialRenderer(imuDriver, laserController, spatialManager)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glSurfaceView)
    }
}

data class RaycastHit(
    val window: SpatialAppWindow,
    val normalizedU: Float,
    val normalizedV: Float,
    val pixelX: Float,
    val pixelY: Float,
    val worldHitPoint: FloatArray,
    val distance: Float
)

/**
 * OpenGL ES 3.0 3DoF Spatial Compositor with:
 * 1. Curved Ultrawide Cylindrical Mesh Generation (0..90 deg continuous curvature)
 * 2. 3DoF Phone-as-Laser-Pointer Raycasting against Curved & Flat Windows
 * 3. Live 3D Laser Beam + Cyan Reticle Overlay
 */
class SpatialRenderer(
    private val imuDriver: XrealImuDriver,
    private val laserController: PhoneLaserController,
    private val spatialManager: ShizukuSpatialManager
) : GLSurfaceView.Renderer {

    companion object {
        private const val MESH_SLICES = 48
        private const val VERTEX_COUNT = (MESH_SLICES + 1) * 2
    }

    private var windowShaderProgram = 0
    private var uMvpMatrixLoc = 0
    private var uTextureLoc = 0
    private var uFocusedLoc = 0
    private var uCursorUvLoc = 0

    private var laserShaderProgram = 0
    private var uLaserVpMatrixLoc = 0
    private var uLaserColorLoc = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val invModelMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var smoothFollowQuat = Quaternion()

    @Volatile var activeWindowId: Int = -1
    @Volatile var latestLaserHit: RaycastHit? = null

    // Dynamic curved mesh buffer: (X, Y, Z, U, V) * VERTEX_COUNT
    private val meshVertices = FloatArray(VERTEX_COUNT * 5)
    private val meshBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(meshVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // 3D Laser beam line buffer: 2 endpoints * (X, Y, Z)
    private val laserLineVertices = FloatArray(6)
    private val laserLineBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(laserLineVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f) // Pure black = transparent on XREAL Micro-OLED
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // Renders the external VirtualDisplay texture + focused border + live 3DoF laser reticle dot
        val fragmentShaderCode = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform int uFocused;
            uniform vec2 uCursorUv;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTexture, vTexCoord);

                // Draw glowing cyan laser reticle on the window surface if hit by 3DoF phone pointer
                if (uCursorUv.x >= 0.0 && uCursorUv.y >= 0.0) {
                    float dist = distance(vTexCoord, uCursorUv);
                    if (dist < 0.009) {
                        fragColor = vec4(0.0, 0.95, 1.0, 1.0);
                        return;
                    } else if (dist < 0.015) {
                        fragColor = mix(tex, vec4(0.0, 0.85, 1.0, 1.0), 0.7);
                        return;
                    }
                }

                // Draw subtle glowing border around the focused window
                if (uFocused == 1) {
                    if (vTexCoord.x < 0.006 || vTexCoord.x > 0.994 || vTexCoord.y < 0.010 || vTexCoord.y > 0.990) {
                        fragColor = vec4(0.22, 0.74, 0.97, 1.0);
                        return;
                    }
                }
                fragColor = tex;
            }
        """.trimIndent()

        windowShaderProgram = buildProgram(vertexShaderCode, fragmentShaderCode)
        uMvpMatrixLoc = GLES30.glGetUniformLocation(windowShaderProgram, "uMvpMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(windowShaderProgram, "uTexture")
        uFocusedLoc = GLES30.glGetUniformLocation(windowShaderProgram, "uFocused")
        uCursorUvLoc = GLES30.glGetUniformLocation(windowShaderProgram, "uCursorUv")

        val laserVs = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uVpMatrix;
            void main() {
                gl_Position = uVpMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val laserFs = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """.trimIndent()

        laserShaderProgram = buildProgram(laserVs, laserFs)
        uLaserVpMatrixLoc = GLES30.glGetUniformLocation(laserShaderProgram, "uVpMatrix")
        uLaserColorLoc = GLES30.glGetUniformLocation(laserShaderProgram, "uColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        // XREAL Air 2 Pro has a 46-degree diagonal FOV (~26.5-degree vertical FOV)
        Matrix.perspectiveM(projectionMatrix, 0, 26.5f, aspect, 0.1f, 50.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(windowShaderProgram)

        val headQuat = imuDriver.getHeadOrientation()
        headQuat.conjugate().toMatrix4x4(viewMatrix)

        // Update Smooth Follow quaternion (deadzone 3.0 deg + smooth slerp)
        val dot = abs(
            smoothFollowQuat.w * headQuat.w +
            smoothFollowQuat.x * headQuat.x +
            smoothFollowQuat.y * headQuat.y +
            smoothFollowQuat.z * headQuat.z
        )
        val angleDeg = 2f * acos(dot.coerceIn(0f, 1f)) * (180f / Math.PI.toFloat())
        if (angleDeg > 3.0f) {
            smoothFollowQuat = Quaternion.slerp(smoothFollowQuat, headQuat, 0.06f)
        }

        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Raycast from the Galaxy Z TriFold 3DoF laser pointer against all spatial windows
        val rayOrigin = floatArrayOf(0.12f, -0.22f, 0.0f) // Ergonomic hand/controller origin offset
        val rayDir = laserController.getLaserRayDirection()
        var closestHit: RaycastHit? = null

        for (win in spatialManager.windows) {
            win.surfaceTexture.updateTexImage()
            computeWindowModelMatrix(win, modelMatrix)

            if (laserController.isLaserEnabled && win.mode != SpatialDisplayMode.CORNER_STAMP_0DOF) {
                val hit = intersectRayWithWindow(rayOrigin, rayDir, win, modelMatrix)
                if (hit != null && (closestHit == null || hit.distance < closestHit.distance)) {
                    closestHit = hit
                }
            }
        }

        latestLaserHit = closestHit
        if (closestHit != null) {
            activeWindowId = closestHit.window.id
        }

        // Render all floating app windows (flat or curved cylinder meshes)
        for (win in spatialManager.windows) {
            computeWindowModelMatrix(win, modelMatrix)
            if (win.mode == SpatialDisplayMode.CORNER_STAMP_0DOF) {
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
            } else {
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            }

            val hitOnThisWindow = if (closestHit?.window?.id == win.id) closestHit else null
            updateCurvedCylinderMesh(win)
            drawWindowMesh(win, mvpMatrix, hitOnThisWindow)
        }

        // Render 3D Laser Beam from hand origin to hit point (or 4m forward)
        if (laserController.isLaserEnabled) {
            drawLaserBeam(rayOrigin, rayDir, closestHit)
        }
    }

    private fun computeWindowModelMatrix(win: SpatialAppWindow, outModel: FloatArray) {
        Matrix.setIdentityM(outModel, 0)
        when (win.mode) {
            SpatialDisplayMode.PINNED_3DOF -> {
                Matrix.rotateM(outModel, 0, -win.yawDeg, 0f, 1f, 0f)
                Matrix.rotateM(outModel, 0, win.pitchDeg, 1f, 0f, 0f)
                Matrix.translateM(outModel, 0, 0f, 0f, -win.distanceMeters)
                Matrix.scaleM(outModel, 0, win.scale, win.scale, win.scale)
            }
            SpatialDisplayMode.SMOOTH_FOLLOW -> {
                val followMat = FloatArray(16)
                smoothFollowQuat.toMatrix4x4(followMat)
                val localTrans = FloatArray(16)
                Matrix.setIdentityM(localTrans, 0)
                Matrix.translateM(localTrans, 0, 0f, 0f, -win.distanceMeters)
                Matrix.scaleM(localTrans, 0, win.scale, win.scale, win.scale)
                Matrix.multiplyMM(outModel, 0, followMat, 0, localTrans, 0)
            }
            SpatialDisplayMode.CORNER_STAMP_0DOF -> {
                val corner = win.stampCorner
                Matrix.translateM(outModel, 0, corner.ndcOffsetX, corner.ndcOffsetY, -1.8f)
                Matrix.scaleM(outModel, 0, win.stampScale, win.stampScale, 1f)
            }
        }
    }

    /**
     * Dynamically populates a tessellated cylindrical arc mesh (48 slices) for curved ultrawide rendering.
     * When curveAngleDeg == 0 (or in Corner Stamp mode), falls back to a flat planar surface.
     */
    private fun updateCurvedCylinderMesh(win: SpatialAppWindow) {
        val width = win.quadWidthMeters
        val halfHeight = win.quadHeightMeters * 0.5f
        val effectiveCurveDeg = if (win.mode == SpatialDisplayMode.CORNER_STAMP_0DOF) 0f else win.curveAngleDeg
        val thetaRad = (effectiveCurveDeg * Math.PI / 180.0).toFloat()

        var idx = 0
        for (i in 0..MESH_SLICES) {
            val u = i.toFloat() / MESH_SLICES.toFloat()
            val x: Float
            val z: Float

            if (thetaRad < 0.02f) {
                // Flat screen plane
                x = (u - 0.5f) * width
                z = 0f
            } else {
                // Cylindrical arc of radius R = width / thetaRad, tangent to Z = 0 at center (u = 0.5)
                val radius = width / thetaRad
                val alpha = (u - 0.5f) * thetaRad
                x = radius * sin(alpha)
                z = radius * (1f - cos(alpha)) // Edges curve toward the viewer (+Z)
            }

            // Top vertex (X, +Y, Z, U, V=0)
            meshVertices[idx++] = x
            meshVertices[idx++] = halfHeight
            meshVertices[idx++] = z
            meshVertices[idx++] = u
            meshVertices[idx++] = 0f

            // Bottom vertex (X, -Y, Z, U, V=1)
            meshVertices[idx++] = x
            meshVertices[idx++] = -halfHeight
            meshVertices[idx++] = z
            meshVertices[idx++] = u
            meshVertices[idx++] = 1f
        }

        meshBuffer.position(0)
        meshBuffer.put(meshVertices)
        meshBuffer.position(0)
    }

    /**
     * Analytical 3D raycast intersection against both flat and cylindrical curved windows.
     */
    private fun intersectRayWithWindow(
        rayOriginWorld: FloatArray,
        rayDirWorld: FloatArray,
        win: SpatialAppWindow,
        windowModelMatrix: FloatArray
    ): RaycastHit? {
        if (!Matrix.invertM(invModelMatrix, 0, windowModelMatrix, 0)) return null

        // Transform ray origin & direction into window-local space
        val orig4 = floatArrayOf(rayOriginWorld[0], rayOriginWorld[1], rayOriginWorld[2], 1f)
        val dir4 = floatArrayOf(rayDirWorld[0], rayDirWorld[1], rayDirWorld[2], 0f)
        val localOrig = FloatArray(4)
        val localDir = FloatArray(4)
        Matrix.multiplyMV(localOrig, 0, invModelMatrix, 0, orig4, 0)
        Matrix.multiplyMV(localDir, 0, invModelMatrix, 0, dir4, 0)

        val ox = localOrig[0]; val oy = localOrig[1]; val oz = localOrig[2]
        val dx = localDir[0];  val dy = localDir[1];  val dz = localDir[2]

        val width = win.quadWidthMeters
        val halfHeight = win.quadHeightMeters * 0.5f
        val thetaRad = (win.curveAngleDeg * Math.PI / 180.0).toFloat()

        var tHit = -1f
        var uHit = -1f
        var vHit = -1f

        if (thetaRad < 0.02f) {
            // Ray-Plane intersection (z = 0)
            if (abs(dz) > 1e-5f) {
                val t = -oz / dz
                if (t > 0.05f) {
                    val hx = ox + t * dx
                    val hy = oy + t * dy
                    val u = (hx / width) + 0.5f
                    val v = 0.5f - (hy / (halfHeight * 2f))
                    if (u in 0f..1f && v in 0f..1f) {
                        tHit = t; uHit = u; vHit = v
                    }
                }
            }
        } else {
            // Ray-Cylinder intersection: x^2 + (z - R)^2 = R^2 in local XZ plane
            val radius = width / thetaRad
            val ozShifted = oz - radius
            val a = dx * dx + dz * dz
            val b = 2f * (ox * dx + ozShifted * dz)
            val c = ox * ox + ozShifted * ozShifted - radius * radius
            val disc = b * b - 4f * a * c
            if (disc >= 0f && a > 1e-6f) {
                val sqrtDisc = sqrt(disc)
                val t1 = (-b - sqrtDisc) / (2f * a)
                val t2 = (-b + sqrtDisc) / (2f * a)
                for (tCandidate in floatArrayOf(t1, t2)) {
                    if (tCandidate > 0.05f) {
                        val hx = ox + tCandidate * dx
                        val hy = oy + tCandidate * dy
                        val hz = oz + tCandidate * dz
                        val alpha = atan2(hx, radius - hz)
                        val u = (alpha / thetaRad) + 0.5f
                        val v = 0.5f - (hy / (halfHeight * 2f))
                        if (u in 0f..1f && v in 0f..1f) {
                            tHit = tCandidate; uHit = u; vHit = v
                            break
                        }
                    }
                }
            }
        }

        if (tHit <= 0f) return null

        val localHit4 = floatArrayOf(
            ox + tHit * dx,
            oy + tHit * dy,
            oz + tHit * dz,
            1f
        )
        val worldHit4 = FloatArray(4)
        Matrix.multiplyMV(worldHit4, 0, windowModelMatrix, 0, localHit4, 0)
        val wx = worldHit4[0] - rayOriginWorld[0]
        val wy = worldHit4[1] - rayOriginWorld[1]
        val wz = worldHit4[2] - rayOriginWorld[2]
        val worldDist = sqrt(wx * wx + wy * wy + wz * wz)

        return RaycastHit(
            window = win,
            normalizedU = uHit,
            normalizedV = vHit,
            pixelX = uHit * win.widthPx,
            pixelY = vHit * win.heightPx,
            worldHitPoint = floatArrayOf(worldHit4[0], worldHit4[1], worldHit4[2]),
            distance = worldDist
        )
    }

    private fun drawWindowMesh(win: SpatialAppWindow, mvp: FloatArray, hit: RaycastHit?) {
        GLES30.glUseProgram(windowShaderProgram)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvp, 0)
        GLES30.glUniform1i(uFocusedLoc, if (win.id == activeWindowId) 1 else 0)

        if (hit != null) {
            GLES30.glUniform2f(uCursorUvLoc, hit.normalizedU, hit.normalizedV)
        } else {
            GLES30.glUniform2f(uCursorUvLoc, -1f, -1f)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, win.glTextureId)
        GLES30.glUniform1i(uTextureLoc, 0)

        meshBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 5 * 4, meshBuffer)
        GLES30.glEnableVertexAttribArray(0)

        meshBuffer.position(3)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 5 * 4, meshBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
    }

    private fun drawLaserBeam(origin: FloatArray, dir: FloatArray, hit: RaycastHit?) {
        GLES30.glUseProgram(laserShaderProgram)
        GLES30.glUniformMatrix4fv(uLaserVpMatrixLoc, 0, false, vpMatrix, 0)
        GLES30.glUniform4f(uLaserColorLoc, 0.0f, 0.90f, 1.0f, 0.75f)

        val endX = hit?.worldHitPoint?.get(0) ?: (origin[0] + dir[0] * 4.0f)
        val endY = hit?.worldHitPoint?.get(1) ?: (origin[1] + dir[1] * 4.0f)
        val endZ = hit?.worldHitPoint?.get(2) ?: (origin[2] + dir[2] * 4.0f)

        laserLineVertices[0] = origin[0]
        laserLineVertices[1] = origin[1]
        laserLineVertices[2] = origin[2]
        laserLineVertices[3] = endX
        laserLineVertices[4] = endY
        laserLineVertices[5] = endZ

        laserLineBuffer.position(0)
        laserLineBuffer.put(laserLineVertices)
        laserLineBuffer.position(0)

        GLES30.glLineWidth(4.0f)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, laserLineBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
    }

    fun createExternalGlTexture(): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return texIds[0]
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        return shader
    }
}
