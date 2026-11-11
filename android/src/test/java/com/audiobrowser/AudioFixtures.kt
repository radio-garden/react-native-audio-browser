package com.audiobrowser

import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import org.junit.rules.TemporaryFolder

/** A WAV of [duration] of 8 kHz mono 8-bit silence, its data size in its header. */
fun silentWavBytes(duration: Duration): ByteArray {
  val samples = (duration.toMillis() * 8).toInt()
  val header =
    ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray())
      putInt(36 + samples)
      put("WAVEfmt ".toByteArray())
      putInt(16)
      putShort(1)
      putShort(1)
      putInt(8_000)
      putInt(8_000)
      putShort(1)
      putShort(8)
      put("data".toByteArray())
      putInt(samples)
    }
  return header.array() + ByteArray(samples) { 0x80.toByte() }
}

/** The `file:` URI of [silentWavBytes] written to `silence.wav` here: a second call throws. */
fun TemporaryFolder.silentWavUri(duration: Duration): String =
  Uri.fromFile(newFile("silence.wav").apply { writeBytes(silentWavBytes(duration)) }).toString()
