package com.audiobrowser

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** A created [Service], whose binding to HeadlessTaskService connects. */
fun buildService(): ServiceController<Service> {
  val application = RuntimeEnvironment.getApplication()
  // Robolectric connects an unregistered binding with a null ComponentName, which the
  // HeadlessTaskService connection's non-null parameter rejects.
  shadowOf(application)
    .setComponentNameAndServiceForBindService(
      ComponentName(application, HeadlessTaskService::class.java),
      Binder(),
    )
  return Robolectric.buildService(Service::class.java).create()
}

/** The service's session, which it otherwise hands only to a connecting controller. */
@get:OptIn(UnstableApi::class)
val Service.session: MediaLibrarySession
  get() {
    // onGetSession ignores its argument.
    val session =
      onGetSession(
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
          packageName,
          0,
          Process.myUid(),
          0,
          0,
          false,
          Bundle.EMPTY,
          false,
        )
      )
    return checkNotNull(session) { "the session is released" }
  }
