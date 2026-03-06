package com.pari.rps

/**
 * Rock Paper Scissors game logic: AI move generation, winner determination, scoring.
 */
object GameLogic {

    enum class Result(val label: String) {
        WIN("You Win"),
        LOSE("AI Wins"),
        DRAW("Draw")
    }

    private val MOVES = listOf(
        HandGestureClassifier.Gesture.ROCK,
        HandGestureClassifier.Gesture.PAPER,
        HandGestureClassifier.Gesture.SCISSORS
    )

    /** Returns a random AI move. */
    fun getAiMove(): HandGestureClassifier.Gesture {
        return MOVES.random()
    }

    /** Determines the winner given human and AI moves. */
    fun determineWinner(
        human: HandGestureClassifier.Gesture,
        ai: HandGestureClassifier.Gesture
    ): Result {
        if (human == ai) return Result.DRAW
        return when {
            human == HandGestureClassifier.Gesture.ROCK && ai == HandGestureClassifier.Gesture.SCISSORS -> Result.WIN
            human == HandGestureClassifier.Gesture.PAPER && ai == HandGestureClassifier.Gesture.ROCK -> Result.WIN
            human == HandGestureClassifier.Gesture.SCISSORS && ai == HandGestureClassifier.Gesture.PAPER -> Result.WIN
            else -> Result.LOSE
        }
    }

    /** Data class for a log entry. */
    data class LogEntry(
        val round: Int,
        val humanMove: HandGestureClassifier.Gesture,
        val aiMove: HandGestureClassifier.Gesture,
        val result: Result
    )
}
