package com.example.screenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RECORDING_STOPPED = "com.example.screenrecorder.RECORDING_STOPPED"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001

        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHUNK = 2048 // short samples per read (~46 ms)
        private const val VIDEO_BIT_RATE = 8 * 1024 * 1024
        private const val VIDEO_FRAME_RATE = 30
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var pfd: android.os.ParcelFileDescriptor? = null

    // Recording engine (MediaCodec) state.
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var codecThread: HandlerThread? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var videoFinished = false
    private var audioFinished = false

    // Encoder output that arrives before the muxer has started is copied here
    // and flushed as soon as both tracks are known.
    private val pendingVideo = ArrayDeque<ByteBuffer>()
    private val pendingAudio = ArrayDeque<ByteBuffer>()
    private val pendingVideoInfo = ArrayDeque<MediaCodec.BufferInfo>()
    private val pendingAudioInfo = ArrayDeque<MediaCodec.BufferInfo>()

    private val isRecording = AtomicBoolean(false)
    private val engineLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)
                // NOTE: on success Android returns resultCode == Activity.RESULT_OK == -1,
                // so checking "resultCode != -1" would ALWAYS reject a granted permission.
                // The real signal that consent was obtained is a non-null data Intent.
                if (data != null) {
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        showErrorAndStop("Xizmatni fon rejimida ishga tushirib bo'lmadi")
                        return START_NOT_STICKY
                    }
                    startRecording(resultCode, data)
                } else {
                    showErrorAndStop("Ekranni yozib olish uchun ruxsat olinmadi")
                }
            }
            ACTION_STOP -> {
                stopAndFinalize()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtraCompat(
        intent: Intent,
        name: String,
        clazz: Class<T>
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            intent.getParcelableExtra(name)
        }
    }

    private fun sendStoppedBroadcast() {
        val intent = Intent(ACTION_RECORDING_STOPPED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun showErrorAndStop(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        sendStoppedBroadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, pendingFlags
        )

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_media_pause,
            getString(R.string.stop_recording),
            stopPendingIntent
        )
        // MediaStyle keeps the stop action on the collapsed row of the ongoing
        // notification, so the stop button is always visible in the notification
        // / quick panel like a media-player control — no need to expand anything.
        val mediaStyle = android.app.Notification.MediaStyle()
            .setShowActionsInCompactView(0)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setStyle(mediaStyle)
            .addAction(stopAction)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ------------------------------------------------------------------ //
    // Recording engine (MediaCodec + MediaMuxer + internal-audio capture)
    // ------------------------------------------------------------------ //

    private fun startRecording(resultCode: Int, data: Intent) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                showErrorAndStop("Ekranni yozib olish ruxsati olinmadi")
                return
            }

            if (!createOutputFile()) {
                showErrorAndStop("Video fayl yaratib bo'lmadi")
                return
            }

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            if (width <= 0 || height <= 0) {
                abortFailed("Ekran o'lchamini aniqlab bo'lmadi")
                return
            }
            // H.264 requires even width/height.
            val safeWidth = if (width % 2 == 0) width else width - 1
            val safeHeight = if (height % 2 == 0) height else height - 1

            codecThread = HandlerThread("ScreenRecorderCodecs").apply { start() }
            val handler = Handler(codecThread!!.looper)
            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Video encoder (surface input) ---
            val vFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, safeWidth, safeHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vCodec.createInputSurface()
            videoEncoder = vCodec
            vCodec.setCallback(encoderCallback, handler)
            vCodec.start()

            // --- Audio encoder (AAC) ---
            val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val aFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                1 // mono
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder = aCodec
            aCodec.setCallback(encoderCallback, handler)
            aCodec.start()

            // --- Capture the screen into the video encoder's surface ---
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder",
                safeWidth, safeHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null
            )
            if (virtualDisplay == null) {
                abortFailed("Virtual displey yaratib bo'lmadi")
                return
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopAndFinalize()
                }
            }, mainHandler)

            // --- Internal (phone) audio capture ---
            val record = buildAudioRecord()
            if (record == null) {
                abortFailed("Ichki tovushni ochib bo'lmadi")
                return
            }
            audioRecord = record
            record.startRecording()
            isRecording.set(true)

            val audioThread = Thread { audioLoop(record) }
            audioThread.name = "ScreenRecorderAudio"
            audioThread.start()
        } catch (e: Exception) {
            e.printStackTrace()
            abortFailed("Yozishni boshlashda xatolik: ${e.message}")
        }
    }

    /** Builds the capture that records what other apps play (Android 10+).
     *  On Android 8-9 it falls back to the microphone. */
    private fun buildAudioRecord(): AudioRecord? {
        return try {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                AUDIO_CHUNK * 2
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                buildMicRecord(bufferSize)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun buildMicRecord(bufferSize: Int): AudioRecord {
        return AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    private fun audioLoop(record: AudioRecord) {
        val shorts = ShortArray(AUDIO_CHUNK)
        var ptsUs = 0L
        while (isRecording.get()) {
            val n = record.read(shorts, 0, AUDIO_CHUNK)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION) break
                continue
            }
            submitAudio(shorts, n, ptsUs)
            ptsUs += n * 1_000_000L / AUDIO_SAMPLE_RATE
        }
        signalAudioEos()
    }

    private fun submitAudio(shorts: ShortArray, n: Int, ptsUs: Long) {
        try {
            val codec = audioEncoder ?: return
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex < 0) return
            val inBuffer = codec.getInputBuffer(inIndex) ?: return
            inBuffer.clear()
            // AudioRecord delivers little-endian PCM16.
            inBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                inBuffer.putShort(shorts[i])
            }
            codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun signalAudioEos() {
        try {
            val codec = audioEncoder ?: return
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex < 0) return
            val inBuffer = codec.getInputBuffer(inIndex) ?: return
            inBuffer.clear()
            codec.queueInputBuffer(
                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- MediaCodec async callbacks (video and audio share one handler) ----

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            try {
                synchronized(engineLock) {
                    if (codec === videoEncoder && videoTrackIndex < 0) {
                        videoTrackIndex = muxer!!.addTrack(format)
                    } else if (codec === audioEncoder && audioTrackIndex < 0) {
                        audioTrackIndex = muxer!!.addTrack(format)
                    }
                    startMuxerIfReadyLocked()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { abortFailed("Kodlash xatosi") }
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                val buffer = codec.getOutputBuffer(index) ?: return
                // Codec-specific data is already carried by the track format; skip it.
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0
                }
                if (info.size > 0) {
                    val isVideo = codec === videoEncoder
                    val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                    synchronized(engineLock) {
                        if (muxerStarted && trackIndex >= 0) {
                            muxer!!.writeSampleData(trackIndex, buffer, info)
                        } else {
                            // Muxer not started yet: copy the sample and flush later.
                            val copy = ByteBuffer.allocateDirect(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            copy.put(buffer)
                            copy.rewind()
                            val copyInfo = MediaCodec.BufferInfo()
                            copyInfo.set(0, info.size, info.presentationTimeUs, info.flags)
                            if (isVideo) {
                                pendingVideo.add(copy)
                                pendingVideoInfo.add(copyInfo)
                            } else {
                                pendingAudio.add(copy)
                                pendingAudioInfo.add(copyInfo)
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    val isVideo = codec === videoEncoder
                    synchronized(engineLock) {
                        if (isVideo) videoFinished = true else audioFinished = true
                        if (videoFinished && audioFinished) {
                            mainHandler.post { finalizeFile(success = true) }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { abortFailed("Yozish paytida xatolik") }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            e.printStackTrace()
            mainHandler.post { abortFailed("Kodlash xatosi: ${e.message}") }
        }
    }

    private fun startMuxerIfReadyLocked() {
        if (muxerStarted) return
        if (videoTrackIndex < 0 || audioTrackIndex < 0) return
        muxer!!.start()
        muxerStarted = true
        flushPendingLocked()
    }

    private fun flushPendingLocked() {
        while (pendingVideo.isNotEmpty()) {
            val buffer = pendingVideo.removeFirst()
            val info = pendingVideoInfo.removeFirst()
            muxer!!.writeSampleData(videoTrackIndex, buffer, info)
        }
        while (pendingAudio.isNotEmpty()) {
            val buffer = pendingAudio.removeFirst()
            val info = pendingAudioInfo.removeFirst()
            muxer!!.writeSampleData(audioTrackIndex, buffer, info)
        }
    }

    /** Idempotent stop: releases the capture and lets both encoders finish cleanly. */
    private fun stopAndFinalize() {
        if (!isRecording.compareAndSet(true, false)) return
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            e.printStackTrace()
            videoFinished = true
        }
        // audioLoop sees isRecording == false, exits and sends the audio EOS.
        // finalizeFile() runs from the callback once BOTH encoders emitted EOS.
    }

    private fun finalizeFile(success: Boolean) {
        val ok = success && muxerStarted
        if (ok) {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        releaseEngine()
        if (ok) {
            publishFile()
        } else {
            deleteOutput()
        }
        sendStoppedBroadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseEngine() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
        }
        audioRecord = null

        try {
            muxer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        videoFinished = false
        audioFinished = false
        pendingVideo.clear()
        pendingVideoInfo.clear()
        pendingAudio.clear()
        pendingAudioInfo.clear()

        try {
            videoEncoder?.stop()
        } catch (e: Exception) {
        }
        try {
            videoEncoder?.release()
        } catch (e: Exception) {
        }
        videoEncoder = null
        try {
            audioEncoder?.stop()
        } catch (e: Exception) {
        }
        try {
            audioEncoder?.release()
        } catch (e: Exception) {
        }
        audioEncoder = null

        try {
            inputSurface?.release()
        } catch (e: Exception) {
        }
        inputSurface = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
        }
        mediaProjection = null

        codecThread?.quitSafely()
        codecThread = null

        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        pfd = null
    }

    /** Makes the MediaStore file visible (IS_PENDING = 0) after a good recording. */
    private fun publishFile() {
        val uri = outputUri ?: return
        outputUri = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteOutput() {
        val uri = outputUri ?: return
        outputUri = null
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createOutputFile(): Boolean {
        return try {
            val fileName = "ScreenRecord_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            outputUri = uri
            pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: run {
                    contentResolver.delete(uri, null, null)
                    outputUri = null
                    return false
                }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Error path: abort the recording and remove the unfinished (hidden) file. */
    private fun abortFailed(message: String) {
        isRecording.set(false)
        releaseEngine()
        deleteOutput()
        showErrorAndStop(message)
    }

    override fun onDestroy() {
        stopAndFinalize()
        super.onDestroy()
    }
}
