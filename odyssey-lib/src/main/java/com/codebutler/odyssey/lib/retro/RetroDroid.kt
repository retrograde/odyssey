/*
 * RetroDroid.kt
 *
 * Copyright (C) 2017 Odyssey Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.odyssey.lib.retro

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.view.KeyEvent
import android.view.MotionEvent
import com.codebutler.odyssey.common.BitmapCache
import com.codebutler.odyssey.common.BufferCache
import com.codebutler.odyssey.common.kotlin.containsAny
import com.sun.jna.Native
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.experimental.and

/**
 * Native Android frontend for LibRetro!
 */
class RetroDroid(private val context: Context, coreFile: File) : DefaultLifecycleObserver {

    private val audioSampleBufferCache = BufferCache()
    private val handler = Handler()
    private val pressedKeys = mutableSetOf<Int>()
    private val retro: Retro
    private val variables: MutableMap<String, String> = mutableMapOf()
    private val videoBufferCache = BufferCache()
    private val videoBitmapCache = BitmapCache()

    private var region: Retro.Region? = null
    private var systemAVInfo: Retro.SystemAVInfo? = null
    private var systemInfo: Retro.SystemInfo? = null
    private var timer: Timer? = null
    private var videoBitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
    private var videoBytesPerPixel: Int = 0

    /**
     * Callback for audio data, should be set by frontend.
     */
    var audioCallback: ((buffer: ByteArray) -> Unit)? = null

    /**
     * Callback for log events, should be set by frontend.
     */
    var logCallback: ((level: Retro.LogLevel, message: String) -> Unit)? = null

    /**
     * Callback for preparing audio playback.
     */
    var prepareAudioCallback: ((sampleRate: Int) -> Unit)? = null

    /**
     * Callback for video data, should be set by frontend.
     */
    var videoCallback: ((bitmap: Bitmap) -> Unit)? = null

    /**
     * Callback when game is unloaded, to allow for persisting save ram.
     */
    var gameUnloadedCallback: ((saveData: ByteArray?) -> Unit)? = null

    init {
        Native.setCallbackExceptionHandler { c, e ->
            handler.post {
                throw Exception("JNA: Callback $c threw exception", e)
            }
        }

        System.setProperty("jna.library.path", coreFile.parentFile.absolutePath)

        val coreLibraryName = coreFile.nameWithoutExtension.substring(3) // FIXME

        retro = Retro(coreLibraryName)

        retro.environmentCallback = RetroDroidEnvironmentCallback()

        retro.videoCallback = { data, width, height, pitch ->
            val newBuffer = videoBufferCache.getBuffer(width * height * videoBytesPerPixel)
            for (i in 0 until height) {
                val widthAsBytes = width * videoBytesPerPixel
                System.arraycopy(
                        data,             // SRC
                        i * pitch,        // SRC POS
                        newBuffer,        // DST
                        i * widthAsBytes, // DST POS
                        widthAsBytes      // LENGTH
                )
            }
            val bitmap = videoBitmapCache.getBitmap(width, height, videoBitmapConfig)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(newBuffer))
            videoCallback?.invoke(bitmap)
        }

        retro.audioSampleCallback = { left, right ->
            val buffer = audioSampleBufferCache.getBuffer(2)
            buffer[0] = left.toByte() and 0xff.toByte()
            buffer[1] = (right.toInt() shr 8).toByte() and 0xff.toByte()
            audioCallback?.invoke(buffer)
        }

        retro.audioSampleBatchCallback = { data ->
            audioCallback?.invoke(data)
            data.size.toLong()
        }

        retro.inputPollCallback = { /* Nothing to do here? */ }

        retro.inputStateCallback = this::onInputState

