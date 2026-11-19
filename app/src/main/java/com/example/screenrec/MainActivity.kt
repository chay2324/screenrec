package com.example.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var button: Button
    private lateinit var audioBox: CheckBox
    private lateinit var group: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Screen Rec"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Quality (lower = lighter on battery)"
            setPadding(0, pad, 0, 0)
        })

        group = RadioGroup(this)
        listOf("Light (720p)", "Medium (1080p)", "Max (your screen's full resolution)").forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                text = label
                id = 100 + i
            })
        }
        group.check(100)
        root.addView(group)

        audioBox = CheckBox(this).apply {
            text = "Record game / app audio (works at any volume)"
            isChecked = true
        }
        root.addView(audioBox)

        button = Button(this).apply {
            textSize = 18f
            setOnClickListener { onButton() }
        }
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        root.addView(TextView(this).apply {
            text = "Videos are saved in Movies/ScreenRecs.\nPull down the notification and tap Stop to finish."
            gravity = Gravity.START
            setPadding(0, pad, 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        button.text = if (RecorderService.running) "Stop recording" else "Start recording"
    }

    private fun onButton() {
        if (RecorderService.running) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            button.postDelayed({ refresh() }, 1500)
            return
        }
        val needed = mutableListOf<String>()
        if (audioBox.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
        } else {
            askCapture()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (audioBox.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioBox.isChecked = false
            }
            askCapture()
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            val quality = group.checkedRadioButtonId - 100
            val intent = Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_CODE, resultCode)
                .putExtra(RecorderService.EXTRA_DATA, data)
                .putExtra(RecorderService.EXTRA_QUALITY, quality)
                .putExtra(RecorderService.EXTRA_AUDIO, audioBox.isChecked)
            startForegroundService(intent)
            moveTaskToBack(true)
        }
    }
}
