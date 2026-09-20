package cc.novelia.app.data.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import cc.novelia.app.MainActivity
import cc.novelia.app.R

object AppNotifications {
    fun show(context: Context, id: Int, title: String, text: String) {
        if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("updates", "下载与书架更新", NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, "updates").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}
