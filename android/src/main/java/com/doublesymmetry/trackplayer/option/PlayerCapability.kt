package com.doublesymmetry.trackplayer.option

import com.margelo.nitro.audiobrowser.Capability as NitroCapability

enum class PlayerCapability {
  PLAY,
  PLAY_FROM_ID,
  PLAY_FROM_SEARCH,
  PAUSE,
  STOP,
  SEEK_TO,
  SKIP,
  SKIP_TO_NEXT,
  SKIP_TO_PREVIOUS,
  JUMP_FORWARD,
  JUMP_BACKWARD,
  SET_RATING,
  LIKE,
  DISLIKE,
  BOOKMARK;

  companion object {

    fun fromNitro(nitroCapability: NitroCapability): PlayerCapability {
      return when (nitroCapability) {
        NitroCapability.PLAY -> PLAY
        NitroCapability.PLAY_FROM_ID -> PLAY_FROM_ID
        NitroCapability.PLAY_FROM_SEARCH -> PLAY_FROM_SEARCH
        NitroCapability.PAUSE -> PAUSE
        NitroCapability.STOP -> STOP
        NitroCapability.SEEK_TO -> SEEK_TO
        NitroCapability.SKIP -> SKIP
        NitroCapability.SKIP_TO_NEXT -> SKIP_TO_NEXT
        NitroCapability.SKIP_TO_PREVIOUS -> SKIP_TO_PREVIOUS
        NitroCapability.JUMP_FORWARD -> JUMP_FORWARD
        NitroCapability.JUMP_BACKWARD -> JUMP_BACKWARD
        NitroCapability.SET_RATING -> SET_RATING
      }
    }
  }

  fun toNitro(): NitroCapability {
    return when (this) {
      PLAY -> NitroCapability.PLAY
      PLAY_FROM_ID -> NitroCapability.PLAY_FROM_ID
      PLAY_FROM_SEARCH -> NitroCapability.PLAY_FROM_SEARCH
      PAUSE -> NitroCapability.PAUSE
      STOP -> NitroCapability.STOP
      SEEK_TO -> NitroCapability.SEEK_TO
      SKIP -> NitroCapability.SKIP
      SKIP_TO_NEXT -> NitroCapability.SKIP_TO_NEXT
      SKIP_TO_PREVIOUS -> NitroCapability.SKIP_TO_PREVIOUS
      JUMP_FORWARD -> NitroCapability.JUMP_FORWARD
      JUMP_BACKWARD -> NitroCapability.JUMP_BACKWARD
      SET_RATING -> NitroCapability.SET_RATING
      LIKE -> NitroCapability.SET_RATING // Map to closest equivalent
      DISLIKE -> NitroCapability.SET_RATING // Map to closest equivalent
      BOOKMARK -> NitroCapability.SET_RATING // Map to closest equivalent
    }
  }
}
