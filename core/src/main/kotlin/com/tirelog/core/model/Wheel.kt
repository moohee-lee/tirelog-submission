package com.tirelog.core.model

enum class WheelPosition(val label: String, val isFront: Boolean) {
    FRONT_LEFT("앞왼쪽", true),
    FRONT_RIGHT("앞오른쪽", true),
    REAR_LEFT("뒤왼쪽", false),
    REAR_RIGHT("뒤오른쪽", false);

    /** "앞오른쪽이"처럼 주격 조사를 붙인다. 모든 위치 이름이 받침으로 끝난다. */
    val subject: String get() = "${label}이"
}

enum class RotationPattern(val label: String, val description: String) {
    FRONT_REAR("앞뒤 교환", "같은 쪽 앞뒤를 바꿉니다"),
    SIDE_TO_SIDE("좌우 교환", "앞타이어끼리, 뒷타이어끼리 좌우를 바꿉니다"),
    X_CROSS("X자 교환", "대각선으로 바꿉니다"),
    FORWARD_CROSS("뒷타이어 교차", "앞타이어는 같은 쪽 뒤로, 뒷타이어는 대각선 앞으로 이동합니다"),
    REARWARD_CROSS("앞타이어 교차", "뒷타이어는 같은 쪽 앞으로, 앞타이어는 대각선 뒤로 이동합니다");

    fun destinationOf(from: WheelPosition): WheelPosition = when (this) {
        FRONT_REAR -> when (from) {
            WheelPosition.FRONT_LEFT -> WheelPosition.REAR_LEFT
            WheelPosition.FRONT_RIGHT -> WheelPosition.REAR_RIGHT
            WheelPosition.REAR_LEFT -> WheelPosition.FRONT_LEFT
            WheelPosition.REAR_RIGHT -> WheelPosition.FRONT_RIGHT
        }
        SIDE_TO_SIDE -> when (from) {
            WheelPosition.FRONT_LEFT -> WheelPosition.FRONT_RIGHT
            WheelPosition.FRONT_RIGHT -> WheelPosition.FRONT_LEFT
            WheelPosition.REAR_LEFT -> WheelPosition.REAR_RIGHT
            WheelPosition.REAR_RIGHT -> WheelPosition.REAR_LEFT
        }
        X_CROSS -> when (from) {
            WheelPosition.FRONT_LEFT -> WheelPosition.REAR_RIGHT
            WheelPosition.FRONT_RIGHT -> WheelPosition.REAR_LEFT
            WheelPosition.REAR_LEFT -> WheelPosition.FRONT_RIGHT
            WheelPosition.REAR_RIGHT -> WheelPosition.FRONT_LEFT
        }
        FORWARD_CROSS -> when (from) {
            WheelPosition.FRONT_LEFT -> WheelPosition.REAR_LEFT
            WheelPosition.FRONT_RIGHT -> WheelPosition.REAR_RIGHT
            WheelPosition.REAR_LEFT -> WheelPosition.FRONT_RIGHT
            WheelPosition.REAR_RIGHT -> WheelPosition.FRONT_LEFT
        }
        REARWARD_CROSS -> when (from) {
            WheelPosition.REAR_LEFT -> WheelPosition.FRONT_LEFT
            WheelPosition.REAR_RIGHT -> WheelPosition.FRONT_RIGHT
            WheelPosition.FRONT_LEFT -> WheelPosition.REAR_RIGHT
            WheelPosition.FRONT_RIGHT -> WheelPosition.REAR_LEFT
        }
    }
}
