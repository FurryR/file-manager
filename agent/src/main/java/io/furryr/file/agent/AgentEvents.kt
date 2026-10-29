package io.furryr.file.agent

import io.furryr.file.proto.hub.SessionTitle
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** Process-wide fan-out for session metadata pushed to connected agents. */
object AgentEvents {
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<SessionTitle>>()

    fun subscribe(): Channel<SessionTitle> =
        Channel<SessionTitle>(Channel.UNLIMITED).also(subscribers::add)

    fun unsubscribe(channel: Channel<SessionTitle>) {
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
