// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests of the public [ChatSession] state machine over a fake transport. Drives
 * the whole `Coordinator` → `ChatService` → `ChatSession` path deterministically on virtual
 * time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {

    private fun TestEnv(): Triple<FakeTransport, FakeRestApi, Configuration> =
        Triple(FakeTransport(), FakeRestApi(), Configuration(apiKey = "key", heartbeatIntervalSeconds = 0))

    @Test
    fun send_rendersPending_thenConfirmed_thenAgentReply() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val (fake, rest, config) = TestEnv()
        val client = PolyMessagingClient.forTest(config, fake, rest, scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent()

        assertTrue(fake.connectUrls.isNotEmpty()) // start() -> session -> connect
        fake.simulateOpen(); runCurrent()
        assertTrue(session.connection.value is ConnectionStatus.Open)

        session.send("hi"); runCurrent()
        assertEquals(1, session.userMessages.size)
        assertEquals(Delivery.PENDING, session.userMessages.first().delivery)

        val localId = fake.sent.filterIsInstance<OutgoingEvent.UserMessage>().first().metadata!!.getValue("local_id")
        fake.simulateMessage(
            MessagingEvent.UserMessage(
                Envelope("e1", 1, 0L, EventMetadata(mapOf("local_id" to localId))),
                UserMessageEchoPayload("srv-1", "hi"),
            ),
        )
        runCurrent()
        assertEquals(Delivery.SENT, session.userMessages.first().delivery)

        fake.simulateMessage(
            MessagingEvent.AgentMessage(
                Envelope("e2", 2, 0L, null),
                AgentMessagePayload("m1", "hello there", null, null, emptyList(), emptyList(), emptyList()),
            ),
        )
        runCurrent()
        assertEquals(1, session.agentMessages.size)
        assertEquals("hello there", session.agentMessages.first().text)
        assertEquals(AgentKind.POLY, session.agentMessages.first().agentKind)
    }

    @Test
    fun agentThinking_setsTyping_thenAgentMessageClearsIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val (fake, rest, config) = TestEnv()
        val client = PolyMessagingClient.forTest(config, fake, rest, scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("t1", 1, 0L, null)))
        runCurrent()
        assertTrue(session.isAgentTyping.value)

        fake.simulateMessage(
            MessagingEvent.AgentMessage(Envelope("a1", 2, 0L, null), AgentMessagePayload("m1", "done", null, null, emptyList(), emptyList(), emptyList())),
        )
        runCurrent()
        assertTrue(!session.isAgentTyping.value)
    }

    @Test
    fun handoff_rendersSystemPills_andLiveAgentBubble() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val (fake, rest, config) = TestEnv()
        val client = PolyMessagingClient.forTest(config, fake, rest, scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        fake.simulateMessage(MessagingEvent.AgentTriggeredHandoff(Envelope("h1", 1, 0L, null)))
        fake.simulateMessage(
            MessagingEvent.LiveAgentJoined(Envelope("h2", 2, 0L, null), LiveAgentJoinedPayload(null, "Sam", null)),
        )
        fake.simulateMessage(
            MessagingEvent.LiveAgentMessage(
                Envelope("h3", 3, 0L, null),
                LiveAgentMessagePayload("lm1", "I can help", null, "Sam", null, emptyList(), emptyList(), emptyList()),
            ),
        )
        runCurrent()

        assertTrue(session.systemMessages.any { it.event is SystemEvent.HandoffStarted })
        assertTrue(session.systemMessages.any { it.event is SystemEvent.LiveAgentJoined })
        val live = session.agentMessages.single()
        assertEquals(AgentKind.LIVE, live.agentKind)
        assertEquals("I can help", live.text)
    }

    @Test
    fun streamingChunks_renderProgressively_thenReplaceWithAssembled() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val (fake, rest, config) = TestEnv()
        val client = PolyMessagingClient.forTest(config, fake, rest, scope) // streamingEnabled defaults true
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        fake.simulateMessage(MessagingEvent.AgentMessageChunk(Envelope("c0", 1, 0L, null), AgentMessageChunkPayload("m1", "Hel", 0, false, emptyList(), emptyList())))
        fake.simulateMessage(MessagingEvent.AgentMessageChunk(Envelope("c1", 2, 0L, null), AgentMessageChunkPayload("m1", "lo", 1, true, emptyList(), emptyList())))
        runCurrent()

        // Exactly one agent bubble, carrying the assembled text (chunks replaced in place).
        assertEquals(1, session.agentMessages.size)
        assertEquals("Hello", session.agentMessages.single().text.replace(" ", ""))
    }
}
