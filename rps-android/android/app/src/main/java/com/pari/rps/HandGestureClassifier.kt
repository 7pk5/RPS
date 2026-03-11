package com.pari.rps

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * Classifies hand gestures (Rock, Paper, Scissors) from MediaPipe hand landmarks.
 *
 * Landmark indices (MediaPipe Hand):
 *   0     – WRIST
 *   1-4   – THUMB  (CMC, MCP, IP, TIP)
 *   5-8   – INDEX  (MCP, PIP, DIP, TIP)
 *   9-12  – MIDDLE (MCP, PIP, DIP, TIP)
 *   13-16 – RING   (MCP, PIP, DIP, TIP)
 *   17-20 – PINKY  (MCP, PIP, DIP, TIP)
 */
object HandGestureClassifier {

    enum class Gesture(val emoji: String, val label: String) {
        ROCK("✊", "Rock"),
        PAPER("✋", "Paper"),
        SCISSORS("✌️", "Scissors"),
        NONE("❓", "None")
    }

    fun classify(landmarks: List<NormalizedLandmark>): Gesture {
        if (landmarks.size < 21) return Gesture.NONE

        // A finger is "extended" when:
        //   TIP is above PIP  AND  TIP is above DIP
        // Using both joints makes detection much more robust at different hand angles.
        // "above" means smaller Y value (Y=0 is top of image).
        fun isFingerExtended(tipIdx: Int, dipIdx: Int, pipIdx: Int, mcpIdx: Int): Boolean {
            val tipY = landmarks[tipIdx].y()
            val dipY = landmarks[dipIdx].y()
            val pipY = landmarks[pipIdx].y()
            val mcpY = landmarks[mcpIdx].y()
            // Finger is extended if tip is clearly above PIP
            // OR if tip is above DIP and DIP is above MCP (partially extended still counts)
            return tipY < pipY && tipY < dipY ||
                   tipY < mcpY && dipY < pipY
        }

        // A finger is "curled" when TIP is below PIP (tucked in)
        fun isFingerCurled(tipIdx: Int, pipIdx: Int): Boolean {
            return landmarks[tipIdx].y() > landmarks[pipIdx].y()
        }

        val indexUp = isFingerExtended(8, 7, 6, 5)
        val middleUp = isFingerExtended(12, 11, 10, 9)
        val ringUp = isFingerExtended(16, 15, 14, 13)
        val pinkyUp = isFingerExtended(20, 19, 18, 17)

        val indexCurled = isFingerCurled(8, 6)
        val middleCurled = isFingerCurled(12, 10)
        val ringCurled = isFingerCurled(16, 14)
        val pinkyCurled = isFingerCurled(20, 18)

        val extendedCount = listOf(indexUp, middleUp, ringUp, pinkyUp).count { it }
        val curledCount = listOf(indexCurled, middleCurled, ringCurled, pinkyCurled).count { it }

        // ✋ Paper – 3+ fingers extended
        if (extendedCount >= 3) return Gesture.PAPER

        // ✌️ Scissors – index + middle up, ring + pinky NOT extended
        if (indexUp && middleUp && !ringUp && !pinkyUp) return Gesture.SCISSORS

        // ✊ Rock – at least 3 fingers curled, index and middle NOT extended
        if (curledCount >= 3 && !indexUp && !middleUp) return Gesture.ROCK

        // Fallback: if most fingers are curled, it's probably Rock
        if (curledCount >= 2 && extendedCount <= 1 && !indexUp && !middleUp) return Gesture.ROCK

        return Gesture.NONE
    }
}
