package com.audiobrowser.sonos

/**
 * UPnP control of one Sonos room. Every call blocks on a SOAP round-trip, so call it off the main
 * thread, and throws [SoapException] when the speaker answers with an error.
 */
internal class SonosTransport(private val device: SonosDevice, private val soap: SoapClient) :
  SonosControl {
  /** Loads the stream on the room (`SetAVTransportURI`) without starting playback. */
  override fun setUri(
    streamUrl: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUri: String?,
    live: Boolean,
  ) {
    val uri = SonosStreamUri.forTransport(streamUrl, live)
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

  /** The room's UPnP `CurrentTransportState`, such as `PLAYING`, or null if unreadable. */
  override fun getTransportState(): String? =
    SoapResponseParser.transportState(
      soap.execute(device.avTransportControlUrl, SoapEnvelopes.getTransportInfo())
    )

  override fun getPosition(): SonosPosition? =
    SoapResponseParser.position(
      soap.execute(device.avTransportControlUrl, SoapEnvelopes.getPositionInfo())
    )

  override fun getMediaUri(): String? =
    SoapResponseParser.currentUri(
      soap.execute(device.avTransportControlUrl, SoapEnvelopes.getMediaInfo())
    )

  /** Sets the volume of every speaker in the group this device coordinates, not of one speaker. */
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
