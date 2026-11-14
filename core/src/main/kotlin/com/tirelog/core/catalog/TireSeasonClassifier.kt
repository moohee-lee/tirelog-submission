package com.tirelog.core.catalog

import com.tirelog.core.model.TireSetType
import java.util.Locale

/** 공식 제품의 계절 태그만 받는다. 모델명·메뉴·후기·일반 설명은 분류 근거로 넣지 않는다. */
object TireSeasonClassifier {
    fun classify(rawSeason: String, snowCertified: Boolean?, separatesAllWeather: Boolean,
        onRoad: Boolean = true): TireSetType? {
        val tag = rawSeason.lowercase(Locale.ROOT).replace('-', ' ')
        val winter = Regex("\\bwinter\\b|겨울|윈터").containsMatchIn(tag)
        val summer = Regex("\\bsummer\\b|썸머|여름").containsMatchIn(tag)
        val weather = Regex("\\ball\\s*weather\\b|올웨더").containsMatchIn(tag)
        val season = Regex("\\ball\\s*seasons?\\b|올시즌|사계절").containsMatchIn(tag)
        if (winter) return if (summer || weather || season) null else TireSetType.WINTER
        if (summer) return if (weather || season) null else TireSetType.SUMMER
        if (weather) return TireSetType.ALL_WEATHER
        if (!onRoad) return null // A/T·M/T의 눈송이 인증만으로 올웨더로 분류하지 않는다.
        if (!season) return null
        return when {
            snowCertified == true -> TireSetType.ALL_WEATHER
            separatesAllWeather || snowCertified == false -> TireSetType.ALL_SEASON
            else -> null
        }
    }
}
