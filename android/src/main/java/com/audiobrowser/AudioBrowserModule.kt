package com.audiobrowser

import com.margelo.nitro.audiobrowser.BrowserItem
import com.margelo.nitro.audiobrowser.BrowserTrack
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackProgress
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.core.Promise

class AudioBrowserModule: HybridAudioBrowserSpec() {
//    override fun configure(config: BrowserConfig) {
//        TODO("Not yet implemented")
//    }

    override fun navigate(path: String) {
        TODO("Not yet implemented")
    }

    override fun getCurrentItem(): BrowserItem {
        TODO("Not yet implemented")
    }

    override fun search(query: String): Promise<Array<BrowserTrack>> {
        TODO("Not yet implemented")
    }

    override fun play() {
        TODO("Not yet implemented")
    }

    override fun pause() {
        TODO("Not yet implemented")
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    override fun setVolume(volume: Double) {
        TODO("Not yet implemented")
    }

    override fun getPlaybackProgress(): PlaybackProgress {
        TODO("Not yet implemented")
    }

    override fun getPlaybackError(): PlaybackError? {
        TODO("Not yet implemented")
    }

    override fun getPlaybackState(): PlaybackState {
        TODO("Not yet implemented")
    }

    override fun load(track: BrowserTrack) {
        TODO("Not yet implemented")
    }

    override fun add(
        tracks: Array<BrowserTrack>,
        index: Double?
    ) {
        TODO("Not yet implemented")
    }

    override fun getQueue(): Array<BrowserTrack> {
        TODO("Not yet implemented")
    }

    override fun getCurrentTrack(): BrowserTrack? {
        TODO("Not yet implemented")
    }

    override fun getCurrentIndex(): Double {
        TODO("Not yet implemented")
    }

    override fun setQueue(
        tracks: Array<BrowserTrack>,
        startIndex: Double?
    ) {
        TODO("Not yet implemented")
    }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun setRepeatMode(mode: RepeatMode) {
        TODO("Not yet implemented")
    }

}
