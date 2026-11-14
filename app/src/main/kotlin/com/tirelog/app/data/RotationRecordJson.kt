package com.tirelog.app.data

import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.RotationRecord
import com.tirelog.core.model.TireMove
import com.tirelog.core.model.WheelPosition
import org.json.JSONArray
import org.json.JSONObject

/** 추가 정보가 손상돼도 기존 이벤트 설명은 표시한다. */
internal object RotationRecordJson {
    fun encode(record: RotationRecord?): String? = record?.let {
        JSONObject().put("version", 1).put("pattern", it.pattern.name)
            .put("moves", JSONArray().apply {
                it.moves.forEach { move ->
                    put(JSONObject().put("tireId", move.tireId).put("tireLabel", move.tireLabel)
                        .put("from", move.from.name).put("to", move.to.name))
                }
            }).toString()
    }

    fun decode(json: String?): RotationRecord? = json?.let {
        runCatching {
            val value = JSONObject(it)
            require(value.getInt("version") == 1)
            val pattern = RotationPattern.valueOf(value.getString("pattern"))
            val moves = value.getJSONArray("moves").let { items ->
                List(items.length()) { index ->
                    val move = items.getJSONObject(index)
                    TireMove(move.getLong("tireId"), move.getString("tireLabel"),
                        WheelPosition.valueOf(move.getString("from")), WheelPosition.valueOf(move.getString("to")))
                }
            }
            require(moves.isNotEmpty() && moves.all { move ->
                move.tireId > 0 && move.tireLabel.isNotBlank() && pattern.destinationOf(move.from) == move.to
            })
            require(moves.map { move -> move.tireId }.distinct().size == moves.size &&
                moves.map { move -> move.from }.distinct().size == moves.size)
            RotationRecord(pattern, moves)
        }.getOrNull()
    }
}
