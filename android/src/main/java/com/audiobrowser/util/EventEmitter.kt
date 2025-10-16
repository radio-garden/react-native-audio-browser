package com.audiobrowser.util

/**
 * A lightweight, generic event emitter for managing listeners within a single process or module.
 *
 * Each listener is registered via [addListener] and receives events emitted through [emit].
 * The returned function from [addListener] can be called to unsubscribe the listener.
 *
 * Example:
 * ```
 * val emitter = EventEmitter<String>()
 * val unsubscribe = emitter.addListener { println("Received: $it") }
 * emitter.emit("Hello")  // prints "Received: Hello"
 * unsubscribe()          // removes the listener
 * ```
 *
 * This class is not thread-safe; use it only from a single thread or dispatcher.
 *
 * @param T The type of event payloads emitted.
 */
class EventEmitter<T> {
    private var nextId = 0

    private val listeners = mutableMapOf<Int, (T) -> Unit>()

    /**
     * Adds a new listener.
     *
     * @param listener The callback to invoke when [emit] is called.
     * @return A function that removes this listener when invoked.
     *
     * Example:
     * ```
     * val unsubscribe = emitter.addListener { event -> println(event) }
     * ...
     * unsubscribe() // stops receiving events
     * ```
     */
    fun addListener(listener: (T) -> Unit): () -> Unit {
        val id = nextId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    /**
     * Emits an event to all current listeners.
     *
     * @param event The event payload to deliver.
     *
     * Each listener receives the event synchronously on the same thread
     * that calls [emit].
     */
    fun emit(event: T) {
        listeners.values.forEach { it(event) }
    }
}