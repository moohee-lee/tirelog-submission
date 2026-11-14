package com.tirelog.app.voice

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 정차 중 한 줄 음성 안내. Pleos TextToSpeech(ko-KR, 온디바이스)를 쓰고,
 * 차량 음성 서비스가 없으면(일반 에뮬레이터 등) Android TTS로 대신한다.
 */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private var pleos: PleosTts? = null
    @Volatile private var pleosReady = false
    private var android: AndroidTts? = null
    @Volatile private var androidReady = false

    fun init() {
        if (pleos != null || android != null) return
        try {
            val tts = PleosTts(appContext, Mode.ON_DEVICE)
            pleos = tts
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { tts.setLocale(Locale.KOREA) }
                    pleosReady = true
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "Pleos TTS 초기화 실패: $errorType")
                    pleosReady = false
                    initAndroid()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos TTS 사용 불가", t)
            initAndroid()
        }
    }

    private fun initAndroid() {
        if (android != null) return
        android = AndroidTts(appContext) { status ->
            androidReady = status == AndroidTts.SUCCESS
            if (androidReady) android?.language = Locale.KOREA
        }
    }

    fun speak(text: String) {
        when {
            pleosReady -> runCatching { pleos?.speak(text, true) }.onFailure { Log.w(TAG, "speak 실패", it) }
            androidReady -> android?.speak(text, AndroidTts.QUEUE_FLUSH, null, "tirelog")
            else -> Log.i(TAG, "음성 안내 준비 전: $text")
        }
    }

    fun release() {
        runCatching { pleos?.release() }
        runCatching { android?.shutdown() }
        pleos = null
        android = null
        pleosReady = false
        androidReady = false
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
