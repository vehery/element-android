/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.room.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import im.vector.matrix.android.InstrumentedTest
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageTextContent
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.api.session.room.sender.SenderInfo
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.internal.session.room.send.pills.MentionLinkSpecComparator
import im.vector.matrix.android.internal.session.room.send.pills.TextPillsUtils
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.util.StringProvider
import im.vector.matrix.android.testCoroutineDispatchers
import org.amshove.kluent.shouldBe
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.TextContentRenderer
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@Suppress("SpellCheckingInspection")
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
internal class LocalEchoEventFactoryTest : InstrumentedTest {

    private val markdownParser = MarkdownParser(
            Parser.builder().build(),
            HtmlRenderer.builder().build(),
            TextContentRenderer.builder().build()
    )

    private val localEchoEventFactory = LocalEchoEventFactory(
            context = context(),
            userId = "user@test:org",
            stringProvider = StringProvider(context().resources),
            markdownParser = markdownParser,
            localEchoRepository = NoopLocalEchoRepository(),
            taskExecutor = TaskExecutor(testCoroutineDispatchers),
            textPillsUtils = TextPillsUtils(MentionLinkSpecComparator())
    )

    @Test
    fun replyToReply_shouldRemoveInnerTags() {
        val roomId = "room_id"
        val firstText = "Test message"
        val replyResponse = "Reply"
        val eventToReply = localEchoEventFactory.createTextEvent(
                roomId = roomId,
                msgType = MessageType.MSGTYPE_TEXT,
                text = firstText,
                autoMarkdown = false
        ).toTimelineEvent()
        val firstReplyEvent = localEchoEventFactory.createReplyTextEvent(
                roomId = roomId,
                eventReplied = eventToReply,
                replyText = replyResponse,
                autoMarkdown = false
        )!!
        LocalEchoEventFactory.MX_REPLY_REGEX.findAll(firstReplyEvent.content.toModel<MessageTextContent>()?.matrixFormattedBody!!)
                .toList().size shouldBe 1
        val replyOfReplyEvent = localEchoEventFactory.createReplyTextEvent(
                roomId = roomId,
                eventReplied = firstReplyEvent.toTimelineEvent(),
                replyText = replyResponse,
                autoMarkdown = false
        )!!
        LocalEchoEventFactory.MX_REPLY_REGEX.findAll(replyOfReplyEvent.content.toModel<MessageTextContent>()?.matrixFormattedBody!!)
                .toList().size shouldBe 1
    }

    private fun Event.toTimelineEvent(): TimelineEvent {
        return TimelineEvent(
                root = this,
                eventId = this.eventId!!,
                annotations = null,
                displayIndex = 0,
                localId = 0L,
                readReceipts = emptyList(),
                senderInfo = SenderInfo("userId@test.org", displayName = null, isUniqueDisplayName = true, avatarUrl = null)
        )
    }
}
