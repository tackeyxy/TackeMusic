package com.tacke.music.utils

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    data class NotificationPermissionState(
        val runtimePermissionGranted: Boolean,
        val appNotificationsEnabled: Boolean,
        val channelEnabled: Boolean,
        val channelImportance: Int?
    ) {
        val isFullyEnabled: Boolean
            get() = runtimePermissionGranted && appNotificationsEnabled && channelEnabled
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun getNotificationPermissionState(
        context: Context,
        channelId: String? = null
    ): NotificationPermissionState {
        val runtimePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelId.isNullOrBlank()) {
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(channelId)
                ?.importance
        } else {
            null
        }
        val channelEnabled = channelImportance == null || channelImportance != NotificationManager.IMPORTANCE_NONE
        return NotificationPermissionState(
            runtimePermissionGranted = runtimePermissionGranted,
            appNotificationsEnabled = appNotificationsEnabled,
            channelEnabled = channelEnabled,
            channelImportance = channelImportance
        )
    }

    fun buildOverlayPermissionIntents(context: Context): List<Intent> {
        val packageUri = Uri.parse("package:${context.packageName}")
        return listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        )
    }

    fun buildNotificationSettingsIntents(
        context: Context,
        channelId: String? = null
    ): List<Intent> {
        val intents = mutableListOf<Intent>()
        val packageUri = Uri.parse("package:${context.packageName}")
        val appUid = context.applicationInfo.uid

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelId.isNullOrBlank()) {
            intents += Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                putExtra("app_package", context.packageName)
                putExtra("app_uid", appUid)
            }
        }

        intents += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra("app_package", context.packageName)
            putExtra("app_uid", appUid)
        }
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        return intents
    }

    fun findFirstResolvableIntent(context: Context, intents: List<Intent>): Intent? {
        return intents.firstOrNull { intent ->
            intent.resolveActivity(context.packageManager) != null
        }
    }
}
