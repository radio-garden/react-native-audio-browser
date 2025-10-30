package com.doublesymmetry.trackplayer.event

data class EventControllerConnection(
  val packageName: String,
  val isMediaNotificationController: Boolean,
  val isAutomotiveController: Boolean,
  val isAutoCompanionController: Boolean,
)
