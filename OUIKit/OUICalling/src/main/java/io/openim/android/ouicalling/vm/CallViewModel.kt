package io.openim.android.ouicalling.vm

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.*
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.*
import io.livekit.android.room.track.video.ViewVisibility
import io.livekit.android.util.flow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.github.ajalt.timberkt.Timber
import livekit.LivekitRtc
import kotlinx.coroutines.flow.collectLatest as collectLatest1


@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModel(
    application: Application
) : AndroidViewModel(application) {
    val room = LiveKit.create(
        appContext = application,
        options = RoomOptions(
            adaptiveStream = true, dynacast = true,
//            videoTrackPublishDefaults = VideoTrackPublishDefaults(
//                videoCodec = VideoCodec.VP9.codecName
//            )
        ),
    )

    val audioHandler = room.audioHandler as AudioSwitchHandler

    val allParticipants = room::remoteParticipants.flow.map { remoteParticipants ->
        listOf<Participant>(room.localParticipant) + remoteParticipants.keys.sortedBy { it.value }.mapNotNull { remoteParticipants[it] }
    }
    val remoteParticipants = room::remoteParticipants.flow
    var singleRemotePar: RemoteParticipant? = null

    private val scopes = mutableListOf<CoroutineScope>()
    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    private val mutablePrimarySpeaker = MutableStateFlow<Participant?>(null)
    val primarySpeaker: StateFlow<Participant?> = mutablePrimarySpeaker

    private val activeSpeakers = room::activeSpeakers.flow
    val roomMetadata = room::metadata.flow

    private var localScreencastTrack: LocalScreencastVideoTrack? = null

    private val mutableMicEnabled = MutableLiveData(true)
    val micEnabled = mutableMicEnabled.hide()

    private val mutableCameraEnabled = MutableLiveData(true)
    val cameraEnabled = mutableCameraEnabled.hide()

    private val mutableFlipVideoButtonEnabled = MutableLiveData(true)
    val flipButtonVideoEnabled = mutableFlipVideoButtonEnabled.hide()

    private val mutableScreencastEnabled = MutableLiveData(false)
    val screenshareEnabled = mutableScreencastEnabled.hide()

    private val mutableDataReceived = MutableSharedFlow<String>()
    val dataReceived = mutableDataReceived

    private val mutablePermissionAllowed = MutableStateFlow(true)
    val permissionAllowed = mutablePermissionAllowed.hide()


    init {
        viewModelScope.launch {
            // Collect any errors.
            launch {
                error.collect { Timber.e(it) }
            }

            // Handle any changes in speakers.
            launch {
                combine(allParticipants, activeSpeakers) { participants, speakers -> participants to speakers }.collect { (participantsList, speakers) ->
                    handlePrimarySpeaker(
                        participantsList,
                        speakers,
                        room,
                    )
                }
            }

            launch {
                room.events.collect {
                    when (it) {
                        is RoomEvent.FailedToConnect -> mutableError.value = it.error
                        is RoomEvent.DataReceived -> {
                            val identity = it.participant?.identity ?: "server"
                            val message = it.data.toString(Charsets.UTF_8)
                            mutableDataReceived.emit("$identity: $message")
                        }

                        else -> {
                            Timber.e { "Room event: $it" }
                        }
                    }
                }
            }

        }
    }

    private suspend fun collectTrackStats(event: RoomEvent.TrackSubscribed) {
        val pub = event.publication
        while (true) {
            delay(10000)
            if (pub.subscribed) {
                val statsReport = pub.track?.getRTCStats() ?: continue
                Timber.e { "stats for ${pub.sid}:" }

                for (entry in statsReport.statsMap) {
                    Timber.e { "${entry.key} = ${entry.value}" }
                }
            }
        }
    }

    lateinit var url: String
    lateinit var token: String
    suspend fun connectToRoom(url: String, token: String) {
        this@CallViewModel.url = url
        this@CallViewModel.token = token
        try {
            room.connect(
                url = url,
                token = token,
            )
            // Create and publish audio/video tracks
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            mutableMicEnabled.postValue(localParticipant.isMicrophoneEnabled())

            localParticipant.setCameraEnabled(true)
            mutableCameraEnabled.postValue(localParticipant.isCameraEnabled())

            handlePrimarySpeaker(emptyList(), emptyList(), room)
            Timber.i{"connect ok"};
        } catch (e: Throwable) {
            mutableError.value = e
            Timber.e{"connect fail"}
        }

    }

    private fun handlePrimarySpeaker(participantsList: List<Participant>, speakers: List<Participant>, room: Room?) {
        var speaker = mutablePrimarySpeaker.value

        // If speaker is local participant (due to defaults),
        // attempt to find another remote speaker to replace with.
        if (speaker is LocalParticipant) {
            val remoteSpeaker = participantsList.filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        // If previous primary speaker leaves
        if (!participantsList.contains(speaker)) {
            // Default to another person in room, or local participant.
            speaker = participantsList.filterIsInstance<RemoteParticipant>().firstOrNull() ?: room?.localParticipant
        }

        if (speakers.isNotEmpty() && !speakers.contains(speaker)) {
            val remoteSpeaker = speakers.filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        mutablePrimarySpeaker.value = speaker
    }

    suspend fun bindRemoteViewRenderer(
        viewRenderer: TextureViewRenderer, participant: Participant, scope: CoroutineScope
    ) {
        // observe videoTracks changes.
        val videoTrackPubFlow = participant::videoTrackPublications.flow.map { participant to it }.flatMapLatest { (participant, videoTracks) ->
            // Prioritize any screenshare streams.
            val trackPublication = participant.getTrackPublication(Track.Source.SCREEN_SHARE) ?: participant.getTrackPublication(Track.Source.CAMERA)
            ?: videoTracks.firstOrNull()?.first
            flowOf(trackPublication)
        }
        scope.launch {
            videoTrackPubFlow.flatMapLatest { pub ->
                if (pub != null) {
                    pub::track.flow
                } else {
                    flowOf(null)
                }
            }.collectLatest1 { videoTrack ->
                val videoTrack = videoTrack as? VideoTrack ?: return@collectLatest1

                bindVideoTrack(viewRenderer, videoTrack)
            }
        }
    }

    fun bindVideoTrack(
        viewRenderer: TextureViewRenderer, videoTrack: VideoTrack
    ) {
        if (null != viewRenderer.tag) {
            val lastTrack = viewRenderer.tag as VideoTrack
//            if (videoTrack == lastTrack) return
            lastTrack.removeRenderer(viewRenderer)
        }
        viewRenderer.tag = videoTrack
        if (videoTrack is RemoteVideoTrack) {
            videoTrack.addRenderer(
                viewRenderer, ViewVisibility(viewRenderer.rootView)
            )
        } else {
            videoTrack.addRenderer(viewRenderer)
        }
    }


    fun getVideoTrack(participant: Participant): VideoTrack? {
        return participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    fun startScreenCapture(mediaProjectionPermissionResultData: Intent) {
        val localParticipant = room.localParticipant
        viewModelScope.launch {
            val screencastTrack = localParticipant.createScreencastTrack(mediaProjectionPermissionResultData = mediaProjectionPermissionResultData)
            localParticipant.publishVideoTrack(
                screencastTrack
            )

            // Must start the foreground prior to startCapture.
            screencastTrack.startForegroundService(null, null)
            screencastTrack.startCapture()

            this@CallViewModel.localScreencastTrack = screencastTrack
            mutableScreencastEnabled.postValue(screencastTrack.enabled)
        }
    }


    fun stopScreenCapture() {
        viewModelScope.launch {
            localScreencastTrack?.let { localScreencastVideoTrack ->
                localScreencastVideoTrack.stop()
                room.localParticipant.unpublishTrack(localScreencastVideoTrack)
                mutableScreencastEnabled.postValue(localScreencastTrack?.enabled ?: false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }

    fun release() {
        try {
            scopes.forEach { it.cancel() }
            scopes.clear()
            room.disconnect()
            room.release()
        } catch (_: Exception) {
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        viewModelScope.launch {
            room.localParticipant.setMicrophoneEnabled(enabled)
            mutableMicEnabled.postValue(enabled)
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch {
            room.localParticipant.setCameraEnabled(enabled)
            mutableCameraEnabled.postValue(enabled)
        }
    }

    fun flipCamera() {
        val videoTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return

        val newPosition = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }

        try {
            videoTrack.switchCamera(position = newPosition)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getActiveSpeakersFlow(): StateFlow<List<Participant>> {
        return room::activeSpeakers.flow
    }

    fun dismissError() {
        mutableError.value = null
    }


    fun buildScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main);
        scopes.add(scope)
        return scope;
    }

    fun scopeCancel(scope: CoroutineScope) {
        scope.cancel()
        scopes.remove(scope)
    }

    @JvmOverloads
    fun <T> subscribe(
        flow: Flow<T>, function: (T) -> Any,
        scope: CoroutineScope = viewModelScope,
    ) {
        scopes.add(scope)
        scope.launch {
            flow.collect {
                function.invoke(it)
            }
        }
    }


    fun getConnectionFlow(p: Participant): StateFlow<ConnectionQuality> {
        return p::connectionQuality.flow
    }

    fun sendData(message: String) {
        viewModelScope.launch {
            room.localParticipant.publishData(message.toByteArray(Charsets.UTF_8))
        }
    }

    fun toggleSubscriptionPermissions() {
        mutablePermissionAllowed.value = !mutablePermissionAllowed.value
        room.localParticipant.setTrackSubscriptionPermissions(mutablePermissionAllowed.value)
    }

    fun simulateMigration() {
        room.sendSimulateScenario(
            LivekitRtc.SimulateScenario.newBuilder().setMigration(true).build()
        )
    }

    fun reconnect() {
        mutablePrimarySpeaker.value = null
        room.disconnect()
        viewModelScope.launch {
            connectToRoom(url, token)
        }
    }


}

public fun <T> LiveData<T>.hide(): LiveData<T> = this
public fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this
public fun <T> Flow<T>.hide(): Flow<T> = this
 fun Participant.getIdentity(): String {
    if (null != this.identity)
        return this.identity!!.value
    return ""
}

