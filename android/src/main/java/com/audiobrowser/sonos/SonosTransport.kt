package com.audiobrowser.sonos

/**
 * Blocking UPnP control of a single Sonos device — the integration seam between the tested protocol
 * units ([SoapEnvelopes], [SoapClient], [SonosStreamUri], [DidlLite], [SoapResponseParser]) and the
 * [SonosPlayer]. Every call blocks on a SOAP round-trip and throws [SoapException] on failure, so
 * callers invoke it off the main thread (the player uses `Dispatchers.IO`).
 */
internal class SonosTransport(private val device: SonosDevice, private val soap: SoapClient) :
  SonosControl {
  /** Loads the stream on the device (`SetAVTransportURI`) without starting playback. */
  override fun setUri(
    streamUrl: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUri: String?,
    live: Boolean,
  ) {
    val uri = SonosStreamUri.forTransport(streamUrl)
    val didl =
      DidlLite.build(
        title = title,
        creator = artist,
        album = album,
        albumArtUri = artworkUri,
        live = live,
      )
    avt(SoapEnvelopes.setAvTransportUri(uri, didl))
  }

  override fun play() = avt(SoapEnvelopes.play())

  override fun pause() = avt(SoapEnvelopes.pause())

  override fun stop() = avt(SoapEnvelopes.stop())

  override fun seek(positionMs: Long) = avt(SoapEnvelopes.seek(positionMs))

  /** The device's current UPnP `CurrentTransportState`, or null if unreadable. */
  override fun getTransportState(): String? =
    SoapResponseParser.transportState(
      soap.execute(device.avTransportControlUrl, SoapEnvelopes.getTransportInfo())
    )

  override fun getPosition(): SonosPosition? =
    SoapResponseParser.position(
      soap.execute(device.avTransportControlUrl, SoapEnvelopes.getPositionInfo())
    )

  override fun setVolume(volume: Int) {
    soap.execute(device.groupRenderingControlUrl, SoapEnvelopes.setGroupVolume(volume))
  }

  override fun getVolume(): Int? =
    SoapResponseParser.volume(
      soap.execute(device.groupRenderingControlUrl, SoapEnvelopes.getGroupVolume())
    )

  private fun avt(action: SoapAction) {
    soap.execute(device.avTransportControlUrl, action)
  }
}
