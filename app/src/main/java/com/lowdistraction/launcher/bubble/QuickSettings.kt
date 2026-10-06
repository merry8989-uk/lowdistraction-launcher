package com.lowdistraction.launcher.bubble

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * The device-setting shortcuts shown from the bubble's long-press panel.
 * Everything here uses only public APIs; the two "modes" that Android keeps
 * private (Focus mode and Bedtime mode, owned by Digital Wellbeing) are
 * approximated by opening the relevant settings screen.
 */
object QuickSettings {

    private var torchOn = false
    private var torchId: String? = null

    // ---------------------------------------------------------------- Torch
    fun toggleTorch(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = torchId ?: cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run { toast(context, "No torch on this device"); return }
            torchId = id
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (_: Exception) {
            toast(context, "Torch not available")
        }
    }

    // ---------------------------------------------------------------- Sound
    fun openSound(context: Context) {
        // No public API exists to pop the volume slider; nudge the music
        // stream so the system shows its own volume UI.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ----------------------------------------------------------- Brightness
    fun cycleBrightness(context: Context) {
        if (!Settings.System.canWrite(context)) {
            openWriteSettings(context)
            toast(context, "Allow modifying system settings, then tap again")
            return
        }
        val resolver = context.contentResolver
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = when {
            current < 90 -> 200
            current < 210 -> 255
            else -> 60
        }
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
        toast(context, "Brightness $next/255")
    }

    // ------------------------------------------------------- Do Not Disturb
    fun toggleDnd(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            toast(context, "Grant Do Not Disturb access, then tap again")
            return
        }
        val currentlyOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (currentlyOn) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        toast(context, if (currentlyOn) "DND off" else "DND on")
    }

    // ------------------------------------- Focus / Bedtime (Digital Wellbeing)
    fun openFocusMode(context: Context) =
        openSettings(context, "android.settings.DIGITAL_WELLBEING_SETTINGS", "Focus mode")

    fun openBedtimeMode(context: Context) =
        openSettings(context, "android.settings.DIGITAL_WELLBEING_SETTINGS", "Bedtime mode")

    private fun openSettings(context: Context, action: String, label: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast(context, "$label: not available on this device")
        }
    }

    fun openWriteSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
