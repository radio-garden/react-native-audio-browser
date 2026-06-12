package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.NotificationButton
import com.margelo.nitro.audiobrowser.NotificationButtonLayout
import com.margelo.nitro.audiobrowser.PlayerCapabilities

/**
 * The pure decision core for Capability-gated controls: one rule for whether a control is available
 * ([isEnabled]) and one derivation of the notification button layout ([deriveNotificationSlots]).
 * MediaSessionCommandManager's three Media3 assemblies (external player commands, session button
 * layout, notification commands/layout) all consume these decisions — previously each
 * re-implemented them, and the copies had drifted.
 */

/** A user-facing control gated by a Capability. */
enum class Control {
  PLAY_PAUSE,
  STOP,
  SEEK_TO,
  SKIP_TO_NEXT,
  SKIP_TO_PREVIOUS,
  JUMP_FORWARD,
  JUMP_BACKWARD,
  FAVORITE,
}

/**
 * Whether a Capability-gated control is available. Everything is enabled by default and only an
 * explicit `false` disables — except FAVORITE, which is opt-in, and PLAY_PAUSE, which is one
 * control on every surface and only goes away when both halves are disabled.
 */
fun PlayerCapabilities.isEnabled(control: Control): Boolean =
  when (control) {
    Control.PLAY_PAUSE -> !(play == false && pause == false)
    Control.STOP -> stop != false
    Control.SEEK_TO -> seekTo != false
    Control.SKIP_TO_NEXT -> skipToNext != false
    Control.SKIP_TO_PREVIOUS -> skipToPrevious != false
    Control.JUMP_FORWARD -> jumpForward != false
    Control.JUMP_BACKWARD -> jumpBackward != false
    Control.FAVORITE -> favoriteEnabled
  }

/** Notification slot, mapped to `CommandButton.SLOT_*` at Media3 assembly time. */
enum class NotificationSlot {
  BACK,
  FORWARD,
  BACK_SECONDARY,
  FORWARD_SECONDARY,
  OVERFLOW,
}

data class SlottedButton(val button: NotificationButton, val slot: NotificationSlot)

private val NotificationButton.control: Control
  get() =
    when (this) {
      NotificationButton.SKIP_TO_PREVIOUS -> Control.SKIP_TO_PREVIOUS
      NotificationButton.SKIP_TO_NEXT -> Control.SKIP_TO_NEXT
      NotificationButton.JUMP_BACKWARD -> Control.JUMP_BACKWARD
      NotificationButton.JUMP_FORWARD -> Control.JUMP_FORWARD
      NotificationButton.FAVORITE -> Control.FAVORITE
    }

/** Whether [button]'s gating Capability allows it. */
fun PlayerCapabilities.allows(button: NotificationButton): Boolean = isEnabled(button.control)

/**
 * The single derivation of the notification button layout: an explicit [layout]'s slots filtered to
 * allowed buttons, or — with no layout — the capability defaults: skip on the primary slots (jump
 * falling back to a primary slot when its skip is disabled), jump on the secondary slots otherwise,
 * favorite in overflow.
 */
fun deriveNotificationSlots(
  capabilities: PlayerCapabilities,
  layout: NotificationButtonLayout?,
): List<SlottedButton> {
  if (layout != null) {
    return buildList {
        layout.back?.let { add(SlottedButton(it, NotificationSlot.BACK)) }
        layout.forward?.let { add(SlottedButton(it, NotificationSlot.FORWARD)) }
        layout.backSecondary?.let { add(SlottedButton(it, NotificationSlot.BACK_SECONDARY)) }
        layout.forwardSecondary?.let { add(SlottedButton(it, NotificationSlot.FORWARD_SECONDARY)) }
        layout.overflow?.forEach { add(SlottedButton(it, NotificationSlot.OVERFLOW)) }
      }
      .filter { capabilities.allows(it.button) }
  }

  return buildList {
    val skipPrevious = capabilities.isEnabled(Control.SKIP_TO_PREVIOUS)
    val skipNext = capabilities.isEnabled(Control.SKIP_TO_NEXT)
    val jumpBackward = capabilities.isEnabled(Control.JUMP_BACKWARD)
    val jumpForward = capabilities.isEnabled(Control.JUMP_FORWARD)

    // Primary slots: skip, with jump falling back when its skip is disabled.
    if (skipPrevious) {
      add(SlottedButton(NotificationButton.SKIP_TO_PREVIOUS, NotificationSlot.BACK))
    } else if (jumpBackward) {
      add(SlottedButton(NotificationButton.JUMP_BACKWARD, NotificationSlot.BACK))
    }
    if (skipNext) {
      add(SlottedButton(NotificationButton.SKIP_TO_NEXT, NotificationSlot.FORWARD))
    } else if (jumpForward) {
      add(SlottedButton(NotificationButton.JUMP_FORWARD, NotificationSlot.FORWARD))
    }

    // Jump moves to the secondary slots when skip holds the primary.
    if (skipPrevious && jumpBackward) {
      add(SlottedButton(NotificationButton.JUMP_BACKWARD, NotificationSlot.BACK_SECONDARY))
    }
    if (skipNext && jumpForward) {
      add(SlottedButton(NotificationButton.JUMP_FORWARD, NotificationSlot.FORWARD_SECONDARY))
    }

    if (capabilities.isEnabled(Control.FAVORITE)) {
      add(SlottedButton(NotificationButton.FAVORITE, NotificationSlot.OVERFLOW))
    }
  }
}
