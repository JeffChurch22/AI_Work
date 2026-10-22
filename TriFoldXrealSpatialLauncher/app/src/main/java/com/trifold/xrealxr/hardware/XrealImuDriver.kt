package com.trifold.xrealxr.hardware

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.*

data class Quaternion(val w: Float = 1f, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun normalized(): Quaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        return if (norm > 1e-6f) Quaternion(w / norm, x / norm, y / norm, z / norm) else Quaternion()
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    operator fun times(o: Quaternion): Quaternion = Quaternion(
        w = w * o.w - x * o.x - y * o.y - z * o.z,
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w
    )

    fun rotateVector(vx: Float, vy: Float, vz: Float): FloatArray {
        val qv = Quaternion(0f, vx, vy, vz)
        val res = this * qv * conjugate()
        return floatArrayOf(res.x, res.y, res.z)
    }

    fun toMatrix4x4(out: FloatArray) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        out[0] = 1f - 2f * (yy + zz); out[4] = 2f * (xy - wz);       out[8]  = 2f * (xz + wy);       out[12] = 0f
        out[1] = 2f * (xy + wz);       out[5] = 1f - 2f * (xx + zz); out[9]  = 2f * (yz - wx);       out[13] = 0f
        out[2] = 2f * (xz - wy);       out[6] = 2f * (yz + wx);       out[10] = 1f - 2f * (xx + yy); out[14] = 0f
        out[3] = 0f;                   out[7] = 0f;                   out[11] = 0f;                   out[15] = 1f
    }

    companion object {
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            var cosHalfTheta = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            var bCopy = b
            if (cosHalfTheta < 0f) {
                bCopy = Quaternion(-b.w, -b.x, -b.y, -b.z)
                cosHalfTheta = -cosHalfTheta
            }
            if (cosHalfTheta >= 0.9995f) {
                return Quaternion(
                    a.w + t * (bCopy.w - a.w),
                    a.x + t * (bCopy.x - a.x),
                    a.y + t * (bCopy.y - a.y),
                    a.z + t * (bCopy.z - a.z)
                ).normalized()
            }
            val halfTheta = acos(cosHalfTheta.coerceIn(-1f, 1f))
            val sinHalfTheta = sqrt(1f - cosHalfTheta * cosHalfTheta)
            val ratioA = sin((1f - t) * halfTheta) / sinHalfTheta
            val ratioB = sin(t * halfTheta) / sinHalfTheta
            return Quaternion(
                a.w * ratioA + bCopy.w * ratioB,
                a.x * ratioA + bCopy.x * ratioB,
                a.y * ratioA + bCopy.y * ratioB,
                a.z * ratioA + bCopy.z * ratioB
            ).normalized()
        }
    }
}