        retro.init()
    }

    fun loadGame(gamePath: String, saveData: ByteArray?) {
        val systemInfo = retro.getSystemInfo()
        Timber.d("System Info: $systemInfo")

        if (systemInfo.needFullpath) {
            if (!retro.loadGame(gamePath)) {
                throw Exception("Failed to load game via path: $gamePath")
            }
        } else {
            Timber.d("Load game with data!!")
            if (!retro.loadGame(File(gamePath).readBytes())) {
                throw Exception("Failed to load game via buffer: $gamePath")
            }
        }

        Timber.d("Game loaded!")

        val region = retro.getRegion()
        Timber.d("Region: $region")

        val systemAVInfo = retro.getSystemAVInfo()
        Timber.d("System AV Info: $systemAVInfo")
        updateSystemAVInfo(systemAVInfo)

        this.region = region
        this.systemInfo = systemInfo

        if (saveData != null) {
            retro.setMemoryData(Retro.MemoryId.SAVE_RAM, saveData)
        }
    }

    fun start() {
        val avInfo = systemAVInfo
        if (this.timer != null || avInfo == null) {
            return
        }
        this.timer = fixedRateTimer(period = 1000L / avInfo.timing.fps.toLong()) {
            retro.run()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        start()
    }

    override fun onPause(owner: LifecycleOwner) {
        stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unloadGame()
        deinit()
    }

    fun onKeyEvent(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> pressedKeys.add(event.keyCode)
            KeyEvent.ACTION_UP -> pressedKeys.remove(event.keyCode)
        }
    }

    fun onMotionEvent(event: MotionEvent) {
        Timber.d("onMotionEvent: $event")

        when (event.rawX) {
            1.0F -> {
                pressedKeys.add(KeyEvent.KEYCODE_DPAD_RIGHT)
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            -1.0F -> {
                pressedKeys.add(KeyEvent.KEYCODE_DPAD_LEFT)
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            else -> {
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_RIGHT)
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_LEFT)
            }
        }

        when (event.rawY) {
            1.0F -> {
                pressedKeys.add(KeyEvent.KEYCODE_DPAD_DOWN)
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_UP)
            }
            -1.0F -> {
                pressedKeys.add(KeyEvent.KEYCODE_DPAD_UP)
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            else -> {
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_UP)
                pressedKeys.remove(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
    }

    private fun stop() {
        timer?.cancel()
        timer = null
    }

    private fun unloadGame() {
        val saveRam = retro.getMemoryData(Retro.MemoryId.SAVE_RAM)
        retro.unloadGame()
        gameUnloadedCallback?.invoke(saveRam)
    }

    private fun deinit() {
        retro.deinit()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onInputState(port: Int, device: Int, index: Int, id: Int): Boolean {
        if (port != 0) {
            // Only P1 supported for now.
            return false
        }

        when (Retro.Device.fromValue(device)) {
            Retro.Device.NONE -> { }
            Retro.Device.JOYPAD -> {
                return when (Retro.DeviceId.fromValue(id)) {
                    Retro.DeviceId.JOYPAD_A -> pressedKeys.containsAny(
                            KeyEvent.KEYCODE_A,
                            KeyEvent.KEYCODE_BUTTON_A)
                    Retro.DeviceId.JOYPAD_B -> pressedKeys.containsAny(
                            KeyEvent.KEYCODE_B,
                            KeyEvent.KEYCODE_BUTTON_B)
                    Retro.DeviceId.JOYPAD_DOWN -> pressedKeys.contains(KeyEvent.KEYCODE_DPAD_DOWN)
                    Retro.DeviceId.JOYPAD_L -> pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_L1)
                    Retro.DeviceId.JOYPAD_L2 -> pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_L2)
                    Retro.DeviceId.JOYPAD_L3 -> false
                    Retro.DeviceId.JOYPAD_LEFT -> pressedKeys.contains(KeyEvent.KEYCODE_DPAD_LEFT)
                    Retro.DeviceId.JOYPAD_R -> pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_R1)
                    Retro.DeviceId.JOYPAD_R2 -> pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_R2)
                    Retro.DeviceId.JOYPAD_R3 -> false
                    Retro.DeviceId.JOYPAD_RIGHT -> pressedKeys.contains(KeyEvent.KEYCODE_DPAD_RIGHT)
                    Retro.DeviceId.JOYPAD_SELECT -> pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_SELECT)
                    Retro.DeviceId.JOYPAD_START -> pressedKeys.containsAny(
                            KeyEvent.KEYCODE_BUTTON_START,
                            KeyEvent.KEYCODE_ENTER)
                    Retro.DeviceId.JOYPAD_UP -> pressedKeys.contains(KeyEvent.KEYCODE_DPAD_UP)
                    Retro.DeviceId.JOYPAD_X -> pressedKeys.containsAny(
                            KeyEvent.KEYCODE_BUTTON_X,
                            KeyEvent.KEYCODE_X)
                    Retro.DeviceId.JOYPAD_Y -> pressedKeys.containsAny(
                            KeyEvent.KEYCODE_BUTTON_Y,
                            KeyEvent.KEYCODE_Y)
                }
            }
            Retro.Device.MOUSE -> TODO()
            Retro.Device.KEYBOARD -> TODO()
            Retro.Device.LIGHTGUN -> TODO()
            Retro.Device.ANALOG -> TODO()
            Retro.Device.POINTER -> TODO()
        }
        return false
    }

    private fun updateSystemAVInfo(systemAVInfo: Retro.SystemAVInfo) {
        prepareAudioCallback?.invoke(systemAVInfo.timing.sample_rate.toInt())
        this.systemAVInfo = systemAVInfo
    }

    inner class RetroDroidEnvironmentCallback : Retro.EnvironmentCallback {
        override fun onGetLogInterface(): Retro.LogInterface? {
            // Retro logging is somewhat expensive, so skip entirely in production builds.
            if (Timber.treeCount() > 0) {
                return object : Retro.LogInterface {
                    override fun onLogMessage(level: Retro.LogLevel, message: String) {
                        logCallback?.invoke(level, message)
                    }
                }
            }
            return null
        }

        override fun onSetVariables(variables: Map<String, String>) {
            Timber.d("onSetVariables: $variables")
            this@RetroDroid.variables.putAll(variables)
        }

        override fun onSetSupportAchievements(supportsAchievements: Boolean) {
            // FIXME: Implement
            Timber.d("onSetSupportAchievements: $supportsAchievements")
        }

        override fun onSetPerformanceLevel(performanceLevel: Int) {
            // FIXME: Implement
            Timber.d("onSetPerformanceLevel: $performanceLevel")
        }

        override fun onSetSystemAvInfo(info: Retro.SystemAVInfo) {
            Timber.d("onSetSystemAvInfo: $info")
            updateSystemAVInfo(info)
        }

        override fun onSetGeometry(geometry: Retro.GameGeometry) {
            Timber.d("onSetGeometry: $geometry")
            val systemAVInfo = systemAVInfo ?: retro.getSystemAVInfo()
            updateSystemAVInfo(systemAVInfo.copy(geometry = geometry))
        }

        override fun onGetVariable(name: String): String? {
            Timber.d("onGetVariable: $name, value: ${variables[name]}")
            return variables[name]
        }

        override fun onSetPixelFormat(pixelFormat: Retro.PixelFormat): Boolean {
            val bitmapConfig = when (pixelFormat) {
                Retro.PixelFormat.XRGB8888 -> Bitmap.Config.ARGB_8888
                Retro.PixelFormat.RGB565 -> Bitmap.Config.RGB_565
                else -> TODO()
            }

            val pixelFormatInfo = pixelFormat.info

            Timber.d("""onSetPixelFormat: $pixelFormat
                bitsPerPixel: ${pixelFormatInfo.bitsPerPixel}
                bytesPerPixel: ${pixelFormatInfo.bytesPerPixel}""")

            videoBitmapConfig = bitmapConfig
            videoBytesPerPixel = pixelFormatInfo.bytesPerPixel

            return true
        }

        override fun onSetInputDescriptors(descriptors: List<Retro.InputDescriptor>) {
            // FIXME: Implement
            Timber.d("onSetInputDescriptors: $descriptors")
        }

        override fun onSetControllerInfo(info: List<Retro.ControllerInfo>) {
            // FIXME: Implement
            Timber.d("onSetControllerInfo: $info")
        }

        override fun onGetVariableUpdate(): Boolean {
            // FIXME: Implement
            //Timber.d("onGetVariableUpdate")
            return false
        }

        override fun onGetSystemDirectory(): String? {
            val dir = File(context.filesDir, "system")
            dir.mkdirs()
            Timber.d("onGetSystemDirectory ${dir.absolutePath}")
            return dir.absolutePath
        }

        override fun onGetSaveDirectory(): String? {
            val dir = File(context.filesDir, "save")
            dir.mkdirs()
            Timber.d("onGetSaveDirectory ${dir.absolutePath}")
            return dir.absolutePath
        }

        override fun onSetMemoryMaps() {
            // FIXME: Implement
            //Timber.d("onSetMemoryMaps")
        }

        override fun onUnsupportedCommand(cmd: Int) {
            Timber.e("Unsupported env command: $cmd")
        }

        override fun onUnhandledException(error: Throwable) {
            throw RuntimeException("Unhandled Exception in Environment Callback", error)
        }
    }
}

