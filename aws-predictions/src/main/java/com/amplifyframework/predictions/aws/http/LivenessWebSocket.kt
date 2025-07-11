/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.predictions.aws.http

import android.graphics.RectF
import android.os.Build
import androidx.annotation.VisibleForTesting
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.emptyAttributes
import com.amplifyframework.AmplifyException
import com.amplifyframework.core.Action
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.Consumer
import com.amplifyframework.core.category.CategoryType
import com.amplifyframework.predictions.PredictionsException
import com.amplifyframework.predictions.aws.BuildConfig
import com.amplifyframework.predictions.aws.exceptions.AccessDeniedException
import com.amplifyframework.predictions.aws.exceptions.FaceLivenessSessionNotFoundException
import com.amplifyframework.predictions.aws.exceptions.FaceLivenessUnsupportedChallengeTypeException
import com.amplifyframework.predictions.aws.models.liveness.BoundingBox
import com.amplifyframework.predictions.aws.models.liveness.ClientChallenge
import com.amplifyframework.predictions.aws.models.liveness.ClientSessionInformationEvent
import com.amplifyframework.predictions.aws.models.liveness.ColorDisplayed
import com.amplifyframework.predictions.aws.models.liveness.FaceMovementAndLightClientChallenge
import com.amplifyframework.predictions.aws.models.liveness.FaceMovementClientChallenge
import com.amplifyframework.predictions.aws.models.liveness.FreshnessColor
import com.amplifyframework.predictions.aws.models.liveness.InitialFace
import com.amplifyframework.predictions.aws.models.liveness.InvalidSignatureException
import com.amplifyframework.predictions.aws.models.liveness.LivenessResponseStream
import com.amplifyframework.predictions.aws.models.liveness.SessionInformation
import com.amplifyframework.predictions.aws.models.liveness.TargetFace
import com.amplifyframework.predictions.aws.models.liveness.VideoEvent
import com.amplifyframework.predictions.models.Challenge
import com.amplifyframework.predictions.models.FaceLivenessChallengeType
import com.amplifyframework.predictions.models.FaceLivenessSessionInformation
import com.amplifyframework.util.UserAgent
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