class XrealImuDriver(private val context: Context) {
    companion object {
        private const val TAG = "XrealImuDriver"
        const val XREAL_VID = 0x3318
        val SUPPORTED_PIDS = setOf(0x0424, 0x0428, 0x0432) // Air, Air 2, Air 2 Pro

        private const val IMU_INTERFACE_INDEX = 3
        private const val MCU_INTERFACE_INDEX = 4
        private const val PACKET_SIZE = 0x40 // 64 bytes

        // Display modes supported by XREAL Air / Air 2 Pro MCU (Command 0x0008)
        const val DISPLAY_MODE_1080P_60HZ = 1
        const val DISPLAY_MODE_SBS_60HZ = 3
        const val DISPLAY_MODE_1080P_120HZ = 11
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var imuInterface: UsbInterface? = null
    private var mcuInterface: UsbInterface? = null
    private var imuEndpointIn: UsbEndpoint? = null
    private var imuEndpointOut: UsbEndpoint? = null
    private var mcuEndpointOut: UsbEndpoint? = null

    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var currentQuaternion = Quaternion()
    @Volatile
    private var recenterOffset = Quaternion()

    private var gyroBiasX = 0f
    private var gyroBiasY = 0f
    private var gyroBiasZ = 0f
    private var lastTimestampNs = 0L

    fun findGlassesDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == XREAL_VID && device.productId in SUPPORTED_PIDS
        }
    }

    fun start(device: UsbDevice): Boolean {
        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open XREAL USB device. Ensure USB permission is granted.")
            return false
        }
        connection = conn

        if (device.interfaceCount > IMU_INTERFACE_INDEX) {
            val intf = device.getInterface(IMU_INTERFACE_INDEX)
            if (conn.claimInterface(intf, true)) {
                imuInterface = intf
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN) imuEndpointIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) imuEndpointOut = ep
                }
            }
        }

        if (device.interfaceCount > MCU_INTERFACE_INDEX) {
            val intf = device.getInterface(MCU_INTERFACE_INDEX)
            if (conn.claimInterface(intf, true)) {
                mcuInterface = intf
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_OUT) mcuEndpointOut = ep
                }
            }
        }

        if (imuEndpointIn == null || imuEndpointOut == null) {
            Log.e(TAG, "Could not locate IMU HID endpoints on Interface 3")
            return false
        }

        sendImuCommand(commandId = 0x19, payload = byteArrayOf(0x01))

        readJob = scope.launch {
            pollImuLoop()
        }
        return true
    }

    fun setDisplayMode(mode: Int) {
        val epOut = mcuEndpointOut ?: return
        val conn = connection ?: return
        val packet = ByteArray(PACKET_SIZE)
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

        bb.put(0, 0xFD.toByte())
        val dataLen = 17 + 1
        bb.putShort(5, dataLen.toShort())
        bb.putInt(7, 0x1337)
        bb.putInt(11, 0)
        bb.putShort(15, 0x0008.toShort())
        bb.put(22, mode.toByte())

        val crc = CRC32()
        crc.update(packet, 5, dataLen)
        bb.putInt(1, crc.value.toInt())

        conn.bulkTransfer(epOut, packet, packet.size, 200)
    }

    private fun sendImuCommand(commandId: Int, payload: ByteArray) {
        val epOut = imuEndpointOut ?: return
        val conn = connection ?: return
        val packet = ByteArray(PACKET_SIZE)
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

        val length = 3 + payload.size
        bb.put(0, 0xAA.toByte())
        bb.putShort(5, length.toShort())
        bb.put(7, commandId.toByte())
        System.arraycopy(payload, 0, packet, 8, payload.size)

        val crc = CRC32()
        crc.update(packet, 5, length)
        bb.putInt(1, crc.value.toInt())

        conn.bulkTransfer(epOut, packet, packet.size, 200)
    }

    private suspend fun pollImuLoop() {
        val epIn = imuEndpointIn ?: return
        val conn = connection ?: return
        val buffer = ByteArray(PACKET_SIZE)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        while (currentCoroutineContext().isActive) {
            val read = conn.bulkTransfer(epIn, buffer, buffer.size, 50)
            if (read >= 0x2A && buffer[0] == 0x01.toByte() && buffer[1] == 0x02.toByte()) {
                parseImuPacket(buffer, bb)
            }
        }
    }

    private fun readInt24LE(buf: ByteArray, offset: Int): Int {
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        val b2 = buf[offset + 2].toInt() and 0xFF
        val combined = b0 or (b1 shl 8) or (b2 shl 16)
        return (combined shl 8) shr 8
    }

    private fun parseImuPacket(buf: ByteArray, bb: ByteBuffer) {
        val timestampNs = bb.getLong(0x04)
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return
        }
        val dt = ((timestampNs - lastTimestampNs) * 1e-9f).coerceIn(0.0001f, 0.05f)
        lastTimestampNs = timestampNs

        val gyroMul = (bb.getShort(0x0C).toInt() and 0xFFFF).toFloat()
        val gyroDiv = bb.getInt(0x0E).toFloat().coerceAtLeast(1f)
        val rawGx = readInt24LE(buf, 0x12).toFloat()
        val rawGy = readInt24LE(buf, 0x15).toFloat()
        val rawGz = readInt24LE(buf, 0x18).toFloat()

        val degToRad = (Math.PI / 180.0).toFloat()
        val gxScale = (gyroMul / gyroDiv) * degToRad
        var gx = (rawGx * gxScale) - gyroBiasX
        var gy = (rawGy * gxScale) - gyroBiasY
        var gz = (rawGz * gxScale) - gyroBiasZ

        val accelMul = (bb.getShort(0x1B).toInt() and 0xFFFF).toFloat()
        val accelDiv = bb.getInt(0x1D).toFloat().coerceAtLeast(1f)
        val axScale = accelMul / accelDiv
        val ax = readInt24LE(buf, 0x21) * axScale
        val ay = readInt24LE(buf, 0x24) * axScale
        val az = readInt24LE(buf, 0x27) * axScale

        val gyroMag = sqrt(gx * gx + gy * gy + gz * gz)
        val accelMag = sqrt(ax * ax + ay * ay + az * az)
        if (gyroMag < 0.018f && abs(accelMag - 1.0f) < 0.08f) {
            val alpha = 0.002f
            gyroBiasX = (1f - alpha) * gyroBiasX + alpha * (rawGx * gxScale)
            gyroBiasY = (1f - alpha) * gyroBiasY + alpha * (rawGy * gxScale)
            gyroBiasZ = (1f - alpha) * gyroBiasZ + alpha * (rawGz * gxScale)
            gx = 0f; gy = 0f; gz = 0f
        }

        integrateOrientation(gx, gy, gz, ax, ay, az, dt)
    }

    private fun integrateOrientation(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        dt: Float
    ) {
        val q = currentQuaternion
        val halfDt = 0.5f * dt
        val dq = Quaternion(
            w = 1f,
            x = gx * halfDt,
            y = gy * halfDt,
            z = gz * halfDt
        ).normalized()
        var nextQ = (q * dq).normalized()

        val aNorm = sqrt(ax * ax + ay * ay + az * az)
        if (aNorm in 0.85f..1.15f) {
            val nx = ax / aNorm
            val ny = ay / aNorm
            val nz = az / aNorm
            val vx = 2f * (nextQ.x * nextQ.z - nextQ.w * nextQ.y)
            val vy = 2f * (nextQ.w * nextQ.x + nextQ.y * nextQ.z)
            val vz = nextQ.w * nextQ.w - nextQ.x * nextQ.x - nextQ.y * nextQ.y + nextQ.z * nextQ.z

            val ex = (ny * vz - nz * vy)
            val ey = (nz * vx - nx * vz)
            val ez = (nx * vy - ny * vx)
            val kp = 0.015f
            val corrQ = Quaternion(1f, ex * kp * halfDt, ey * kp * halfDt, ez * kp * halfDt).normalized()
            nextQ = (nextQ * corrQ).normalized()
        }

        currentQuaternion = nextQ
    }

    fun getHeadOrientation(): Quaternion {
        return (recenterOffset.conjugate() * currentQuaternion).normalized()
    }

    fun recenter() {
        recenterOffset = currentQuaternion
    }

    fun stop() {
        readJob?.cancel()
        sendImuCommand(0x19, byteArrayOf(0x00))
        imuInterface?.let { connection?.releaseInterface(it) }
        mcuInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
    }
}
