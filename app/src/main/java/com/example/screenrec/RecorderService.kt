package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_AUDIO = "audio"
        private const val CHANNEL = "rec"
        private const val SAMPLE_RATE = 44100
        private const val PUMP_NONE = 0
        private const val PUMP_DONE = 1
        private const val PUMP_EOS = 2

        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    private var vThread: Thread? = null
    private var aThread: Thread? = null

    private var hasAudio = false
    private var vTrack = -1
    private var aTrack = -1
    private var muxerStarted = false
    @Volatile private var stopFlag = false
    private var baseUs = 0L
    private var lastV = -1L
    private var lastA = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNow()
                if (!running) {
                    try {
                        start(
                            intent.getIntExtra(EXTRA_CODE, 0),
                            intent.getParcelableExtra<Intent>(EXTRA_DATA)!!,
                            intent.getIntExtra(EXTRA_QUALITY, 0),
                            intent.getBooleanExtra(EXTRA_AUDIO, true)
                        )
                    } catch (e: Exception) {
                        cleanupAfterFailure()
                    }
                }
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Recording screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    private fun start(code: Int, data: Intent, quality: Int, wantAudio: Boolean) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }, main)

        // Screen size and encoder settings
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val short = minOf(dm.widthPixels, dm.heightPixels)
        val targetShort = when (quality) {
            0 -> 720
            1 -> 1080
            else -> short
        }
        val scale = minOf(1.0, targetShort.toDouble() / short)
        val w = maxOf(16, (dm.widthPixels * scale).toInt() / 16 * 16)
        val h = maxOf(16, (dm.heightPixels * scale).toInt() / 16 * 16)
        val bitrate = when (quality) {
            0 -> 2_500_000
            1 -> 6_000_000
            else -> 12_000_000
        }
        val fps = if (quality == 2) 60 else 30

        // Output file in Movies/ScreenRecs
        val fileName = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecs")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        uri = u
        val p = contentResolver.openFileDescriptor(u, "rw")!!
        pfd = p
        muxer = MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Video encoder (hardware, fed straight from the screen surface)
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val ve = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = ve.createInputSurface()
        vEnc = ve

        // Internal audio capture (game / app sound, taken before the speaker so volume doesn't matter)
        var bufSize = 0
        if (wantAudio) {
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val af = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                bufSize = maxOf(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2,
                    16384
                )
                val ar = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(bufSize)
                    .build()
                val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
                }
                ae.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioRecord = ar
                aEnc = ae
                hasAudio = true
            } catch (e: Exception) {
                hasAudio = false
            }
        }

        baseUs = System.nanoTime() / 1000
        vTrack = -1; aTrack = -1; muxerStarted = false; stopFlag = false
        lastV = -1L; lastA = -1L

        ve.start()
        display = proj.createVirtualDisplay(
            "rec", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        vThread = Thread {
            var r = PUMP_NONE
            while (r != PUMP_EOS) {
                r = pump(ve, true, 10_000)
            }
        }.apply { name = "rec-video" }

        if (hasAudio) {
            val ae = aEnc!!
            val ar = audioRecord!!
            ae.start()
            aThread = Thread { audioLoop(ar, ae) }.apply { name = "rec-audio" }
        }

        running = true
        vThread!!.start()
        aThread?.start()
    }

    private fun audioLoop(ar: AudioRecord, ae: MediaCodec) {
        ar.startRecording()
        var totalFrames = 0L
        var basePts = -1L
        while (!stopFlag) {
            val idx = ae.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = ae.getInputBuffer(idx)!!
                ib.clear()
                val n = ar.read(ib, minOf(ib.capacity(), 4096))
                if (n > 0) {
                    if (basePts < 0) {
                        basePts = maxOf(0L, System.nanoTime() / 1000 - baseUs - n / 4 * 1_000_000L / SAMPLE_RATE)
                    }
                    val pts = basePts + totalFrames * 1_000_000L / SAMPLE_RATE
                    ae.queueInputBuffer(idx, 0, n, pts, 0)
                    totalFrames += n / 4
                } else {
                    ae.queueInputBuffer(idx, 0, 0, 0, 0)
                }
            }
            while (pump(ae, false, 0) == PUMP_DONE) { /* drain everything ready */ }
        }
        try { ar.stop() } catch (e: Exception) {}
        // Signal end of stream
        var queued = false
        var tries = 0
        while (!queued && tries++ < 100) {
            val idx = ae.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val pts = maxOf(0L, if (basePts < 0) 0L else basePts + totalFrames * 1_000_000L / SAMPLE_RATE)
                ae.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                pump(ae, false, 0)
            }
        }
        var r = PUMP_NONE
        var spins = 0
        while (r != PUMP_EOS && spins++ < 500) {
            r = pump(ae, false, 10_000)
        }
    }

    /** Pulls one finished buffer from an encoder and writes it to the file. */
    private fun pump(codec: MediaCodec, video: Boolean, timeoutUs: Long): Int {
        val info = MediaCodec.BufferInfo()
        val i = try { codec.dequeueOutputBuffer(info, timeoutUs) } catch (e: Exception) { return PUMP_EOS }
        if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            synchronized(lock) {
                val t = muxer!!.addTrack(codec.outputFormat)
                if (video) vTrack = t else aTrack = t
                if (vTrack >= 0 && (aTrack >= 0 || !hasAudio)) {
                    muxer!!.start()
                    muxerStarted = true
                    lock.notifyAll()
                }
            }
            return PUMP_DONE
        }
        if (i < 0) return PUMP_NONE

        val buf = codec.getOutputBuffer(i)
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
        if (info.size > 0 && buf != null) {
            synchronized(lock) {
                var waited = 0
                while (!muxerStarted && waited < 250) { lock.wait(20); waited++ }
            }
            if (muxerStarted) {
                var pts = if (video) info.presentationTimeUs - baseUs else info.presentationTimeUs
                if (pts < 0) pts = 0
                if (video) {
                    if (pts <= lastV) pts = lastV + 1
                    lastV = pts
                } else {
                    if (pts <= lastA) pts = lastA + 1
                    lastA = pts
                }
                info.presentationTimeUs = pts
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                synchronized(lock) {
                    try { muxer!!.writeSampleData(if (video) vTrack else aTrack, buf, info) } catch (e: Exception) {}
                }
            }
        }
        codec.releaseOutputBuffer(i, false)
        return if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) PUMP_EOS else PUMP_DONE
    }

    private fun stopRecording() {
        if (!running) return
        running = false
        Thread {
            stopFlag = true
            try { display?.release() } catch (e: Exception) {}
            try { vEnc?.signalEndOfInputStream() } catch (e: Exception) {}
            try { aThread?.join(5000) } catch (e: Exception) {}
            try { vThread?.join(5000) } catch (e: Exception) {}

            var ok = true
            try {
                if (muxerStarted) muxer?.stop() else ok = false
            } catch (e: Exception) { ok = false }
            try { muxer?.release() } catch (e: Exception) {}
            try { vEnc?.stop() } catch (e: Exception) {}
            try { vEnc?.release() } catch (e: Exception) {}
            try { aEnc?.stop() } catch (e: Exception) {}
            try { aEnc?.release() } catch (e: Exception) {}
            try { audioRecord?.release() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
            finishFile(ok)
            try { projection?.stop() } catch (e: Exception) {}
            projection = null
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun finishFile(ok: Boolean) {
        val u = uri ?: return
        try {
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(u, v, null, null)
            } else {
                contentResolver.delete(u, null, null)
            }
        } catch (e: Exception) {}
        uri = null
    }

    private fun cleanupAfterFailure() {
        running = false
        stopFlag = true
        try { display?.release() } catch (e: Exception) {}
        try { vEnc?.release() } catch (e: Exception) {}
        try { aEnc?.release() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        try { muxer?.release() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
        finishFile(false)
        try { projection?.stop() } catch (e: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
