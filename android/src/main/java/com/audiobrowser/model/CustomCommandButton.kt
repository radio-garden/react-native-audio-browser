package com.audiobrowser.model

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.R
import androidx.media3.session.SessionCommand
import com.audiobrowser.option.PlayerCapability

enum class CustomCommandButton(
  val customAction: String,
  val capability: PlayerCapability,
  val commandButton: CommandButton,
) {
  JUMP_BACKWARD(
    customAction = "JUMP_BACKWARD",
    capability = PlayerCapability.JUMP_BACKWARD,
    commandButton =
      CommandButton.Builder()
        .setDisplayName("Jump Backward")
        .setSessionCommand(SessionCommand("JUMP_BACKWARD", Bundle()))
        .setIconResId(R.drawable.media3_icon_skip_back)
        .build(),
  ),
  JUMP_FORWARD(
    customAction = "JUMP_FORWARD",
    capability = PlayerCapability.JUMP_FORWARD,
    commandButton =
      CommandButton.Builder()
        .setDisplayName("Jump Forward")
        .setSessionCommand(SessionCommand("JUMP_FORWARD", Bundle()))
        .setIconResId(R.drawable.media3_icon_skip_forward)
        .build(),
  ),
  PREVIOUS(
    customAction = "PREVIOUS",
    capability = PlayerCapability.SKIP_TO_PREVIOUS,
    commandButton =
      CommandButton.Builder()
        .setDisplayName("Previous")
        .setSessionCommand(SessionCommand("PREVIOUS", Bundle()))
        .setIconResId(R.drawable.media3_icon_previous)
        .build(),
  ),
  NEXT(
    customAction = "NEXT",
    capability = PlayerCapability.SKIP_TO_NEXT,
    commandButton =
      CommandButton.Builder()
        .setDisplayName("Next")
        .setSessionCommand(SessionCommand("NEXT", Bundle()))
        .setIconResId(R.drawable.media3_icon_next)
        .build(),
  ),
}
