package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import java.text.NumberFormat
import java.util.Locale

object PriceAlertNotifications {
    private const val CHANNEL_ID = "minimo_price_drops"
    private const val CHANNEL_NAME = "Bajadas de precio"

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /** Returns true only if Android accepted the notification. */
    fun showPriceDrop(
        context: Context,
        productId: String,
        productTitle: String,
        price: Double,
        targetPrice: Double,
        storeName: String
    ): Boolean {
        if (!hasPermission(context)) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val locale = Locale("es", "ES")
        val priceText = NumberFormat.getCurrencyInstance(locale).format(price)
        val targetText = NumberFormat.getCurrencyInstance(locale).format(targetPrice)
        val shortTitle = productTitle.ifBlank { "Tu producto vigilado" }
        val contentText = "$priceText en $storeName · objetivo $targetText"
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            productId.hashCode(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ha bajado el precio: $shortTitle")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$shortTitle cuesta ahora $priceText en $storeName. Tu objetivo era $targetText."
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(productId.hashCode() and 0x7fffffff, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
