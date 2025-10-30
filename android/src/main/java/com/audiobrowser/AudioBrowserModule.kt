package com.audiobrowser

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.BrowserItem
import com.margelo.nitro.audiobrowser.BrowserTrack
import com.margelo.nitro.audiobrowser.GetChildrenRequest
import com.margelo.nitro.audiobrowser.GetItemRequest
import com.margelo.nitro.audiobrowser.GetSearchResultRequest
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackProgress
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayerOptions
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.Progress
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise

class AudioBrowserModule(
    override val onGetItemRequest: ((GetItemRequest) -> Unit) -> Promise<() -> Unit>,
    override val onGetChildrenRequest: ((GetChildrenRequest) -> Unit) -> Promise<() -> Unit>,
    override val onGetSearchResultRequest: ((GetSearchResultRequest) -> Unit) -> Promise<() -> Unit>
): HybridAudioBrowserSpec() {
    override fun setupPlayer(options: PlayerOptions): Promise<Unit> {
        TODO("Not yet implemented")
    }

    override fun load(track: Track) {
        TODO("Not yet implemented")
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

    override fun play() {
        TODO("Not yet implemented")
    }

    override fun pause() {
        TODO("Not yet implemented")
    }

    override fun togglePlayback() {
        TODO("Not yet implemented")
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        TODO("Not yet implemented")
    }

    override fun getPlayWhenReady(): Boolean {
        TODO("Not yet implemented")
    }

    override fun seekTo(position: Double) {
        TODO("Not yet implemented")
    }

    override fun seekBy(offset: Double) {
        TODO("Not yet implemented")
    }

    override fun setVolume(level: Double) {
        TODO("Not yet implemented")
    }

    override fun getVolume(): Double {
        TODO("Not yet implemented")
    }

    override fun setRate(rate: Double) {
        TODO("Not yet implemented")
    }

    override fun getRate(): Double {
        TODO("Not yet implemented")
    }

    override fun getProgress(): Progress {
        TODO("Not yet implemented")
    }

    override fun getPlaybackState(): PlaybackState {
        TODO("Not yet implemented")
    }

    override fun getPlayingState(): PlayingState {
        TODO("Not yet implemented")
    }

    override fun getRepeatMode(): RepeatMode {
        TODO("Not yet implemented")
    }

    override fun setRepeatMode(mode: RepeatMode) {
        TODO("Not yet implemented")
    }

    override fun getPlaybackError(): PlaybackError? {
        TODO("Not yet implemented")
    }

    override fun retry() {
        TODO("Not yet implemented")
    }

    override fun add(
        tracks: Array<Track>,
        insertBeforeIndex: Double?
    ) {
        TODO("Not yet implemented")
    }

    override fun move(fromIndex: Double, toIndex: Double) {
        TODO("Not yet implemented")
    }

    override fun remove(indexes: DoubleArray) {
        TODO("Not yet implemented")
    }

    override fun removeUpcomingTracks() {
        TODO("Not yet implemented")
    }

    override fun skip(index: Double, initialPosition: Double?) {
        TODO("Not yet implemented")
    }

    override fun skipToNext(initialPosition: Double?) {
        TODO("Not yet implemented")
    }

    override fun skipToPrevious(initialPosition: Double?) {
        TODO("Not yet implemented")
    }

    override fun setQueue(tracks: Array<Track>) {
        TODO("Not yet implemented")
    }

    override fun getQueue(): Array<Track> {
        TODO("Not yet implemented")
    }

    override fun getTrack(index: Double): Track? {
        TODO("Not yet implemented")
    }

    override fun getActiveTrackIndex(): Double? {
        TODO("Not yet implemented")
    }

    override fun getActiveTrack(): Track? {
        TODO("Not yet implemented")
    }

    override fun acquireWakeLock() {
        TODO("Not yet implemented")
    }

    override fun abandonWakeLock() {
        TODO("Not yet implemented")
    }

    override fun resolveGetItemRequest(
        id: String,
        item: Track
    ) {
        TODO("Not yet implemented")
    }

    override fun resolveGetChildrenRequest(
        requestId: String,
        items: Array<Track>,
        totalChildrenCount: Double
    ) {
        TODO("Not yet implemented")
    }

    override fun resolveSearchResultRequest(
        requestId: String,
        items: Array<Track>,
        totalMatchesCount: Double
    ) {
        TODO("Not yet implemented")
    }

    override fun setMediaBrowserReady() {
        TODO("Not yet implemented")
    }

    companion object {
        // Static context holder for Nitro modules
        @JvmStatic
        var applicationContext: Context? = null
            private set

        @JvmStatic
        fun setApplicationContext(context: Context) {
            applicationContext = context.applicationContext
        }
    }
}
