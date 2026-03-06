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
 *
 * A finger is "extended" when TIP.y < PIP.y (in normalized coords, Y=0 is top).
 * Thumb is extended when TIP.x is far from wrist relative to hand width.
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

        // Helper: is finger extended? (for index/middle/ring/pinky)
        fun isExtended(tipIdx: Int, pipIdx: Int): Boolean =
            landmarks[tipIdx].y() < landmarks[pipIdx].y()

        val indexUp = isExtended(8, 6)
        val middleUp = isExtended(12, 10)
        val ringUp = isExtended(16, 14)
        val pinkyUp = isExtended(20, 18)

        // Thumb: compare tip X distance from wrist vs MCP X distance from wrist
        val thumbTipDist = abs(landmarks[4].x() - landmarks[0].x())
        val thumbMcpDist = abs(landmarks[2].x() - landmarks[0].x())
        val thumbUp = thumbTipDist > thumbMcpDist * 1.2f

        val extendedCount = listOf(indexUp, middleUp, ringUp, pinkyUp).count { it }

        // ✋ Paper – 3+ fingers extended (thumb may or may not be)
        if (extendedCount >= 3 && (thumbUp || extendedCount == 4)) return Gesture.PAPER

        // ✌️ Scissors – index + middle up, ring + pinky down
        if (indexUp && middleUp && !ringUp && !pinkyUp) return Gesture.SCISSORS

        // ✊ Rock – all 4 fingers curled (thumb ignored)
        if (extendedCount <= 1 && !indexUp && !middleUp) return Gesture.ROCK

        return Gesture.NONE
    }
}
