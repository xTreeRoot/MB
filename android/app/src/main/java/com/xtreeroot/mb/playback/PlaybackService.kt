package com.xtreeroot.mb.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.toTrackMetadata
import com.xtreeroot.mb.local.OfflineMediaStore
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.PlaybackRecorder
import com.xtreeroot.mb.music.PlaybackSource
import com.xtreeroot.mb.music.StationPlaybackSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.VideoSource
import com.xtreeroot.mb.music.activeCaption
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(UnstableApi::class)
internal class PlaybackService : MediaSessionService() {
    private val storage by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private data class ResolvedAudio(val until: Long, val source: PlaybackSource, val quality: AudioQuality, val resource: MediaResource)
    private data class AudioLocation(val quality: AudioQuality, val file: OfflineMediaStore.Lease?)
    private data class LoudnessInput(val uri: Uri, val revision: Long)
    private data class AudioTarget(val uri: Uri, val source: PlaybackSource, val quality: AudioQuality)
    private val resolved = mutableMapOf<Uri, ResolvedAudio>()
    private var resolutionEpoch = 0L
    private val locations = mutableMapOf<String, AudioLocation>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recordedTrack: String? = null
    private var reportedSelection = false
    private var currentTrack: String? = null
    private var cacheJob: Job? = null
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var video: VideoPlayback? = null
    private var stations: StationPlayback? = null
    private var lastPortableQueueNotification = 0L
    private val checkpoint = object : Runnable {
        override fun run() {
            if (session?.player?.isPlaying == true) persist(periodic = true)
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as MBApplication
        val preparedLoads = ConcurrentHashMap.newKeySet<Uri>()
        val preloader = NextAudioPreloader<AudioTarget, PreparedAudio>(scope, SystemClock::elapsedRealtime) { target ->
            val identifier = target.uri.pathSegments.single()
            val trackId = "${target.uri.authority}:$identifier"
            val localCopy = withContext(Dispatchers.IO) { app.offline.store.lease(trackId)?.use { true } == true }
            if (localCopy) null else primeAudio(app.http, target.source.audio(identifier, target.quality))
        }
        fun dataSourceFactory(usePrepared: Boolean) = DataSource.Factory {
            var reader: OfflineMediaStore.Lease? = null
            var pinned: Pair<Uri, MediaResource>? = null
            val guard = MediaRangeGuard()
            val upstream = DefaultDataSource.Factory(this) { guardedMediaHttp(app.http, guard) }
            val delegate = ResolvingDataSource.Factory(upstream) { spec ->
                resolve(spec, { reader = it }) { uri, source, identifier, quality ->
                    pinned?.takeIf { it.first == uri }?.second ?: run {
                        val prepared = if (usePrepared) preloader.take(AudioTarget(uri, source, quality)) else null
                        if (prepared != null) {
                            preparedLoads.add(uri)
                            if (spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong()) guard.offer(prepared.prefix)
                        }
                        resolveAudio(uri, source, identifier, quality, prepared?.resource).also { pinned = uri to it }
                    }
                }
            }.createDataSource()
            object : DataSource by delegate {
                override fun close() {
                    try { delegate.close() }
                    finally { reader?.close(); reader = null }
                }
            }
        }
        val factory = dataSourceFactory(usePrepared = true)
        val measuring = dataSourceFactory(usePrepared = false)
        val loudness = LoudnessController<LoudnessInput>(scope) { AndroidLoudness.measure(measuring, it.uri) }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(AudioSignalProcessor({ app.settings.values.value.normalization }, { loudness.gain },
                        { app.settings.values.value.fog }, { app.bassLevel.value = it }))).build()
        }.setEnableDecoderFallback(true)
        val snapshot = storage.getString("queue", null)?.let(QueueSnapshot::decode)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(factory).recoverOutsideLoader())
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                volume = app.settings.values.value.volume
                snapshot?.let { applySnapshot(this, it) }
            }
        this.player = player
        var loudnessInput: LoudnessInput? = null
        var loudnessRevision = 0L
        fun refreshLoudness(reset: Boolean = false) {
            if (reset || player.playbackState == Player.STATE_IDLE) loudnessInput = null
            if (loudnessInput == null && player.playbackState == Player.STATE_READY) {
                loudnessInput = player.currentMediaItem?.localConfiguration?.uri?.let { LoudnessInput(it, ++loudnessRevision) }
            }
            loudness.update(loudnessInput, app.settings.values.value.normalization)
        }
        fun invalidateAddress(item: MediaItem?) {
            synchronized(resolved) {
                resolved.remove(item?.localConfiguration?.uri)
                resolutionEpoch++
            }
            cacheJob?.cancel()
        }
        val recovery = AudioRecovery(scope, player, ::invalidateAddress)
        val video = VideoPlayback(app, app.http, player, scope,
            { app.playbackSource(it.sourceId) as? VideoSource }, { app.settings.values.value.quality })
        this.video = video
        val stations = StationPlayback(player, scope, { app.playbackSource(it) as? StationPlaybackSource }) { status ->
            app.stationStatus.value = status
            persist()
        }
        this.stations = stations
        val sessionPlayer = CaptionSessionPlayer(player, video, stations) {
            recovery.reset()
            invalidateAddress(player.currentMediaItem)
        }
        fun target(item: MediaItem?): AudioTarget? {
            val track = item?.track()?.takeUnless { it.sourceId == localSourceId || it.isUnavailable } ?: return null
            val source = app.playbackSource(track.sourceId) ?: return null
            val uri = item.localConfiguration?.uri ?: return null
            return AudioTarget(uri, source, app.settings.values.value.quality)
        }
        fun refreshPrefetch() {
            preloader.expire()
            if (!player.playWhenReady || video.frames.value != null) { preloader.cancel(); return }
            val eligible = canPrefetchAudio(player.currentPosition, player.duration, player.isPlaying, videoActive = false)
            val index = player.currentMediaItemIndex
            val next = if (!eligible || player.currentTimeline.isEmpty ||
                (stations.station != null && index == player.mediaItemCount - 1)) null else {
                val nextIndex = player.currentTimeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
                if (nextIndex == C.INDEX_UNSET) null else target(player.getMediaItemAt(nextIndex))
                    ?.takeUnless { it.uri == player.currentMediaItem?.localConfiguration?.uri }
                    ?.takeUnless { candidate -> synchronized(resolved) {
                        resolved[candidate.uri]?.let { it.until > SystemClock.elapsedRealtime() &&
                            it.source === candidate.source && it.quality == candidate.quality } == true
                    } }
            }
            preloader.update(next)
        }
        var prefetchTick: Job? = null
        val currentMedia = MutableStateFlow(player.currentMediaItem?.track())
        val openPlayer = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(openPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.uid != applicationInfo.uid) return super.onConnect(session, controller)
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(enqueueTrack).add(nextStationTrack).add(leaveStation).build()).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                             command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command != enqueueTrack && command != nextStationTrack && command != leaveStation) {
                        return super.onCustomCommand(session, controller, command, args)
                    }
                    if (controller.uid != applicationInfo.uid) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    if (command == nextStationTrack) return Futures.immediateFuture(SessionResult(
                        if (stations.next()) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE,
                    ))
                    if (command == leaveStation) {
                        stations.set(null)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    val track = args.getString("track")?.let { runCatching { JSONObject(it).toTrackMetadata() }.getOrNull() }
                    if (track == null || !app.acceptsTrack(track)) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    val current = player.currentMediaItemIndex
                    val next = args.getBoolean("next") && current in 0 until player.mediaItemCount
                    val insertion = if (next) current + 1 else player.mediaItemCount
                    val shuffled = playOrder(player)?.let { order -> shuffledInsertion(order, insertion, current.takeIf { next }) }
                    if (player.mediaItemCount == 0) player.pause()
                    player.addMediaItem(insertion, track.mediaItem())
                    if (shuffled != null) player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffled, System.nanoTime()))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                              mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    if (controller.uid != applicationInfo.uid) return Futures.immediateFailedFuture(SecurityException())
                    val canonical = mediaItems.map { item ->
                        val track = item.track()?.takeIf(app::acceptsTrack)
                            ?: run {
                                Log.w(playbackLogTag, "session rejected unavailable queue item; count=${mediaItems.size}")
                                return Futures.immediateFailedFuture(IllegalArgumentException("无效曲目"))
                            }
                        track.mediaItem()
                    }
                    return Futures.immediateFuture(canonical)
                }

                override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                             mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long):
                    ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.transform(
                        onAddMediaItems(mediaSession, controller, mediaItems), { canonical ->
                            val safe = checkNotNull(canonical)
                            val station = mediaItems.getOrNull(startIndex)?.station()?.takeIf { candidate ->
                                app.playbackSource(candidate.sourceId) is StationPlaybackSource &&
                                    safe.all { it.track()?.sourceId == candidate.sourceId }
                            }
                            val items = if (station == null) safe else safe.mapIndexed { index, item ->
                                if (index == startIndex) item.withStation(station) else item
                            }
                            MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
                        }, MoreExecutors.directExecutor(),
                    )
            }).build()
        stations.set(snapshot?.station)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                preloader.cancel()
                val period = (error as? ExoPlaybackException)?.mediaPeriodId
                val item = if (period == null) player.currentMediaItem else {
                    val timeline = player.currentTimeline
                    val index = timeline.getIndexOfPeriod(period.periodUid)
                    if (index == C.INDEX_UNSET) null else player.getMediaItemAt(timeline.getPeriod(index, Timeline.Period()).windowIndex)
                }
                val track = item?.track()
                val source = track?.let { app.playbackSource(it.sourceId) }
                val usedPreload = item?.localConfiguration?.uri?.let(preparedLoads::remove) == true
                if (track != null && source != null && recovery.onError(error, item, usedPreload) { app.playbackSource(track.sourceId) === source }) return
                val issue = classifyPlaybackIssue(error, error.errorCode)
                Log.w(playbackLogTag, "audio failed: $issue / ${error.playbackDiagnostic(error.errorCode)} / cause=${error.cause?.javaClass?.simpleName}")
                track?.let { app.trackPlaybackIssues.failed(it, issue, error) }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                Log.i(playbackLogTag, "playback suppression=$playbackSuppressionReason")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                recovery.reset()
                preloader.advance(target(mediaItem).takeIf { player.playWhenReady })
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    preloader.cancel()
                    preparedLoads.retainAll((0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri }.toSet())
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (player.playbackState == Player.STATE_READY) player.currentMediaItem?.localConfiguration?.uri?.let(preparedLoads::remove)
                if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) preloader.cancel()
                refreshPrefetch()
                if (player.isPlaying && prefetchTick?.isActive != true) {
                    prefetchTick = scope.launch {
                        while (player.isPlaying) { delay(400); refreshPrefetch() }
                    }
                }
                if (!player.isPlaying) prefetchTick?.cancel()
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    refreshLoudness(events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION))
                }
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    persist()
                }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                    val track = player.currentMediaItem?.track()
                    currentMedia.value = track
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) reportedSelection = false
                    if (player.isPlaying && track != null && !reportedSelection) {
                        reportedSelection = true
                        val recorder = app.playbackSource(track.sourceId) as? PlaybackRecorder
                        if (recorder != null) scope.launch {
                            try { recorder.played(track.sourceTrackId) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { }
                        }
                    }
                    if (player.isPlaying && track != null) {
                        app.trackPlaybackIssues.playing(track)
                        val playedAt = System.currentTimeMillis()
                        scope.launch(Dispatchers.IO) {
                            try {
                                val stored = if (track.sourceId == localSourceId) app.local.catalog.get(track.sourceTrackId) != null else {
                                    app.offline.store.refresh()
                                    app.offline.store.snapshot.value.downloads.any { it.track.id == track.id }
                                }
                                if (stored) app.local.catalog.recordPlay(track.id, playedAt)
                            } catch (_: IOException) { }
                            catch (_: SQLiteException) { }
                        }
                    }
                    if (track?.id != currentTrack) {
                        val previous = currentTrack
                        currentTrack = track?.id
                        recordedTrack = null
                        cacheJob?.cancel()
                        if (previous != null) synchronized(locations) { locations.remove(previous)?.file }?.let { lease ->
                            scope.launch(Dispatchers.IO + NonCancellable) { lease.close() }
                        }
                    }
                    if (track?.id != recordedTrack && player.isPlaying) {
                        recordedTrack = track?.id
                        val uri = player.currentMediaItem?.localConfiguration?.uri
                        val media = synchronized(resolved) { resolved[uri]?.resource }
                        if (track != null && media != null && track.sourceId != localSourceId && app.playbackSource(track.sourceId) != null) cacheJob = scope.launch {
                            try { (application as MBApplication).offline.fillCache(track, media) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { }
                        }
                    }
                }
            }
        })
        scope.launch {
            app.portableLibrary.restoredQueue.filterNotNull().collect { queue ->
                if (player.mediaItemCount == 0) {
                    applySnapshot(player, queue)
                    stations.set(queue.station)
                }
                app.portableLibrary.acknowledgeQueue(queue)
            }
        }
        scope.launch {
            app.settings.values.map { it.volume }.distinctUntilChanged().collect { player.volume = it }
        }
        scope.launch {
            app.settings.values.map { it.normalization }.distinctUntilChanged().collect { refreshLoudness() }
        }
        scope.launch {
            app.settings.values.map { it.quality }.distinctUntilChanged().drop(1).collect { preloader.cancel(); refreshPrefetch() }
        }
        scope.launch {
            app.settings.values.map { it.builtInEnabled }.distinctUntilChanged().collect { enabled ->
                val track = player.currentMediaItem?.track()
                if (!enabled && track?.sourceId == app.bilibili.id && synchronized(locations) { locations[track.id]?.file == null }) {
                    player.pause()
                    player.stop()
                    synchronized(locations) { locations.remove(track.id) }
                    cacheJob?.cancel()
                }
            }
        }
        scope.launch {
            combine(currentMedia, app.settings.values) { track, preferences ->
                track?.takeIf { preferences.nowPlayingLyrics && (it.sourceId != app.bilibili.id || preferences.builtInEnabled) }
            }.distinctUntilChanged().collectLatest { track ->
                sessionPlayer.showCaption(track?.id, null)
                if (track == null) return@collectLatest
                showSystemCaptions(app, track, player, sessionPlayer)
            }
        }
        app.videoPlayback.value = video
        handler.postDelayed(checkpoint, 5_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(checkpoint)
        (application as MBApplication).videoPlayback.value = null
        video?.close()
        video = null
        scope.cancel()
        persist()
        (application as MBApplication).stationStatus.value = StationStatus()
        stations = null
        val current = session
        session = null
        player = null
        current?.player?.release()
        val retired = synchronized(locations) { locations.values.mapNotNull(AudioLocation::file).also { locations.clear() } }
        scope.launch(Dispatchers.IO + NonCancellable) { retired.forEach(OfflineMediaStore.Lease::close) }
        current?.release()
        super.onDestroy()
    }

    private suspend fun showSystemCaptions(app: MBApplication, track: Track, player: Player, sessionPlayer: CaptionSessionPlayer) {
        val lines = try { app.playbackSource(track.sourceId)?.captions(track.sourceTrackId).orEmpty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        currentCoroutineContext().ensureActive()
        if (lines.isEmpty()) return
        while (player.currentMediaItem?.mediaId == track.id) {
            val active = if (player.playbackState == Player.STATE_ENDED) -1 else lines.activeCaption(player.currentPosition)
            sessionPlayer.showCaption(track.id, lines.getOrNull(active)?.text)
            delay(250)
        }
    }

    private fun resolve(spec: DataSpec, keepOpen: (OfflineMediaStore.Lease) -> Unit,
                        resource: (Uri, PlaybackSource, String, AudioQuality) -> MediaResource): DataSpec {
        val uri = spec.uri
        val identifier = uri.pathSegments.singleOrNull() ?: throw IOException("曲目地址无效")
        val app = application as MBApplication
        if (uri.scheme != trackScheme) throw IOException("音源不可用")
        val sourceId = uri.authority ?: throw IOException("音源不可用")
        val trackId = "$sourceId:$identifier"
        val quality = synchronized(locations) {
            val location = locations.getOrPut(trackId) {
                val lease = if (sourceId == localSourceId) null else runCatching { app.offline.store.lease(trackId) }.getOrNull()
                AudioLocation(app.settings.values.value.quality, lease)
            }
            location.file?.duplicate()?.let { reader ->
                keepOpen(reader)
                return spec.withUri(Uri.fromFile(reader.path))
            }
            location.quality
        }
        val source = app.playbackSource(sourceId) ?: throw PlaybackIssueException(PlaybackIssue.SourceUnavailable, "音源不可用")
        val media = resource(uri, source, identifier, quality)
        return spec.withUri(Uri.parse(media.url)).withAdditionalHeaders(media.headers)
    }

    private fun resolveAudio(uri: Uri, source: PlaybackSource, identifier: String, quality: AudioQuality,
                             prepared: MediaResource? = null): MediaResource {
        val time = SystemClock.elapsedRealtime()
        val epoch = synchronized(resolved) {
            if (prepared == null) resolved[uri]?.takeIf { it.until > time && it.source === source && it.quality == quality }
                ?.let { return it.resource }
            resolutionEpoch
        }
        return (prepared ?: runBlocking { source.audio(identifier, quality) }).also { resource ->
            synchronized(resolved) {
                resolved.entries.removeAll { (_, entry) -> entry.until <= time }
                if (epoch == resolutionEpoch) {
                    resolved[uri] = ResolvedAudio(SystemClock.elapsedRealtime() + 60_000, source, quality, resource)
                }
            }
        }
    }

    private fun applySnapshot(player: ExoPlayer, snapshot: QueueSnapshot) {
        player.pause()
        player.setMediaItems(snapshot.tracks.map { it.mediaItem() }, snapshot.index, snapshot.positionMs)
        player.repeatMode = snapshot.repeatMode
        snapshot.shuffleOrder?.let { order ->
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        }
        player.shuffleModeEnabled = snapshot.shuffle
    }

    private fun playOrder(player: Player): List<Int>? {
        if (!player.shuffleModeEnabled) return null
        val timeline = player.currentTimeline
        return generateSequence(timeline.getFirstWindowIndex(true)) { index ->
            timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }.takeWhile { it != C.INDEX_UNSET }.toList()
    }

    private fun persist(periodic: Boolean = false) {
        val player = player ?: return
        val tracks = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).track() ?: return }
        if (tracks.isEmpty()) storage.edit().remove("queue").apply()
        else storage.edit().putString("queue", QueueSnapshot(tracks, player.currentMediaItemIndex,
            player.currentPosition, player.repeatMode, player.shuffleModeEnabled, playOrder(player), stations?.station).encode()).apply()
        val now = SystemClock.elapsedRealtime()
        if (!periodic || now - lastPortableQueueNotification >= 30_000) {
            lastPortableQueueNotification = now
            (application as MBApplication).portableLibrary.userStateChanged()
        }
    }
}
