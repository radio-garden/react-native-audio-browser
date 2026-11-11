package com.audiobrowser.player

import androidx.media3.common.PlaybackException

/**
 * Automatic retry for the local engine (docs/adr/0014): reconnects after a connection ends, paced
 * by [ReconnectPacer], and gives up once [SilenceBudget]'s deadline passes. Main thread only.
 *
 * A connection the load error policy retries in place (an HLS segment, a file of known length) is
 * never paced here: its failures only reach the budget, whose deadline gives up on it as well.
 */
internal class RetryLoop(
  private val clock: () -> Long,
  private val timer: Timer,
  private val engine: Engine,
  maxRetries: Int?,
  recoveryMs: Long,
  firstConnectMs: Long,
  online: Boolean,
) {
  fun interface Timer {
    /** Runs [action] after [delayMs] on the main thread; the returned function cancels it. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
  }

  interface Engine {
    /** Prepares the engine again after a connection ended; it is idle when this is called. */
    fun reconnect()

    /** The next reconnect waits for the network. */
    fun awaitNetwork()

    /**
     * The loop starts or stops keeping the idle engine ([kept]), and [held] while the play intent
     * is up as well: what keeps the device awake and the audio focus for its reconnect is the
     * loop's to hold.
     */
    fun holdChanged(held: Boolean, kept: Boolean)

    /**
     * Stops the engine and surfaces the terminal error, caused by the last connection's failure, if
     * the loop holds one.
     */
    fun giveUp(cause: PlaybackException?)
  }

  private val budget = SilenceBudget(recoveryMs, firstConnectMs, online)
  private val pacer = ReconnectPacer(maxRetries, online)

  private var online = online
  private var playIntent = false
  private var audible = false
  private var connectionStartedAtMs: Long? = null
  private var deliveredAudio = false

  /** When the policy reported that the current connection ended; its error has yet to surface. */
  private var failedAtMs: Long? = null

  /**
   * Whether the policy reported the current connection's end. Outlives [failedAtMs] when the engine
   * is stopped, as its error may already be on its way.
   */
  private var endReported = false

  /** Whether the loop took a connection's error and left the engine idle until it reconnects. */
  private var holdsIdleEngine = false

  /** The error that ended the current connection: the cause the loop gives up with. */
  private var lastError: PlaybackException? = null

  private var waitingForNetwork = false
  private var holding = false
  private var keeping = false
  private var pendingReconnect: (() -> Unit)? = null
  private var cancelDeadline: (() -> Unit)? = null

  /** Gave up, or suspended: nothing runs until the next [loadStarted]. */
  var stopped = false
    private set

  /** When the pending reconnect goes out; null while none is scheduled. */
  var reconnectDueMs: Long? = null
    private set

  /**
   * Whether the loop holds the engine between connections: a failed connection whose error has not
   * surfaced, or an idle engine it will reconnect. Its idle engine and error are the loop's, not
   * the listener's.
   */
  val reconnecting: Boolean
    get() = failedAtMs != null || (holdsIdleEngine && playIntent)

  /** Whether the current connection failed and its error has not surfaced yet. */
  val failurePending: Boolean
    get() = failedAtMs != null

  /** When the loop gives up unless audio plays first; null while none is due. */
  val deadlineMs: Long?
    get() = budget.deadlineMs

  /**
   * A new Load: a track change, a restart from the terminal error, or the local engine taking over
   * from a remote output. Its first connection is starting. Report [isPlayingChanged] after it when
   * the engine already plays, as on a gapless track change.
   */
  fun loadStarted() {
    val now = clock()
    cancelAll()
    stopped = false
    failedAtMs = null
    endReported = false
    holdsIdleEngine = false
    lastError = null
    budget.loadStarted(now)
    pacer.reset()
    // The audio that plays is the last Load's: the wiring reports this one's when it plays.
    audible = false
    deliveredAudio = false
    connectionStartedAtMs = now
    rearmDeadline()
    syncHold()
  }

  /** Stops the loop until the next [loadStarted]: a terminal error, a stop, or a remote output. */
  fun suspend() {
    cancelAll()
    failedAtMs = null
    holdsIdleEngine = false
    stopped = true
    syncHold()
  }

  /**
   * A play intent that comes back up over an idle engine the loop holds reconnects it: a pause
   * prepares the engine itself, but a transient focus loss ends without one.
   */
  fun playIntentChanged(up: Boolean) {
    if (up == playIntent) return
    playIntent = up
    budget.playIntentChanged(up, clock())
    pacer.playIntentChanged(up)
    if (stopped) return
    if (up) {
      if (holdsIdleEngine) scheduleReconnect()
    } else {
      cancelReconnect()
    }
    rearmDeadline()
    syncHold()
  }

  /** The local engine plays audio, or stops; a READY blip the wiring hides is not audio. */
  fun isPlayingChanged(playing: Boolean) {
    if (playing == audible) return
    audible = playing
    if (stopped) return
    if (playing) {
      deliveredAudio = true
      budget.audioStarted(clock())
    } else {
      budget.audioStopped(clock())
    }
    rearmDeadline()
  }

  fun onlineChanged(online: Boolean) {
    if (online == this.online) return
    this.online = online
    budget.onlineChanged(online)
    pacer.onlineChanged(online)
    if (stopped) return
    if (!online && pendingReconnect != null) {
      cancelReconnect()
      waitingForNetwork = true
    }
    if (online && waitingForNetwork) {
      waitingForNetwork = false
      scheduleReconnect()
    }
    rearmDeadline()
  }

  /** The engine left idle: a connection starts, whoever prepared it. */
  fun connectionStarted() {
    if (stopped) return
    cancelReconnect()
    holdsIdleEngine = false
    connectionStartedAtMs = clock()
    deliveredAudio = audible
    failedAtMs = null
    endReported = false
    lastError = null
    syncHold()
  }

  /**
   * The engine was stopped, not failed: a pause over a retrying error. What the loop held of it is
   * dropped; the next prepare is a connection like any other.
   */
  fun engineStopped() {
    cancelReconnect()
    failedAtMs = null
    holdsIdleEngine = false
    syncHold()
  }

  /**
   * The load error policy saw a recoverable failure, at [atMs]. [inPlace] when media3 retries it
   * within its connection; otherwise the connection is over, and its error surfaces once the buffer
   * has played out.
   */
  fun connectionFailed(inPlace: Boolean, atMs: Long) {
    if (stopped) return
    budget.connectionEnded(atMs)
    if (!inPlace && !endReported) {
      endReported = true
      failedAtMs = atMs
    }
    rearmDeadline()
  }

  /**
   * An error the engine surfaced. True when the loop takes it: the end of a connection it was told
   * of, or a stuck engine, which it reconnects as a plain connection end. A connection's end is
   * paced by all the audio it delivered, the buffer it left included.
   */
  fun onPlayerError(error: PlaybackException?, stuck: Boolean): Boolean {
    if (stopped || (!endReported && !stuck)) return false
    val now = clock()
    val endedAtMs = failedAtMs ?: now
    if (!endReported) budget.connectionEnded(now)
    endReported = true
    failedAtMs = null
    lastError = error
    if (audible) isPlayingChanged(false)
    pacer.connectionEnded(deliveredAudio, connectionStartedAtMs ?: endedAtMs, endedAtMs)
    holdsIdleEngine = true
    if (playIntent) scheduleReconnect()
    rearmDeadline()
    syncHold()
    return true
  }

  private fun scheduleReconnect() {
    cancelReconnect()
    when (val wait = pacer.wait(clock())) {
      ReconnectWait.GiveUp -> giveUp()
      ReconnectWait.UntilOnline -> {
        waitingForNetwork = true
        engine.awaitNetwork()
      }
      is ReconnectWait.After -> {
        lateinit var cancel: () -> Unit
        cancel =
          timer.schedule(wait.ms) {
            if (pendingReconnect === cancel) {
              pendingReconnect = null
              reconnectDueMs = null
              engine.reconnect()
            }
          }
        pendingReconnect = cancel
        reconnectDueMs = clock() + wait.ms
      }
    }
  }

  private fun cancelReconnect() {
    pendingReconnect?.invoke()
    pendingReconnect = null
    reconnectDueMs = null
    waitingForNetwork = false
  }

  private fun rearmDeadline() {
    cancelDeadline?.invoke()
    cancelDeadline = null
    if (stopped) return
    val deadline = budget.deadlineMs ?: return
    cancelDeadline =
      timer.schedule(maxOf(0, deadline - clock())) {
        cancelDeadline = null
        giveUp()
      }
  }

  private fun giveUp() {
    cancelAll()
    failedAtMs = null
    holdsIdleEngine = false
    stopped = true
    engine.giveUp(lastError)
    syncHold()
  }

  private fun syncHold() {
    val kept = holdsIdleEngine && !stopped
    val held = kept && playIntent
    if (held == holding && kept == keeping) return
    holding = held
    keeping = kept
    engine.holdChanged(held, kept)
  }

  private fun cancelAll() {
    cancelReconnect()
    cancelDeadline?.invoke()
    cancelDeadline = null
  }
}
