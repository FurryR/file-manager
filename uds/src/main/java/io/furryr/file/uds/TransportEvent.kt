package io.furryr.file.uds

/** Lifecycle events emitted on [Transport.events]. */
sealed interface TransportEvent {
    /** An inbound framed payload (with descriptors the receiver owns). */
    class Incoming(val frame: Frame) : TransportEvent

    /** The connection dropped and will not be re-established. */
    class Closed(val cause: Throwable?) : TransportEvent
}
