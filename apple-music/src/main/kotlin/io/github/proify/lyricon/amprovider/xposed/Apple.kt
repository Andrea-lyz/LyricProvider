/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Apple : YukiBaseHooker() {
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    private var isPlaying = false
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return
        PreferencesMonitor.initialize(application)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                provider?.player?.setDisplayTranslation(selected)
            }
        }

        DiskSongManager.initialize(application)
        initScreenStateMonitor()
        initProvider()
        startHooks()
    }

    private fun initProvider() {
        val helper =
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )

        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = LyricRequester(classLoader, application),
            application = application
        )

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
    }

    private fun startHooks() {
        hookPlaybackItemLoadMethod()
        hookPlaybackItemConvertMethod()
        hookMediaMetadataChange()
        hookLyricBuildMethod()
        runCatching {
            hookExoMediaPlayer()
        }.onFailure {
            YLog.error("hookExoMediaPlayer failed", it)
        }
    }

    private fun hookPlaybackItemLoadMethod() {
        runCatching {
            val viewModelClass =
                classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            val playbackItemClass =
                classLoader.loadClass("com.apple.android.music.model.PlaybackItem")
            val method = viewModelClass.getDeclaredMethod("loadLyrics", playbackItemClass)
                .apply { isAccessible = true }

            method.hook {
                before {
                    PlaybackManager.onPlaybackItemObserved(
                        playbackItem = args.getOrNull(0),
                        source = "PlayerLyricsViewModel.loadLyrics",
                        requestIfMissing = false
                    )
                }
            }
            YLog.debug("hookPlaybackItemLoadMethod Hooked: $method")
        }.onFailure {
            YLog.error("hookPlaybackItemLoadMethod failed", it)
        }
    }

    private fun hookPlaybackItemConvertMethod() {
        runCatching {
            val mapperClass = classLoader.loadClass("com.apple.android.music.player.N")
            val playbackItemClass = classLoader.loadClass("com.apple.android.music.model.PlaybackItem")
            val methods = mapperClass.declaredMethods.filter { method ->
                Modifier.isStatic(method.modifiers)
                        && playbackItemClass.isAssignableFrom(method.returnType)
                        && method.parameterCount == 1
            }

            methods.forEach { method ->
                method.isAccessible = true
                method.hook {
                    after {
                        PlaybackManager.onPlaybackItemObserved(
                            playbackItem = this.result,
                            source = "PlayerMetadataMapper.${method.name}",
                            requestIfMissing = true
                        )
                    }
                }
            }
            YLog.debug("hookPlaybackItemConvertMethod Hooked: ${methods.size}")
        }.onFailure {
            YLog.error("hookPlaybackItemConvertMethod failed", it)
        }
    }

    private fun hookMediaMetadataChange() {
        runCatching {
            val method = findMediaMetadataChangeMethod()
            if (method == null) {
                YLog.debug("hookMediaMetadataChange skipped: method not found")
                return@runCatching
            }

            method.hook {
                after {
                    val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                    val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after
                    YLog.debug("MediaMetadataCompat captured id=${metadata.id}")
                    PlaybackManager.onSongChanged(metadata.id)
                }
            }
            YLog.debug("hookMediaMetadataChange Hooked: $method")
        }.onFailure {
            YLog.error("hookMediaMetadataChange failed", it)
        }
    }

    private fun hookLyricBuildMethod() {
        runCatching {
            val viewModelClass =
                classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            val songInfoPtrClass =
                classLoader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr")
            val method = viewModelClass.getDeclaredMethod(
                "buildTimeRangeToLyricsMap",
                songInfoPtrClass
            ).apply { isAccessible = true }

            method.hook {
                after {
                    val songInfoPtr = args.getOrNull(0)
                    if (songInfoPtr == null) {
                        YLog.debug("buildTimeRangeToLyricsMap: null SongInfoPtr")
                        return@after
                    }

                    val songNative = XposedHelpers.callMethod(songInfoPtr, "get")
                    if (songNative == null) {
                        YLog.debug("buildTimeRangeToLyricsMap: null SongInfoNative")
                        return@after
                    }

                    prepareSongInfoNativeForBridge(songNative)
                    YLog.debug("buildTimeRangeToLyricsMap captured: $songNative")
                    PlaybackManager.onLyricsBuilt(songNative)
                }
            }
            YLog.debug("hookLyricBuildMethod Hooked: $method")
        }.onFailure {
            YLog.error("hookLyricBuildMethod failed", it)
        }
    }

    private fun prepareSongInfoNativeForBridge(songNative: Any) {
        val language = runCatching {
            classLoader
                .loadClass("com.apple.android.music.playback.util.LocaleUtil")
                .getDeclaredMethod("getSystemLyricsLanguage")
                .invoke(null) as? String
        }.onFailure {
            YLog.error("resolve Apple lyrics language failed", it)
        }.getOrNull()

        if (language.isNullOrBlank()) {
            YLog.debug("prepare Apple lyrics translation skipped: language empty")
            return
        }

        val available = runCatching {
            XposedHelpers.callMethod(songNative, "hasTranslation", language) as? Boolean
        }.getOrDefault(false)

        val applied = runCatching {
            XposedHelpers.callMethod(songNative, "setTranslation", language) as? Boolean
        }.onFailure {
            YLog.error("set Apple lyrics translation failed language=$language", it)
        }.getOrDefault(false)

        YLog.debug(
            "Prepared Apple lyrics translation language=$language, " +
                    "available=$available, applied=$applied"
        )
    }

    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

        exoPlayerClass.declaredConstructors.forEach { constructor ->
            constructor.hook {
                after {
                    exoMediaPlayerInstance = instanceOrNull
                    getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
                }
            }
        }

        exoPlayerClass.resolve().firstMethod {
            name = "seekToPosition"
            parameters(Long::class)
        }.hook {
            after {
                val position = args(0).cast<Long>() ?: 0L
                if (isPlaying) provider?.player?.seekTo(position)
            }
        }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .resolve()
            .method {
                name = "onPlaybackStateChanged"
                parameters(VagueType, Int::class, Int::class)
            }.first().hook {
                after {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
            }
    }

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.player?.setPosition(pos)
                } catch (_: Exception) {
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    private fun findMediaMetadataChangeMethod() =
        "android.support.v4.media.MediaMetadataCompat".toClass()
            .declaredMethods.firstOrNull {
                Modifier.isPublic(it.modifiers)
                        && Modifier.isStatic(it.modifiers)
                        && it.parameterCount == 1
                        && it.returnType.simpleName.contains("MediaMetadata")
            }
}
