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

package im.vector.matrix.android.internal.crypto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.failure.shouldBeRetried
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.LocalEcho
import im.vector.matrix.android.api.session.events.model.toContent
import im.vector.matrix.android.internal.crypto.model.MXUsersDevicesMap
import im.vector.matrix.android.internal.crypto.model.rest.GossipingToDeviceObject
import im.vector.matrix.android.internal.crypto.model.rest.RoomKeyShareRequest
import im.vector.matrix.android.internal.crypto.model.rest.SecretShareRequest
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import im.vector.matrix.android.internal.crypto.tasks.SendToDeviceTask
import im.vector.matrix.android.internal.worker.WorkerParamsFactory
import im.vector.matrix.android.internal.worker.getSessionComponent
import timber.log.Timber
import javax.inject.Inject

internal class SendGossipRequestWorker(context: Context,
                                       params: WorkerParameters)
    : CoroutineWorker(context, params) {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            val sessionId: String,
            val keyShareRequest: OutgoingRoomKeyRequest? = null,
            val secretShareRequest: OutgoingSecretRequest? = null
    )

    @Inject lateinit var sendToDeviceTask: SendToDeviceTask
    @Inject lateinit var cryptoStore: IMXCryptoStore
    @Inject lateinit var credentials: Credentials

    override suspend fun doWork(): Result {
        val errorOutputData = Data.Builder().putBoolean("failed", true).build()
        val params = WorkerParamsFactory.fromData<Params>(inputData)
                ?: return Result.success(errorOutputData)

        val sessionComponent = getSessionComponent(params.sessionId)
                ?: return Result.success(errorOutputData).also {
                    // TODO, can this happen? should I update local echo?
                    Timber.e("Unknown Session, cannot send message, sessionId: ${params.sessionId}")
                }
        sessionComponent.inject(this)

        val localId = LocalEcho.createLocalEchoId()
        val contentMap = MXUsersDevicesMap<Any>()
        val eventType: String
        val requestId: String
        when {
            params.keyShareRequest != null    -> {
                eventType = EventType.ROOM_KEY_REQUEST
                requestId = params.keyShareRequest.requestId
                val toDeviceContent = RoomKeyShareRequest(
                        requestingDeviceId = credentials.deviceId,
                        requestId = params.keyShareRequest.requestId,
                        action = GossipingToDeviceObject.ACTION_SHARE_REQUEST,
                        body = params.keyShareRequest.requestBody
                )
                cryptoStore.saveGossipingEvent(Event(
                        type = eventType,
                        content = toDeviceContent.toContent(),
                        senderId = credentials.userId
                ).also {
                    it.ageLocalTs = System.currentTimeMillis()
                })

                params.keyShareRequest.recipients.forEach { userToDeviceMap ->
                    userToDeviceMap.value.forEach { deviceId ->
                        contentMap.setObject(userToDeviceMap.key, deviceId, toDeviceContent)
                    }
                }
            }
            params.secretShareRequest != null -> {
                eventType = EventType.REQUEST_SECRET
                requestId = params.secretShareRequest.requestId
                val toDeviceContent = SecretShareRequest(
                        requestingDeviceId = credentials.deviceId,
                        requestId = params.secretShareRequest.requestId,
                        action = GossipingToDeviceObject.ACTION_SHARE_REQUEST,
                        secretName = params.secretShareRequest.secretName
                )

                cryptoStore.saveGossipingEvent(Event(
                        type = eventType,
                        content = toDeviceContent.toContent(),
                        senderId = credentials.userId
                ).also {
                    it.ageLocalTs = System.currentTimeMillis()
                })

                params.secretShareRequest.recipients.forEach { userToDeviceMap ->
                    userToDeviceMap.value.forEach { deviceId ->
                        contentMap.setObject(userToDeviceMap.key, deviceId, toDeviceContent)
                    }
                }
            }
            else                              -> {
                return Result.success(errorOutputData).also {
                    Timber.e("Unknown empty gossiping request: $params")
                }
            }
        }
        try {
            cryptoStore.updateOutgoingGossipingRequestState(requestId, OutgoingGossipingRequestState.SENDING)
            sendToDeviceTask.execute(
                    SendToDeviceTask.Params(
                            eventType = eventType,
                            contentMap = contentMap,
                            transactionId = localId
                    )
            )
            cryptoStore.updateOutgoingGossipingRequestState(requestId, OutgoingGossipingRequestState.SENT)
            return Result.success()
        } catch (exception: Throwable) {
            return if (exception.shouldBeRetried()) {
                Result.retry()
            } else {
                cryptoStore.updateOutgoingGossipingRequestState(requestId, OutgoingGossipingRequestState.FAILED_TO_SEND)
                Result.success(errorOutputData)
            }
        }
    }
}
