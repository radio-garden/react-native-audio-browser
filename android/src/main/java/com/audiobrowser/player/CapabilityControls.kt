package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButton
import com.margelo.nitro.audiobrowser.RemoteButtonLayout

/**
 * The pure decision core for Capabilities: one rule for whether a Capability is enabled
 * ([isEnabled]) and one derivation of the button layout ([deriveButtonSlots]).
 * MediaSessionCommandManager's Media3 assemblies all consume these decisions — previously each
 * re-implemented them, and the copies had drifted.
 */

/**
 * A control that a Capability gates. Mirrors the [PlayerCapabilities] flags Android acts on —
 * `shuffleMode`, `repeatMode` and `playbackRate` exist on the struct but only drive CarPlay, so
 * they have no entry here.
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
 * Whether a Capability is enabled. Everything is enabled by default and only an explicit `false`
 * disables — except FAVORITE, which is opt-in, and PLAY_PAUSE, which is one control on every
 * surface and only goes away when both halves are disabled.
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
 * The three positions Android actually offers, mapped to `CommandButton.SLOT_*` at Media3 assembly
 * time. There is no secondary pair: every system surface flattens the layout to back, forward and
 * overflow, so a fourth or fifth position would have nowhere to render.
 *
 * Media3 does define SLOT_BACK_SECONDARY and SLOT_FORWARD_SECONDARY, but `DisplayConstraints`
 * defaults both to **0 buttons** — only a controller that opts in with `setMaxButtonsForSlot` shows
 * them, which no system surface does.
 */
enum class ButtonSlot {
  BACK,
  FORWARD,
  OVERFLOW,
}

data class SlottedButton(val button: RemoteButton, val slot: ButtonSlot)

/** The Capability gating a button. */
private val RemoteButton.capability: Capability
  get() =
    when (this) {
      RemoteButton.SKIP_TO_PREVIOUS -> Capability.SKIP_TO_PREVIOUS
      RemoteButton.SKIP_TO_NEXT -> Capability.SKIP_TO_NEXT
      RemoteButton.JUMP_BACKWARD -> Capability.JUMP_BACKWARD
      RemoteButton.JUMP_FORWARD -> Capability.JUMP_FORWARD
      RemoteButton.FAVORITE -> Capability.FAVORITE
    }

/** Whether [button]'s gating Capability allows it. */
fun PlayerCapabilities.allows(button: RemoteButton): Boolean = isEnabled(button.capability)

/**
 * Whether two layouts describe the same arrangement.
 *
 * [RemoteButtonLayout] is a generated data class whose `overflow` is an `Array`, and Kotlin
 * data-class equality compares arrays by *reference*. Plain `==` therefore reports "changed" for
 * two identical layouts whenever overflow is set, because every options update carries a fresh
 * array across the bridge — which would republish the layout to Android Auto on every unrelated
 * `updateOptions` call.
 */
internal fun RemoteButtonLayout?.sameAs(other: RemoteButtonLayout?): Boolean =
  if (this == null || other == null) {
    this == null && other == null
  } else {
    back == other.back && forward == other.forward && overflow.contentEquals(other.overflow)
  }

/**
 * The single derivation of the button layout.
 *
 * The two parameters answer different questions, and only one of them is authoritative:
 * - [capabilities] — *may* this button exist? Admission.
 * - [layout] — *where* does it go? Arrangement.
 *
 * **A layout can rearrange buttons but never add one.** Every entry is filtered through [allows]
 * regardless of its position, so naming a button whose Capability is disabled does nothing —
 * listing `FAVORITE` while `capabilities.favorite` is off leaves it off. This is the usual
 * surprise, because JUMP_FORWARD and JUMP_BACKWARD default to disabled: a layout full of jump
 * buttons silently produces none until those capabilities are turned on.
 *
 * With no [layout], [capabilities] does both jobs — it decides membership *and* infers positions:
 * skip takes the primary positions, jump falls back into a primary position when its skip is
 * disabled and otherwise sits in overflow, favorite in overflow.
 *
 * With a [layout], each field maps to its position — `back` to BACK, `forward` to FORWARD, every
 * `overflow` entry to OVERFLOW in order — and disabled entries drop out. A dropped entry leaves its
 * position empty rather than promoting anything into it: turning off one capability must not
 * silently rearrange the row.
 *
 * A [layout] is all-or-nothing: every field is required, so it fully describes the arrangement and
 * nothing is merged with the capability defaults. The bridge could not support a per-field merge
 * anyway — Nitro maps both an omitted and a null enum field to Kotlin `null`, so native cannot tell
 * "leave this empty" from "derive this one".
 *
 * [capabilities] has a second job outside this function: it also drives the player and session
 * commands, which govern what a Bluetooth remote or headset can trigger. That is why placement is
 * purely cosmetic here — a button left out of the layout disappears from every screen while still
 * responding to a headset.
 *
 * @param capabilities What the player is allowed to do. Gates every button, in both modes.
 * @param layout Explicit placement, or null to infer placement from [capabilities].
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

    // Primary positions: skip, with jump falling back when its skip is disabled.
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

    // Jump moves to overflow when skip holds the primary position.
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
