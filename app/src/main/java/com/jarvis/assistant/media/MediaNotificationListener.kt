package com.jarvis.assistant.media

import android.service.notification.NotificationListenerService

/** Opt-in component identity for MediaSessionManager. Never reads or retains notifications. */
internal class MediaNotificationListener : NotificationListenerService()
