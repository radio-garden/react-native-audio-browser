package com.audiobrowser.destination.sonos

/**
 * Blocking UPnP control of a single Sonos device — the integration seam between the tested protocol
 * units ([SoapEnvelopes], [SoapClient], [SonosStreamUri], [DidlLite], [SoapResponseParser]) and the
 * [SonosPlayer]. Every call blocks on a SOAP round-trip and throws [SoapException] on failure, so
 * callers invoke it off the main thread (the player uses `Dispatchers.IO`).
 */
class SonosTransport(
  private val device: SonosDevice,
  private val soap: SoapClient,
) {
  /** Loads the stream on the device (`SetAVTransportURI`) without starting playback. */
  fun setUri(
    streamUrl: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUri: String?,
    live: Boolean,
    contentType: String? = null,
  ) {
    val uri = SonosStreamUri.forTransport(streamUrl, contentType)
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

  /** Loads the stream on the device and starts it: `SetAVTransportURI` then `Play`. */
  fun setUriAndPlay(
    streamUrl: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUri: String?,
    live: Boolean,
    contentType: String? = null,
  ) {
    setUri(streamUrl, title, artist, album, artworkUri, live, contentType)
    avt(SoapEnvelopes.play())
  }

  fun play() = avt(SoapEnvelopes.play())

  fun pause() = avt(SoapEnvelopes.pause())

  fun stop() = avt(SoapEnvelopes.stop())

  /** The device's current UPnP `CurrentTransportState`, or null if unreadable. */
  fun getTransportState(): String? =
    SoapResponseParser.transportState(soap.execute(device.avTransportControlUrl, SoapEnvelopes.getTransportInfo()))

  fun setVolume(volume: Int) {
    rc(SoapEnvelopes.setVolume(volume))
  }

  fun getVolume(): Int? =
    SoapResponseParser.volume(soap.execute(device.renderingControlControlUrl, SoapEnvelopes.getVolume()))

  fun setMute(mute: Boolean) {
    rc(SoapEnvelopes.setMute(mute))
  }

  private fun avt(action: SoapAction) {
    soap.execute(device.avTransportControlUrl, action)
  }

  private fun rc(action: SoapAction) {
    soap.execute(device.renderingControlControlUrl, action)
  }
}
