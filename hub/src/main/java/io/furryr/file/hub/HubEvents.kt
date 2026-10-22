package io.furryr.file.hub

import io.furryr.file.proto.hub.SessionTitle
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** Process-wide fan-out for session metadata pushed to connected agents. */
object HubEvents {
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<SessionTitle>>()

    internal fun subscribe(): Channel<SessionTitle> =
        Channel<SessionTitle>(Channel.UNLIMITED).also(subscribers::add)

    internal fun unsubscribe(channel: Channel<SessionTitle>) {
        subscribers.remove(channel)
        channel.close()
    }

    fun publishTitle(sessionId: String, title: String) {
        if (sessionId.isBlank() || title.isBlank()) return
        val event = SessionTitle.newBuilder()
            .setSessionId(sessionId)
            .setTitle(title)
            .build()
        for (subscriber in subscribers) {
            subscriber.trySend(event)
        }
    }
}
