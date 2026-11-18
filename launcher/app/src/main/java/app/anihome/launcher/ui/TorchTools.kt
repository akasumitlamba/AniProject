package app.anihome.launcher.ui

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Light signals for the flashlight and the screen light: SOS, and any message in Morse code.
 * Patterns are in Morse units (positive = light on, negative = off); one unit is [UNIT_MS].
 */
internal object TorchTools {
    private const val UNIT_MS = 200L
    private val handler = Handler(Looper.getMainLooper())
    private var signal: Runnable? = null
    /** A signal (SOS or a message) is flashing on the flashlight. */
    val sosRunning get() = signal != null

    private val CODES = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..",
        'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.", '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '/' to "-..-.", '@' to ".--.-.",
        '\'' to ".----.", '-' to "-....-", '&' to ".-...", ':' to "---...", '=' to "-...-", '+' to ".-.-.")

    /**
     * A message as a light pattern: dot 1 unit, dash 3, 1 between marks, 3 between letters,
     * 7 between words, and a long pause before it repeats. Unknown characters are skipped;
     * empty when nothing in [text] can be sent.
     */
    fun morse(text: String): IntArray {
        val out = mutableListOf<Int>()
        text.uppercase().trim().split(Regex("\\s+")).forEach { word ->
            val letters = word.mapNotNull { CODES[it] }
            if (letters.isEmpty()) return@forEach
            if (out.isNotEmpty()) out[out.size - 1] = -7
            letters.forEach { code ->
                code.forEach { mark -> out += if (mark == '.') 1 else 3; out += -1 }
                out[out.size - 1] = -3
            }
        }
        if (out.isEmpty()) return IntArray(0)
        out[out.size - 1] = -14
        return out.toIntArray()
    }
    val SOS: IntArray get() = morse("SOS")

    /** Repeats [pattern] through [light] until stopped with [stopPattern]. */
    fun runPattern(pattern: IntArray = SOS, light: (Boolean) -> Unit): Runnable {
        var index = 0
        val step = object : Runnable {
            override fun run() {
                val units = pattern[index]; index = (index + 1) % pattern.size
                light(units > 0)
                handler.postDelayed(this, kotlin.math.abs(units) * UNIT_MS)
            }
        }
        handler.post(step)
        return step
    }
    fun stopPattern(step: Runnable?) { step?.let(handler::removeCallbacks) }

    fun startSos(camera: CameraManager?, id: String) = startPattern(camera, id, SOS)
    fun startMorse(camera: CameraManager?, id: String, text: String) { morse(text).takeIf { it.isNotEmpty() }?.let { startPattern(camera, id, it) } }
    private fun startPattern(camera: CameraManager?, id: String, pattern: IntArray) {
        stopSos(camera, id)
        signal = runPattern(pattern) { on -> runCatching { camera?.setTorchMode(id, on) } }
    }
    fun stopSos(camera: CameraManager? = null, id: String? = null) {
        stopPattern(signal); signal = null
        if (camera != null && id != null) runCatching { camera.setTorchMode(id, false) }
    }

    /** Torch brightness levels the phone offers (1 = on/off only; Android 13+ can report more). */
    fun maxLevel(camera: CameraManager?, id: String): Int =
        if (Build.VERSION.SDK_INT >= 33) runCatching { camera?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 }.getOrDefault(1) else 1
}
