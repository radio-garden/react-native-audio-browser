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

/** MPEG-1 Layer III frames of silence, 32 kHz mono at 32 kbit/s: 144 bytes and 36 ms each. */
fun silentMp3Bytes(duration: Duration): ByteArray {
  val frame =
    ByteArray(144).apply {
      this[0] = 0xFF.toByte()
      this[1] = 0xFB.toByte()
      this[2] = 0x18
      this[3] = 0xC0.toByte()
    }
  val frames = (duration.toMillis() + 35) / 36
  return ByteArray((frames * frame.size).toInt()) { frame[it % frame.size] }
}

/**
 * The `file:` URI of a live HLS playlist, one with no end tag, of [segments] silent MP3 segments of
 * [segmentSeconds] each, written to `live.m3u8` here: a second call throws.
 */
fun TemporaryFolder.liveHlsUri(segments: Int, segmentSeconds: Int = 10): String {
  newFile("segment.mp3").writeBytes(silentMp3Bytes(Duration.ofSeconds(segmentSeconds.toLong())))
  val playlist = buildString {
    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$segmentSeconds\n")
    append("#EXT-X-MEDIA-SEQUENCE:0\n")
    repeat(segments) { append("#EXTINF:$segmentSeconds.0,\nsegment.mp3\n") }
  }
  return Uri.fromFile(newFile("live.m3u8").apply { writeText(playlist) }).toString()
}
