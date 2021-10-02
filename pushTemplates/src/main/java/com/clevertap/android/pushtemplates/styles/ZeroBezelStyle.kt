package com.clevertap.android.pushtemplates.styles

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import com.clevertap.android.pushtemplates.PTConstants
import com.clevertap.android.pushtemplates.TemplateRenderer
import com.clevertap.android.pushtemplates.content.*
import com.clevertap.android.pushtemplates.content.PendingIntentFactory

class ZeroBezelStyle(private var renderer: TemplateRenderer): Style(renderer) {

    override fun makeSmallContentView(context: Context, renderer: TemplateRenderer): RemoteViews {
        val textOnlySmallView = renderer.pt_small_view != null &&
                renderer.pt_small_view == PTConstants.TEXT_ONLY
        return if (textOnlySmallView){
            ZeroBezelTextOnlySmallContentView(context, renderer).remoteView
        }else{
            ZeroBezelMixedSmallContentView(context, renderer).remoteView
        }
    }

    override fun makeBigContentView(context: Context, renderer: TemplateRenderer): RemoteViews {
        return ZeroBezelBigContentView(context, renderer).remoteView
    }

    override fun makePendingIntent(
        context: Context,
        extras: Bundle,
        notificationId: Int
    ): PendingIntent? {
        return PendingIntentFactory.getPendingIntent(context,notificationId,extras,true,
            RATING_CONTENT_PENDING_INTENT,renderer
        )
    }

    override fun makeDismissIntent(
        context: Context,
        extras: Bundle,
        notificationId: Int
    ): PendingIntent? {
        return null
    }

}