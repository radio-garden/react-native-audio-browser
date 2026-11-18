package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButton
import com.margelo.nitro.audiobrowser.RemoteButtonLayout

/*
 * The one rule for whether a Capability is enabled and the one derivation of the button layout.
 * Every Media3 assembly in MediaSessionCommandManager consumes these instead of deciding for itself.
 */

/**
 * A control a Capability gates. `shuffleMode`, `repeatMode` and `playbackRate` exist on
 * [PlayerCapabilities] but drive only CarPlay, so they have no entry.
 */
enum class Capability {
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
 * Everything is enabled unless explicitly `false` — except FAVORITE, which is opt-in, and
 * PLAY_PAUSE, one control that goes away only when both `play` and `pause` are `false`.
 */
fun PlayerCapabilities.isEnabled(capability: Capability): Boolean =
  when (capability) {
    Capability.PLAY_PAUSE -> !(play == false && pause == false)
    Capability.STOP -> stop != false
    Capability.SEEK_TO -> seekTo != false
    Capability.SKIP_TO_NEXT -> skipToNext != false
    Capability.SKIP_TO_PREVIOUS -> skipToPrevious != false
    Capability.JUMP_FORWARD -> jumpForward != false
    Capability.JUMP_BACKWARD -> jumpBackward != false
    Capability.FAVORITE -> favoriteEnabled
  }

/**
 * The positions every Android system surface renders, mapped to `CommandButton.SLOT_*` at assembly.
 * Media3's SLOT_BACK_SECONDARY and SLOT_FORWARD_SECONDARY are left out: `DisplayConstraints` allows
 * them 0 buttons unless a controller opts in with `setMaxButtonsForSlot`, which no system surface
 * does.
 */
enum class ButtonSlot {
  BACK,
  FORWARD,
  OVERFLOW,
}

data class SlottedButton(val button: RemoteButton, val slot: ButtonSlot)

private val RemoteButton.capability: Capability
  get() =
    when (this) {
      RemoteButton.SKIP_TO_PREVIOUS -> Capability.SKIP_TO_PREVIOUS
      RemoteButton.SKIP_TO_NEXT -> Capability.SKIP_TO_NEXT
      RemoteButton.JUMP_BACKWARD -> Capability.JUMP_BACKWARD
      RemoteButton.JUMP_FORWARD -> Capability.JUMP_FORWARD
      RemoteButton.FAVORITE -> Capability.FAVORITE
    }

fun PlayerCapabilities.allows(button: RemoteButton): Boolean = isEnabled(button.capability)

/**
 * Layout equality with `overflow` compared by content. Data-class `==` compares the array by
 * reference, and every options update brings a fresh array across the bridge, so `==` would
 * republish the layout to Android Auto on every unrelated `updateOptions`.
 */
internal fun RemoteButtonLayout?.sameAs(other: RemoteButtonLayout?): Boolean =
  if (this == null || other == null) {
    this == null && other == null
  } else {
    back == other.back && forward == other.forward && overflow.contentEquals(other.overflow)
  }

/**
 * The buttons and their positions. [capabilities] decides which buttons may exist; [layout] only
 * arranges them, and can never add one: an entry whose Capability is disabled drops out, leaving
 * its position empty rather than promoting another button into it.
 *
 * Without a [layout], skip takes BACK and FORWARD, jump takes a primary position when its skip is
 * disabled and otherwise goes to OVERFLOW, and favorite goes to OVERFLOW.
 *
 * A [layout] is never merged with those defaults: Nitro maps an omitted and a null enum field to
 * the same `null`, so native cannot tell "leave empty" from "derive this one".
 *
 * Placement is cosmetic: [capabilities] also drives the session commands, so a button left out of
 * the layout disappears from every screen but still answers a headset.
 */
fun deriveButtonSlots(
  capabilities: PlayerCapabilities,
  layout: RemoteButtonLayout?,
): List<SlottedButton> {
  if (layout != null) {
    return buildList {
        layout.back?.let { add(SlottedButton(it, ButtonSlot.BACK)) }
        layout.forward?.let { add(SlottedButton(it, ButtonSlot.FORWARD)) }
        layout.overflow.forEach { add(SlottedButton(it, ButtonSlot.OVERFLOW)) }
      }
      .filter { capabilities.allows(it.button) }
  }

  return buildList {
    val skipPrevious = capabilities.isEnabled(Capability.SKIP_TO_PREVIOUS)
    val skipNext = capabilities.isEnabled(Capability.SKIP_TO_NEXT)
    val jumpBackward = capabilities.isEnabled(Capability.JUMP_BACKWARD)
    val jumpForward = capabilities.isEnabled(Capability.JUMP_FORWARD)

    if (skipPrevious) {
      add(SlottedButton(RemoteButton.SKIP_TO_PREVIOUS, ButtonSlot.BACK))
    } else if (jumpBackward) {
      add(SlottedButton(RemoteButton.JUMP_BACKWARD, ButtonSlot.BACK))
    }
    if (skipNext) {
      add(SlottedButton(RemoteButton.SKIP_TO_NEXT, ButtonSlot.FORWARD))
    } else if (jumpForward) {
      add(SlottedButton(RemoteButton.JUMP_FORWARD, ButtonSlot.FORWARD))
    }

    if (skipPrevious && jumpBackward) {
      add(SlottedButton(RemoteButton.JUMP_BACKWARD, ButtonSlot.OVERFLOW))
    }
    if (skipNext && jumpForward) {
      add(SlottedButton(RemoteButton.JUMP_FORWARD, ButtonSlot.OVERFLOW))
    }

    if (capabilities.isEnabled(Capability.FAVORITE)) {
      add(SlottedButton(RemoteButton.FAVORITE, ButtonSlot.OVERFLOW))
    }
  }
}
