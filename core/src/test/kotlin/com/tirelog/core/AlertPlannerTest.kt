package com.tirelog.core

import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.alert.AlertAction
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.model.AlertState
import com.tirelog.core.model.AlertType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlertPlannerTest {

    private fun alert(type: AlertType, key: String = type.name) =
        ActiveAlert(key, type, "${type.title}.", actions = listOf(AlertAction.LATER), listText = type.title)

    private val alerts = listOf(
        alert(AlertType.LEAK, "LEAK:2"),
        alert(AlertType.REFILL_NEEDED),
        alert(AlertType.NUT_CHECK, "NUT:9"),
        alert(AlertType.AGE, "AGE:1"),
    )

    @Test
    fun `우선순위가 가장 높은 알림 하나만 띄운다`() {
        assertEquals("LEAK:2", AlertPlanner.pickPopup(alerts, emptyMap(), 10, measuredToday = true)?.key)
    }

    @Test
    fun `측정 기반 알림은 그날 측정이 없으면 띄우지 않는다`() {
        assertEquals("NUT:9", AlertPlanner.pickPopup(alerts, emptyMap(), 10, measuredToday = false)?.key)
    }

    @Test
    fun `오늘 띄운 알림은 건너뛰고 다음 순위를 띄운다`() {
        val states = mapOf("LEAK:2" to AlertState("LEAK:2", lastShownDay = 10))
        assertEquals("REFILL_NEEDED", AlertPlanner.pickPopup(alerts, states, 10, measuredToday = true)?.key)
        assertEquals("LEAK:2", AlertPlanner.pickPopup(alerts, states, 11, measuredToday = true)?.key, "다음 날 다시")
    }

    @Test
    fun `연식 알림은 확인 뒤 30일 동안 다시 띄우지 않는다`() {
        val only = listOf(alert(AlertType.AGE, "AGE:1"))
        val states = mapOf("AGE:1" to AlertState("AGE:1", lastShownDay = 10, snoozedUntilDay = 40))
        assertNull(AlertPlanner.pickPopup(only, states, 20, measuredToday = false))
        assertEquals("AGE:1", AlertPlanner.pickPopup(only, states, 40, measuredToday = false)?.key)
    }
}