internal class LivenessWebSocket(
    val credentialsProvider: CredentialsProvider,
    val endpoint: String,
    val region: String,
    val clientSessionInformation: FaceLivenessSessionInformation,
    val livenessVersion: String?,
    val onSessionResponseReceived: Consumer<SessionResponse>,
    val onErrorReceived: Consumer<PredictionsException>,
    val onComplete: Action
) {
    internal data class SessionResponse(
        val faceLivenessSession: SessionInformation,
        val livenessChallengeType: FaceLivenessChallengeType
    )

    private val signer = AWSV4Signer()
    private var credentials: Credentials? = null

    // The reported time difference between the server and client. Only set if diff is higher than 4 minutes
    internal var timeDiffOffsetInMillis = 0L
    internal enum class ConnectionState {
        NORMAL,
        ATTEMPT_RECONNECT
    }
    internal var reconnectState = ConnectionState.NORMAL

    @VisibleForTesting
    internal var webSocket: WebSocket? = null
    internal val challengeId = UUID.randomUUID().toString()
    var challengeType: FaceLivenessChallengeType? = null
    private var initialDetectedFace: BoundingBox? = null
    private var faceDetectedStart = 0L
    private var videoStartTimestamp = 0L
    private var videoEndTimestamp = 0L

    @VisibleForTesting internal var webSocketError: PredictionsException? = null
    internal var clientStoppedSession = false
    val json = Json { ignoreUnknownKeys = true }

    // Sending events to the websocket requires processing synchronously because we rely on proper ordered
    // prior signatures. When sending events, we send each of these events to an async queue to process 1 at a time.
    private val sendEventScope = CoroutineScope(Job() + Dispatchers.IO)
    private val sendEventQueueChannel = Channel<Job>(capacity = Channel.UNLIMITED).apply {
        sendEventScope.launch {
            consumeEach { it.join() }
        }
    }

    @VisibleForTesting
    internal var webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            LOG.debug("WebSocket onOpen")

            // device time may be set incorrectly; read the header to skew time and retry
            val sdf = SimpleDateFormat(datePattern, Locale.US)
            val date = response.header("Date")?.let { sdf.parse(it) }
            val tempOffset = if (date != null) {
                date.time - adjustedDate()
            } else {
                0
            }

            super.onOpen(webSocket, response)

            this@LivenessWebSocket.webSocket = webSocket

            // If offset is > 4 minutes, server may reject the request
            // The real allowed diff from serer is < 5 but we check for 4 to add a buffer
            if (!isTimeDiffSafe(tempOffset)) {
                LOG.info("Server reported a time difference between client and server of > 4 minutes")
                timeDiffOffsetInMillis = tempOffset
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            LOG.debug("WebSocket onMessage text")
            super.onMessage(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            LOG.debug("WebSocket onMessage bytes")
            try {
                when (val response = LivenessEventStream.decode(bytes, json)) {
                    is LivenessResponseStream.Event -> {
                        if (response.challengeEvent != null) {
                            challengeType = response.challengeEvent.challengeType
                        } else if (response.serverSessionInformationEvent != null) {
                            val clientRequestedOldLightChallenge = clientSessionInformation.challengeVersions
                                .any { it == Challenge.FaceMovementAndLightChallenge("1.0.0") }

                            if (challengeType == null && clientRequestedOldLightChallenge) {
                                // For the  1.0.0 version of FaceMovementAndLight challenge, backend doesn't send a
                                // ChallengeEvent so we need to manually check and set it if that specific challenge
                                // was requested.
                                challengeType = FaceLivenessChallengeType.FaceMovementAndLightChallenge
                            }

                            // If challengeType hasn't been initialized by this point it's because server sent an
                            // unsupported challenge type so return an error to the client and close the web socket.
                            val resolvedChallengeType = challengeType
                            if (resolvedChallengeType == null) {
                                webSocketError = FaceLivenessUnsupportedChallengeTypeException()
                                destroy(UNSUPPORTED_CHALLENGE_CLOSURE_STATUS_CODE)
                            } else {
                                onSessionResponseReceived.accept(
                                    SessionResponse(
                                        response.serverSessionInformationEvent.sessionInformation,
                                        resolvedChallengeType
                                    )
                                )
                            }
                        } else if (response.disconnectionEvent != null) {
                            this@LivenessWebSocket.webSocket?.close(
                                NORMAL_SOCKET_CLOSURE_STATUS_CODE,
                                "Liveness flow completed."
                            )
                        } else {
                            LOG.debug("WebSocket received unknown event-type: message from server")
                        }
                    }
                    is LivenessResponseStream.Exception -> {
                        handleWebSocketError(response)
                    }
                    else -> {
                        LOG.debug("WebSocket unable to decode message from server")
                    }
                }
            } catch (e: Exception) {
                LOG.debug("WebSocket unable to decode message from server")
            }

            super.onMessage(webSocket, bytes)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            LOG.debug("WebSocket onClosing")
            super.onClosing(webSocket, code, reason)
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            LOG.debug("WebSocket onClosed")
            super.onClosed(webSocket, code, reason)
            if (code != NORMAL_SOCKET_CLOSURE_STATUS_CODE && !clientStoppedSession) {
                val recordedError = webSocketError

                /*
                If the server reports an invalid signature due to a time difference between the local clock and the
                server clock, AND we haven't already tried to reconnect, then we should try to reconnect with an offset
                 */
                if (reconnectState == ConnectionState.NORMAL &&
                    !isTimeDiffSafe(timeDiffOffsetInMillis) &&
                    recordedError is PredictionsException &&
                    recordedError.cause is InvalidSignatureException
                ) {
                    LOG.info("The server rejected the connection due to a likely time difference. Attempting reconnect")
                    reconnectState = ConnectionState.ATTEMPT_RECONNECT
                    webSocketError = null
                    start()
                } else {
                    val faceLivenessException = recordedError ?: PredictionsException(
                        "An error occurred during the face liveness check.",
                        reason
                    )
                    onErrorReceived.accept(faceLivenessException)
                }
            } else {
                onComplete.call()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            LOG.debug("WebSocket onFailure")
            super.onFailure(webSocket, t, response)
            if (!clientStoppedSession) {
                val faceLivenessException = webSocketError ?: PredictionsException(
                    "An unknown error occurred during the Liveness flow.",
                    t,
                    "See attached exception for more details."
                )
                onErrorReceived.accept(faceLivenessException)
            }
        }
    }

    fun start() {
        val userAgent = getUserAgent()

        val okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(
                Interceptor { chain ->
                    val requestWithUserAgent = chain.request().newBuilder()
                        .header("x-amz-user-agent", userAgent)
                        .build()
                    chain.proceed(requestWithUserAgent)
                }
            )
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val credentials = credentialsProvider.resolve(emptyAttributes())
                this@LivenessWebSocket.credentials = credentials
                signer.resetPriorSignature()
                val signedUri = signer.getSignedUri(
                    URI.create(endpoint),
                    credentials,
                    region,
                    userAgent,
                    adjustedDate()
                )
                if (signedUri != null) {
                    val signedEndpoint = URLDecoder.decode(signedUri.toString(), "UTF-8")
                    val signedEndpointNoSpaces = signedEndpoint.replace(" ", signer.encodedSpace)
                    startWebSocket(okHttpClient, signedEndpointNoSpaces)
                } else {
                    onErrorReceived.accept(
                        PredictionsException(
                            "Failed to create the face liveness endpoint.",
                            AmplifyException.TODO_RECOVERY_SUGGESTION
                        )
                    )
                }
            } catch (error: Exception) {
                onErrorReceived.accept(
                    PredictionsException(
                        "Failed to start the face liveness session.",
                        error,
                        AmplifyException.RECOVERY_SUGGESTION_WITH_THROWABLE
                    )
                )
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun getUserAgent(): String {
        val amplifyVersion = BuildConfig.VERSION_NAME
        val deviceManufacturer = Build.MANUFACTURER.replace(" ", "_")
        val deviceName = Build.MODEL.replace(" ", "_")
        var userAgent = "${UserAgent.string()} os/Android/${Build.VERSION.SDK_INT} md/device/$deviceName " +
            "md/device-manufacturer/$deviceManufacturer api/rekognitionstreaming/$amplifyVersion"

        if (!livenessVersion.isNullOrBlank()) {
            userAgent += " api/liveness/$livenessVersion"
        }

        return userAgent.replace(Build.MANUFACTURER, deviceManufacturer).replace(Build.MODEL, deviceName)
            .replace("+", "_")
    }

    private fun startWebSocket(okHttpClient: OkHttpClient, url: String) {
        okHttpClient.newWebSocket(
            Request.Builder().url(url).build(),
            webSocketListener
        )
    }

    private fun handleWebSocketError(livenessResponse: LivenessResponseStream.Exception) {
        webSocketError = if (livenessResponse.validationException != null) {
            PredictionsException(
                "An error occurred during the face liveness flow.",
                livenessResponse.validationException,
                "See attached exception for more details."
            )
        } else if (livenessResponse.internalServerException != null) {
            PredictionsException(
                "An error occurred during the face liveness flow.",
                livenessResponse.internalServerException,
                "Retry the face liveness flow."
            )
        } else if (livenessResponse.throttlingException != null) {
            PredictionsException(
                "Failed since the user made too many requests for a face liveness check.",
                livenessResponse.throttlingException,
                "Make sure the face liveness requests are controlled and errors are properly handled."
            )
        } else if (livenessResponse.serviceQuotaExceededException != null) {
            PredictionsException(
                "Failed since the user made too many requests for a face liveness check.",
                livenessResponse.serviceQuotaExceededException,
                "Make sure the face liveness requests are controlled and errors are properly handled."
            )
        } else if (livenessResponse.serviceUnavailableException != null) {
            PredictionsException(
                "Service is currently unavailable.",
                livenessResponse.serviceUnavailableException,
                "Retry the face liveness check."
            )
        } else if (livenessResponse.sessionNotFoundException != null) {
            FaceLivenessSessionNotFoundException(
                cause = livenessResponse.sessionNotFoundException
            )
        } else if (livenessResponse.accessDeniedException != null) {
            AccessDeniedException(
                cause = livenessResponse.accessDeniedException
            )
        } else if (livenessResponse.unrecognizedClientException != null) {
            PredictionsException(
                "Unrecognized client",
                livenessResponse.unrecognizedClientException,
                "Please check your credentials"
            )
        } else if (livenessResponse.invalidSignatureException != null) {
            PredictionsException(
                "Invalid signature",
                livenessResponse.invalidSignatureException,
                "Please check your credentials"
            )
        } else {
            PredictionsException(
                "An unknown error occurred during the Liveness flow.",
                AmplifyException.TODO_RECOVERY_SUGGESTION
            )
        }
        this.destroy()
    }

    fun sendInitialFaceDetectedEvent(initialFaceRect: RectF, videoStartTime: Long) {
        // Send initial ClientSessionInformationEvent
        videoStartTimestamp = adjustedDate(videoStartTime)
        initialDetectedFace = BoundingBox(
            left = initialFaceRect.left / clientSessionInformation.videoWidth,
            top = initialFaceRect.top / clientSessionInformation.videoHeight,
            height = initialFaceRect.height() / clientSessionInformation.videoHeight,
            width = initialFaceRect.width() / clientSessionInformation.videoWidth
        )
        faceDetectedStart = adjustedDate(videoStartTime)

        val resolvedChallengeType = challengeType
        if (resolvedChallengeType == null) {
            onErrorReceived.accept(
                PredictionsException(
                    "Failed to send an initial face detected event",
                    AmplifyException.TODO_RECOVERY_SUGGESTION
                )
            )
        } else {
            val clientInfoEvent =
                ClientSessionInformationEvent(
                    challenge = buildClientChallenge(
                        challengeType = resolvedChallengeType,
                        challengeId = challengeId,
                        initialFace = InitialFace(
                            boundingBox = initialDetectedFace!!,
                            initialFaceDetectedTimestamp = faceDetectedStart
                        ),
                        videoStartTimestamp = videoStartTimestamp
                    )
                )
            sendClientInfoEvent(clientInfoEvent)
        }
    }

    fun sendFinalEvent(targetFaceRect: RectF, faceMatchedStart: Long, faceMatchedEnd: Long) {
        val resolvedChallengeType = challengeType
        if (resolvedChallengeType == null) {
            onErrorReceived.accept(
                PredictionsException(
                    "Failed to send an initial face detected event",
                    AmplifyException.TODO_RECOVERY_SUGGESTION
                )
            )
        } else {
            val finalClientInfoEvent = ClientSessionInformationEvent(
                challenge = buildClientChallenge(
                    challengeType = resolvedChallengeType,
                    challengeId = challengeId,
                    videoEndTimestamp = videoEndTimestamp,
                    initialFace = InitialFace(
                        boundingBox = initialDetectedFace!!,
                        initialFaceDetectedTimestamp = faceDetectedStart
                    ),
                    targetFace = TargetFace(
                        faceDetectedInTargetPositionStartTimestamp = adjustedDate(faceMatchedStart),
                        faceDetectedInTargetPositionEndTimestamp = adjustedDate(faceMatchedEnd),
                        boundingBox = BoundingBox(
                            left = targetFaceRect.left / clientSessionInformation.videoWidth,
                            top = targetFaceRect.top / clientSessionInformation.videoHeight,
                            height = targetFaceRect.height() / clientSessionInformation.videoHeight,
                            width = targetFaceRect.width() / clientSessionInformation.videoWidth
                        )
                    )
                )
            )
            sendClientInfoEvent(finalClientInfoEvent)
        }
    }

    fun sendColorDisplayedEvent(
        currentColor: FreshnessColor,
        previousColor: FreshnessColor,
        sequenceNumber: Int,
        colorStartTime: Long
    ) {
        val freshnessClientInfo = ClientSessionInformationEvent(
            challenge = ClientChallenge(
                faceMovementAndLightChallenge = FaceMovementAndLightClientChallenge(
                    challengeId = challengeId,
                    colorDisplayed = ColorDisplayed(
                        currentColor = currentColor,
                        previousColor = previousColor,
                        sequenceNumber = sequenceNumber,
                        currentColorStartTimestamp = adjustedDate(colorStartTime)
                    )
                )
            )
        )
        sendClientInfoEvent(freshnessClientInfo)
    }

    private fun sendClientInfoEvent(clientInfoEvent: ClientSessionInformationEvent) {
        // Add event to send queue to ensure proper ordering of signatures
        sendEventQueueChannel.trySend(
            sendEventScope.launch(start = CoroutineStart.LAZY) {
                credentials?.let {
                    val jsonString = Json.encodeToString(clientInfoEvent)
                    val jsonPayload = jsonString.encodeUtf8().toByteArray()
                    val encodedPayload = LivenessEventStream.encode(
                        jsonPayload,
                        mapOf(
                            ":event-type" to "ClientSessionInformationEvent",
                            ":message-type" to "event",
                            ":content-type" to "application/json"
                        )
                    )
                    val eventDate = Date(adjustedDate())
                    val signedPayload = signer.getSignedFrame(
                        region,
                        encodedPayload.array(),
                        it.secretAccessKey,
                        Pair(":date", eventDate)
                    )
                    val signedPayloadBytes = signedPayload.chunked(2).map { hexChar ->
                        hexChar.toInt(16).toByte()
                    }.toByteArray()
                    val encodedRequest = LivenessEventStream.encode(
                        encodedPayload.array(),
                        mapOf(
                            ":date" to eventDate,
                            ":chunk-signature" to signedPayloadBytes
                        )
                    )

                    webSocket?.send(ByteString.of(*encodedRequest.array()))
                }
            }
        )
    }

    fun sendVideoEvent(videoBytes: ByteArray, videoEventTime: Long) {
        // Add event to send queue to ensure proper ordering of signatures
        sendEventQueueChannel.trySend(
            sendEventScope.launch(start = CoroutineStart.LAZY) {
                if (videoBytes.isNotEmpty()) {
                    videoEndTimestamp = adjustedDate(videoEventTime)
                }
                credentials?.let {
                    val videoBuffer = ByteBuffer.wrap(videoBytes)
                    val videoEvent = VideoEvent(
                        timestampMillis = adjustedDate(videoEventTime),
                        videoChunk = videoBuffer
                    )
                    val videoJsonString = Json.encodeToString(videoEvent)
                    val videoJsonPayload = videoJsonString.encodeUtf8().toByteArray()
                    val encodedVideoPayload = LivenessEventStream.encode(
                        videoJsonPayload,
                        mapOf(
                            ":event-type" to "VideoEvent",
                            ":message-type" to "event",
                            ":content-type" to "application/json"
                        )
                    )
                    val videoEventDate = Date(adjustedDate())
                    val signedVideoPayload = signer.getSignedFrame(
                        region,
                        encodedVideoPayload.array(),
                        it.secretAccessKey,
                        Pair(":date", videoEventDate)
                    )
                    val signedVideoPayloadBytes = signedVideoPayload.chunked(2)
                        .map { hexChar -> hexChar.toInt(16).toByte() }.toByteArray()
                    val encodedVideoRequest = LivenessEventStream.encode(
                        encodedVideoPayload.array(),
                        mapOf(
                            ":date" to videoEventDate,
                            ":chunk-signature" to signedVideoPayloadBytes
                        )
                    )
                    webSocket?.send(ByteString.of(*encodedVideoRequest.array()))
                }
            }
        )
    }

    fun destroy(reasonCode: Int = NORMAL_SOCKET_CLOSURE_STATUS_CODE) {
        // Close with provided reason code
        webSocket?.close(reasonCode, null)
    }

    fun adjustedDate(date: Long = Date().time): Long = date + timeDiffOffsetInMillis

    private fun isTimeDiffSafe(diffInMillis: Long) = kotlin.math.abs(diffInMillis) < FOUR_MINUTES

    private fun buildClientChallenge(
        challengeType: FaceLivenessChallengeType,
        challengeId: String,
        videoStartTimestamp: Long? = null,
        videoEndTimestamp: Long? = null,
        initialFace: InitialFace? = null,
        targetFace: TargetFace? = null,
        colorDisplayed: ColorDisplayed? = null
    ): ClientChallenge = when (challengeType) {
        FaceLivenessChallengeType.FaceMovementAndLightChallenge -> {
            ClientChallenge(
                faceMovementAndLightChallenge = FaceMovementAndLightClientChallenge(
                    challengeId = challengeId,
                    videoStartTimestamp = videoStartTimestamp,
                    videoEndTimestamp = videoEndTimestamp,
                    initialFace = initialFace,
                    targetFace = targetFace,
                    colorDisplayed = colorDisplayed
                ),
                faceMovementChallenge = null
            )
        }
        FaceLivenessChallengeType.FaceMovementChallenge -> {
            ClientChallenge(
                faceMovementAndLightChallenge = null,
                faceMovementChallenge = FaceMovementClientChallenge(
                    challengeId = challengeId,
                    videoStartTimestamp = videoStartTimestamp,
                    videoEndTimestamp = videoEndTimestamp,
                    initialFace = initialFace,
                    targetFace = targetFace
                )
            )
        }
    }

    companion object {
        private const val NORMAL_SOCKET_CLOSURE_STATUS_CODE = 1000

        // This is the same as the client-provided 'runtime error' status code
        private const val UNSUPPORTED_CHALLENGE_CLOSURE_STATUS_CODE = 4005
        private const val FOUR_MINUTES = 1000 * 60 * 4

        @VisibleForTesting val datePattern = "EEE, d MMM yyyy HH:mm:ss z"
        private val LOG = Amplify.Logging.logger(CategoryType.PREDICTIONS, "amplify:aws-predictions")
    }
}
