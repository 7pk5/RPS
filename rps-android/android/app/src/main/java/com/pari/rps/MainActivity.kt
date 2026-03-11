package com.pari.rps

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RPSArena"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val STABILIZE_FRAMES = 4
        private const val FRAME_INTERVAL_MS = 67L
    }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var tvGestureIcon: TextView
    private lateinit var tvGestureLabel: TextView
    private lateinit var tvHumanScore: TextView
    private lateinit var tvAiScore: TextView
    private lateinit var tvRoundNumber: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnReset: Button
    private lateinit var resultPanel: LinearLayout
    private lateinit var tvResultHumanEmoji: TextView
    private lateinit var tvResultHumanGesture: TextView
    private lateinit var tvResultAiEmoji: TextView
    private lateinit var tvResultAiGesture: TextView
    private lateinit var tvResultOutcome: TextView
    private lateinit var countdownOverlay: FrameLayout
    private lateinit var tvCountdown: TextView
    private lateinit var tvAiHand: TextView
    private lateinit var tvHoldGesture: TextView
    private lateinit var tvLogEmpty: TextView
    private lateinit var rvLog: RecyclerView
    private lateinit var cameraBorderFlash: View
    private lateinit var tvCameraHint: TextView

    // State
    private var handLandmarker: HandLandmarker? = null
    private var rawGesture = HandGestureClassifier.Gesture.NONE
    private var stableGesture = HandGestureClassifier.Gesture.NONE
    private var gestureStreakCount = 0
    private var humanScore = 0
    private var aiScore = 0
    private var roundNumber = 1
    private var isPlaying = false
    private var isCountingDown = false
    private var cameraReady = false
    private var capturedGesture: HandGestureClassifier.Gesture? = null
    private var gestureAtPlayTap: HandGestureClassifier.Gesture? = null

    private val logAdapter = LogAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val aiCycleEmojis = listOf("✊", "✋", "✌️")

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required to play!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecyclerView()
        setupClickListeners()
        initHandLandmarker()
        checkCameraPermission()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        tvGestureIcon = findViewById(R.id.tvGestureIcon)
        tvGestureLabel = findViewById(R.id.tvGestureLabel)
        tvHumanScore = findViewById(R.id.tvHumanScore)
        tvAiScore = findViewById(R.id.tvAiScore)
        tvRoundNumber = findViewById(R.id.tvRoundNumber)
        btnPlay = findViewById(R.id.btnPlay)
        btnReset = findViewById(R.id.btnReset)
        resultPanel = findViewById(R.id.resultPanel)
        tvResultHumanEmoji = findViewById(R.id.tvResultHumanEmoji)
        tvResultHumanGesture = findViewById(R.id.tvResultHumanGesture)
        tvResultAiEmoji = findViewById(R.id.tvResultAiEmoji)
        tvResultAiGesture = findViewById(R.id.tvResultAiGesture)
        tvResultOutcome = findViewById(R.id.tvResultOutcome)
        countdownOverlay = findViewById(R.id.countdownOverlay)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvAiHand = findViewById(R.id.tvAiHand)
        tvHoldGesture = findViewById(R.id.tvHoldGesture)
        tvLogEmpty = findViewById(R.id.tvLogEmpty)
        rvLog = findViewById(R.id.rvLog)
        cameraBorderFlash = findViewById(R.id.cameraBorderFlash)
        tvCameraHint = findViewById(R.id.tvCameraHint)
    }

    private fun setupRecyclerView() {
        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter
    }

    private fun setupClickListeners() {
        btnPlay.setOnClickListener {
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            playRound()
        }
        btnReset.setOnClickListener {
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            resetGame()
        }
    }

    // ─── MediaPipe Hand Landmarker Setup ─── //

    private fun initHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .setResultListener { result, _ ->
                    processHandResult(result)
                }
                .setErrorListener { error ->
                    Log.e(TAG, "HandLandmarker error: ${error.message}")
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            Log.i(TAG, "HandLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize HandLandmarker: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to load hand model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processHandResult(result: HandLandmarkerResult) {
        val newRawGesture = if (result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks()[0]
            HandGestureClassifier.classify(landmarks)
        } else {
            HandGestureClassifier.Gesture.NONE
        }

        if (newRawGesture == rawGesture && newRawGesture != HandGestureClassifier.Gesture.NONE) {
            gestureStreakCount++
        } else if (newRawGesture == HandGestureClassifier.Gesture.NONE) {
            gestureStreakCount++
            if (gestureStreakCount >= 5) {
                stableGesture = HandGestureClassifier.Gesture.NONE
                gestureStreakCount = 0
            }
            rawGesture = newRawGesture
            runOnUiThread { updateGestureUI() }
            return
        } else {
            gestureStreakCount = 1
        }
        rawGesture = newRawGesture

        if (gestureStreakCount >= STABILIZE_FRAMES && stableGesture != rawGesture) {
            stableGesture = rawGesture
            gestureStreakCount = 0
            runOnUiThread { updateGestureUI() }
        } else {
            runOnUiThread { updateGestureUI() }
        }
    }

    private fun updateGestureUI() {
        val gestureToShow = if (stableGesture != HandGestureClassifier.Gesture.NONE) stableGesture else rawGesture
        tvGestureIcon.text = gestureToShow.emoji
        tvGestureLabel.text = if (gestureToShow == HandGestureClassifier.Gesture.NONE) {
            "No hand"
        } else {
            gestureToShow.label
        }

        // Hide hint once a gesture is detected
        if (gestureToShow != HandGestureClassifier.Gesture.NONE) {
            if (tvCameraHint.visibility == View.VISIBLE) {
                tvCameraHint.animate().alpha(0f).setDuration(300).withEndAction {
                    tvCameraHint.visibility = View.GONE
                }.start()
            }
        }
    }

    // Play button: only 2 states — camera not ready (disabled) / camera ready (always enabled)
    private fun updatePlayButtonState() {
        if (!cameraReady) {
            btnPlay.isEnabled = false
            btnPlay.text = getString(R.string.starting_camera)
            btnPlay.setTextColor(0x88FFFFFF.toInt())
            btnPlay.setBackgroundResource(R.drawable.bg_gradient_button_disabled)
            return
        }

        // Camera is ready → Play is ALWAYS enabled. No gesture gate!
        btnPlay.isEnabled = true
        btnPlay.text = getString(R.string.play_round_emoji)
        btnPlay.setTextColor(ContextCompat.getColor(this, R.color.white))
        btnPlay.setBackgroundResource(R.drawable.bg_gradient_button)

        if (!isPlaying) {
            btnPlay.animate().scaleX(1.03f).scaleY(1.03f).setDuration(400)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    btnPlay.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
                }.start()
        }
    }

    // ─── Camera Setup ─── //

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private var frameTimestamp = 0L
    private var lastAnalyzedTimestamp = 0L

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(640, 480),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.i(TAG, "Camera started successfully")

                runOnUiThread {
                    cameraReady = true
                    updatePlayButtonState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker ?: run {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAnalyzedTimestamp < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalyzedTimestamp = now

        val rawBitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap: Bitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, false)
            rawBitmap.recycle()
            rotated
        } else {
            rawBitmap
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestamp = System.currentTimeMillis()

        val safeTimestamp = if (timestamp <= frameTimestamp) frameTimestamp + 1 else timestamp
        frameTimestamp = safeTimestamp

        try {
            landmarker.detectAsync(mpImage, safeTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
        }

        imageProxy.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cameraReady) {
            startCamera()
        }
    }

    // ─── Haptic Feedback ─── //

    private fun vibrateLight() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        } catch (_: Exception) { }
    }

    private fun vibrateMedium() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80)
            }
        } catch (_: Exception) { }
    }

    private fun vibrateStrong() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, 255))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        } catch (_: Exception) { }
    }

    // ─── Game Logic ─── //

    private fun playRound() {
        if (isPlaying) return

        // NO gesture gate — game starts immediately!
        // Snapshot current gesture as fallback (may be NONE — that's fine)
        isPlaying = true
        isCountingDown = true
        gestureAtPlayTap = when {
            stableGesture != HandGestureClassifier.Gesture.NONE -> stableGesture
            rawGesture != HandGestureClassifier.Gesture.NONE -> rawGesture
            else -> null
        }

        btnPlay.isEnabled = false
        btnPlay.text = getString(R.string.play_round_emoji)
        btnPlay.setBackgroundResource(R.drawable.bg_gradient_button_disabled)
        btnPlay.setTextColor(0x88FFFFFF.toInt())
        btnReset.isEnabled = false
        btnReset.alpha = 0.5f
        resultPanel.visibility = View.GONE

        val aiMove = GameLogic.getAiMove()
        startCountdown(aiMove)
    }

    private fun startCountdown(finalAiMove: HandGestureClassifier.Gesture) {
        val words = listOf("Stone", "Paper", "Scissors")
        var step = 0

        countdownOverlay.visibility = View.VISIBLE
        countdownOverlay.alpha = 0f
        countdownOverlay.animate().alpha(1f).setDuration(200).setListener(null).start()

        tvCountdown.text = words[0]
        animateCountdownText()
        vibrateLight()

        tvHoldGesture.visibility = View.VISIBLE
        tvHoldGesture.alpha = 0f
        tvHoldGesture.animate().alpha(0.9f).setDuration(300).setStartDelay(300).start()

        var cycleCount = 0
        val cycleRunnable = object : Runnable {
            override fun run() {
                if (!isCountingDown) return
                tvAiHand.text = aiCycleEmojis[cycleCount % aiCycleEmojis.size]
                cycleCount++
                ObjectAnimator.ofFloat(tvAiHand, "translationY", 0f, -12f, 6f, -6f, 0f).apply {
                    duration = 250
                    start()
                }
                handler.postDelayed(this, 150)
            }
        }
        handler.post(cycleRunnable)

        val tick = object : Runnable {
            override fun run() {
                if (!isCountingDown) return
                step++
                if (step < words.size) {
                    tvCountdown.text = words[step]
                    animateCountdownText()
                    vibrateLight()
                    handler.postDelayed(this, 800)
                } else {
                    handler.removeCallbacks(cycleRunnable)
                    isCountingDown = false

                    // Capture gesture at SHOOT — best available, with Play-tap as final fallback
                    capturedGesture = when {
                        stableGesture != HandGestureClassifier.Gesture.NONE -> stableGesture
                        rawGesture != HandGestureClassifier.Gesture.NONE -> rawGesture
                        else -> gestureAtPlayTap
                    }

                    vibrateStrong()

                    tvAiHand.text = finalAiMove.emoji
                    ObjectAnimator.ofFloat(tvAiHand, "scaleX", 0.5f, 1.3f, 1f).apply {
                        duration = 400
                        interpolator = OvershootInterpolator()
                        start()
                    }
                    ObjectAnimator.ofFloat(tvAiHand, "scaleY", 0.5f, 1.3f, 1f).apply {
                        duration = 400
                        interpolator = OvershootInterpolator()
                        start()
                    }

                    tvCountdown.text = getString(R.string.shoot)
                    animateCountdownText()

                    tvHoldGesture.animate().alpha(0f).setDuration(200).start()

                    handler.postDelayed({
                        countdownOverlay.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    countdownOverlay.visibility = View.GONE
                                    countdownOverlay.animate().setListener(null)
                                    finishRound(finalAiMove)
                                }
                            })
                            .start()
                    }, 1000)
                }
            }
        }

        handler.postDelayed(tick, 800)
    }

    private fun animateCountdownText() {
        tvCountdown.scaleX = 0.5f
        tvCountdown.scaleY = 0.5f
        tvCountdown.alpha = 0f
        tvCountdown.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun finishRound(aiMove: HandGestureClassifier.Gesture) {
        val humanMove = capturedGesture
        capturedGesture = null
        gestureAtPlayTap = null

        if (humanMove == null) {
            tvResultHumanEmoji.text = "❓"
            tvResultHumanGesture.text = "None"
            tvResultAiEmoji.text = "—"
            tvResultAiGesture.text = "—"
            tvResultOutcome.text = getString(R.string.no_gesture_hint)
            tvResultOutcome.setTextColor(ContextCompat.getColor(this, R.color.color_draw))
            tvResultOutcome.textSize = 16f
            showResultPanel()

            vibrateMedium()

            isPlaying = false
            btnReset.isEnabled = true
            btnReset.alpha = 1.0f
            updatePlayButtonState()
            return
        }

        val result = GameLogic.determineWinner(humanMove, aiMove)
        showResult(humanMove, aiMove, result)

        when (result) {
            GameLogic.Result.WIN -> {
                humanScore++
                animateScoreChange(tvHumanScore, humanScore)
                flashCameraBorder(R.color.color_win_border)
                vibrateStrong()
            }
            GameLogic.Result.LOSE -> {
                aiScore++
                animateScoreChange(tvAiScore, aiScore)
                flashCameraBorder(R.color.color_lose_border)
                vibrateMedium()
            }
            GameLogic.Result.DRAW -> {
                flashCameraBorder(R.color.color_draw_border)
                vibrateLight()
            }
        }

        logAdapter.addEntry(GameLogic.LogEntry(roundNumber, humanMove, aiMove, result))
        tvLogEmpty.visibility = View.GONE
        rvLog.scrollToPosition(0)

        roundNumber++
        tvRoundNumber.text = roundNumber.toString()

        isPlaying = false
        btnReset.isEnabled = true
        btnReset.alpha = 1.0f
        updatePlayButtonState()
    }

    private fun animateScoreChange(scoreView: TextView, newScore: Int) {
        scoreView.text = newScore.toString()
        scoreView.animate()
            .scaleX(1.8f).scaleY(1.8f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(5f))
            .withEndAction {
                scoreView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun flashCameraBorder(colorRes: Int) {
        val flashColor = ContextCompat.getColor(this, colorRes)
        val bg = cameraBorderFlash.background
        if (bg is GradientDrawable) {
            bg.setStroke(12, flashColor)
        }

        cameraBorderFlash.alpha = 0f
        cameraBorderFlash.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                cameraBorderFlash.animate()
                    .alpha(0f)
                    .setDuration(800)
                    .setStartDelay(400)
                    .start()
            }
            .start()
    }

    private fun showResult(
        humanMove: HandGestureClassifier.Gesture,
        aiMove: HandGestureClassifier.Gesture,
        result: GameLogic.Result
    ) {
        tvResultHumanEmoji.text = humanMove.emoji
        tvResultHumanGesture.text = humanMove.label
        tvResultAiEmoji.text = aiMove.emoji
        tvResultAiGesture.text = aiMove.label
        tvResultOutcome.textSize = 24f

        when (result) {
            GameLogic.Result.WIN -> {
                tvResultOutcome.text = getString(R.string.you_win)
                tvResultOutcome.setTextColor(ContextCompat.getColor(this, R.color.color_win))
            }
            GameLogic.Result.LOSE -> {
                tvResultOutcome.text = getString(R.string.ai_wins)
                tvResultOutcome.setTextColor(ContextCompat.getColor(this, R.color.color_lose))
            }
            GameLogic.Result.DRAW -> {
                tvResultOutcome.text = getString(R.string.draw)
                tvResultOutcome.setTextColor(ContextCompat.getColor(this, R.color.color_draw))
            }
        }

        showResultPanel()
    }

    private fun showResultPanel() {
        resultPanel.visibility = View.VISIBLE
        resultPanel.alpha = 0f
        resultPanel.translationY = 30f
        resultPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        listOf(tvResultHumanEmoji, tvResultAiEmoji).forEach { tv ->
            tv.scaleX = 0f
            tv.scaleY = 0f
            tv.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(2f))
                .start()
        }
    }

    private fun resetGame() {
        isCountingDown = false
        isPlaying = false

        handler.removeCallbacksAndMessages(null)

        countdownOverlay.animate().cancel()
        countdownOverlay.animate().setListener(null)
        cameraBorderFlash.animate().cancel()
        tvHoldGesture.animate().cancel()
        btnPlay.animate().cancel()

        countdownOverlay.visibility = View.GONE
        countdownOverlay.alpha = 0f
        tvHoldGesture.visibility = View.GONE
        cameraBorderFlash.alpha = 0f

        humanScore = 0
        aiScore = 0
        roundNumber = 1
        capturedGesture = null
        gestureAtPlayTap = null
        tvHumanScore.text = "0"
        tvAiScore.text = "0"
        tvRoundNumber.text = "1"
        resultPanel.visibility = View.GONE

        if (logAdapter.itemCount > 0) {
            logAdapter.clear()
        }

        tvLogEmpty.visibility = View.VISIBLE
        vibrateLight()
        btnReset.isEnabled = true
        btnReset.alpha = 1.0f
        updatePlayButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        handLandmarker?.close()
    }
}
