package ca.pkay.rcloneexplorer.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

class NotificationUtils {


    companion object {

        @SuppressLint("MissingPermission") // Checked by PermissionManager(context).grantedNotifications()
        @JvmStatic
        fun createNotification(context: Context, notificationId: Int, notification: Notification) {
            if (notification.visibility != Notification.VISIBILITY_SECRET) {
                notification.visibility = Notification.VISIBILITY_PRIVATE
                notification.publicVersion = android.app.Notification.Builder(context)
                    .setSmallIcon(notification.icon)
                    .setContentTitle("CloudBridge")
                    .setContentText("Background operation")
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build()
            }
            if(PermissionManager(context).grantedNotifications()) {
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.notify(notificationId, notification)
            }
        }


        @SuppressLint("MissingPermission") // Checked by PermissionManager(context).grantedNotifications()
        @JvmStatic
        fun createNotificationChannel(context: Context, channelId: String, channelName: String, importance: Int, description: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Create the NotificationChannel, but only on API 26+ because
                // the NotificationChannel class is new and not in the support library
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    importance
                )
                channel.description = description
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                // Register the channel with the system
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                notificationManager?.createNotificationChannel(channel)
            }
        }
    }

}