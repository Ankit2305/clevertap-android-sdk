package com.clevertap.android.sdk.custom

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StickyNotificationBroadcastReceiver: BroadcastReceiver() {
    companion object {
        const val NOTIFICATION_ID = "com.clevertap.android.sdk.custom.NOTIFICATION_ID"
    }
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i("DebugTag", "onReceive: $intent $context")
        if(intent == null || context == null)
            return
        val notificationId = intent.getIntExtra(NOTIFICATION_ID, 0)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Log.i("DebugTag", "onReceive: $notificationId")
        notificationManager.cancel(notificationId)
    }
}