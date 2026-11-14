package dev.found.lyrics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.found.lyrics.nowplaying.MediaSessionListenerService
import dev.found.lyrics.overlay.LyricOverlayService

/**
 * The one screen in Found: permissions, and how the bubble looks.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: FoundSettings
    private lateinit var overlaySwitch: Switch
    private lateinit var notificationSwitch: Switch
    private lateinit var autoCollapseSwitch: Switch
    private lateinit var topLeftSwitch: Switch
    private lateinit var textScaleBar: SeekBar
    private lateinit var textScaleLabel: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = FoundSettings(this)

        overlaySwitch = findViewById(R.id.switch_overlay)
        notificationSwitch = findViewById(R.id.switch_notification)
        autoCollapseSwitch = findViewById(R.id.switch_auto_collapse)
        topLeftSwitch = findViewById(R.id.switch_top_left)
        textScaleBar = findViewById(R.id.seek_text_scale)
        textScaleLabel = findViewById(R.id.label_text_scale)
        statusText = findViewById(R.id.text_status)

        overlaySwitch.isChecked = settings.overlayEnabled
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            settings.overlayEnabled = checked
            syncService()
        }

        notificationSwitch.isChecked = settings.showNotification
        notificationSwitch.setOnCheckedChangeListener { _, checked ->
            settings.showNotification = checked
        }

        autoCollapseSwitch.isChecked = settings.autoCollapse
        autoCollapseSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoCollapse = checked
        }

        topLeftSwitch.isChecked = settings.corner == 1
        topLeftSwitch.setOnCheckedChangeListener { _, checked ->
            settings.corner = if (checked) 1 else 0
        }

        textScaleBar.progress = ((settings.textScale - 0.75f) * 100f).toInt().coerceIn(0, 75)
        updateTextScaleLabel()
        textScaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.textScale = 0.75f + progress / 100f
                updateTextScaleLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<Button>(R.id.btn_overlay_permission).setOnClickListener {
            openOverlaySettings()
        }
        findViewById<Button>(R.id.btn_media_access).setOnClickListener {
            MediaSessionListenerService.openSettings(this)
        }
        findViewById<Button>(R.id.btn_notifications).setOnClickListener {
            requestNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        syncService()
    }

    private fun updateTextScaleLabel() {
        textScaleLabel.text = getString(R.string.text_scale_value, settings.textScale)
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val mediaOk = MediaSessionListenerService.isEnabled(this)
        val notificationsOk = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        statusText.text = getString(
            R.string.status_template,
            if (overlayOk) "granted" else "not granted",
            if (mediaOk) "granted" else "not granted",
            if (notificationsOk) "granted" else "not granted",
        )
    }

    /** Starts or stops the overlay service to match the current preferences. */
    private fun syncService() {
        val wanted = settings.overlayEnabled && Settings.canDrawOverlays(this)
        if (wanted) {
            LyricOverlayService.start(this)
        } else {
            LyricOverlayService.stop(this)
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openAppSettings()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openAppSettings()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}
