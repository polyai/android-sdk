// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * A handle to unregister a listener / cancel an in-flight async call. Returned by every
 * `addXListener(...)` and callback-style method so Java consumers can tear down cleanly.
 */
public fun interface Cancellable {
    public fun cancel()
}

/**
 * Java-friendly completion callback for the callback overloads of the `suspend` API.
 * Delivered on the [java.util.concurrent.Executor] supplied to the call. (Kotlin callers use the `suspend` form instead.)
 */
public interface Callback<T> {
    public fun onSuccess(value: T)
    public fun onError(error: Throwable)
}

/** Java-friendly listener for the typed event stream (`client.events`). */
public fun interface EventListener {
    public fun onEvent(event: MessagingEvent)
}

/** Java-friendly listener for an observable value (delivered immediately, then on change). */
public fun interface ValueListener<T> {
    public fun onChanged(value: T)
}

/** Fired whenever any observable property on the [ChatSession] changes. */
public fun interface ChatSessionListener {
    public fun onChanged(session: ChatSession)
}
