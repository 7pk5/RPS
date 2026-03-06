package com.pari.rps

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var tvLogEmpty: TextView
    private lateinit var rvLog: RecyclerView

    // State
    private var handLandmarker: HandLandmarker? = null
    private var currentGesture = HandGestureClassifier.Gesture.NONE
    private var humanScore = 0
    private var aiScore = 0
    private var roundNumber = 1
    private var isPlaying = false

    private val logAdapter = LogAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // AI cycling emojis for countdown animation
    private val aiCycleEmojis = listOf("✊", "✋", "✌️")

    // Permission launcher
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
        tvLogEmpty = findViewById(R.id.tvLogEmpty)
        rvLog = findViewById(R.id.rvLog)
    }

    private fun setupRecyclerView() {
        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter
    }

    private fun setupClickListeners() {
        btnPlay.setOnClickListener { playRound() }
        btnReset.setOnClickListener { resetGame() }
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
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
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
        if (result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks()[0]
            currentGesture = HandGestureClassifier.classify(landmarks)
        } else {
            currentGesture = HandGestureClassifier.Gesture.NONE
        }

        runOnUiThread {
            tvGestureIcon.text = currentGesture.emoji
            tvGestureLabel.text = if (currentGesture == HandGestureClassifier.Gesture.NONE) {
                "No hand"
            } else {
                currentGesture.label
            }
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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Image analysis for MediaPipe
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            // Front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.i(TAG, "Camera started successfully")

                runOnUiThread {
                    btnPlay.isEnabled = true
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

        val bitmap = imageProxy.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestamp = System.currentTimeMillis()

        // Ensure timestamps are strictly increasing
        val safeTimestamp = if (timestamp <= frameTimestamp) frameTimestamp + 1 else timestamp
        frameTimestamp = safeTimestamp

        try {
            landmarker.detectAsync(mpImage, safeTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
        }

        imageProxy.close()
    }

    // ─── Game Logic ─── //

    private fun playRound() {
        if (isPlaying) return
        isPlaying = true
        btnPlay.isEnabled = false
        resultPanel.visibility = View.GONE

        val aiMove = GameLogic.getAiMove()
        startCountdown(aiMove)
    }

    private fun startCountdown(finalAiMove: HandGestureClassifier.Gesture) {
        val words = listOf("Stone", "Paper", "Scissors")
        var step = 0

        countdownOverlay.visibility = View.VISIBLE
        countdownOverlay.alpha = 0f
        countdownOverlay.animate().alpha(1f).setDuration(200).start()

        tvCountdown.text = words[0]
        animateCountdownText()

        // AI hand cycling
        var cycleCount = 0
        val cycleRunnable = object : Runnable {
            override fun run() {
                tvAiHand.text = aiCycleEmojis[cycleCount % aiCycleEmojis.size]
                cycleCount++
                // Shake animation
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
                step++
                if (step < words.size) {
                    tvCountdown.text = words[step]
                    animateCountdownText()
                    handler.postDelayed(this, 900)
                } else {
                    // SHOOT!
                    handler.removeCallbacks(cycleRunnable)

                    // Show AI's actual move
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

                    handler.postDelayed({
                        countdownOverlay.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    countdownOverlay.visibility = View.GONE
                                    finishRound(finalAiMove)
                                }
                            })
                            .start()
                    }, 1200)
                }
            }
        }

        handler.postDelayed(tick, 900)
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
        val humanMove = if (currentGesture == HandGestureClassifier.Gesture.NONE) null else currentGesture

        if (humanMove == null) {
            // No gesture detected
            tvResultHumanEmoji.text = "❓"
            tvResultHumanGesture.text = "None"
            tvResultAiEmoji.text = "—"
            tvResultAiGesture.text = "—"
            tvResultOutcome.text = getString(R.string.no_gesture)
            tvResultOutcome.setTextColor(ContextCompat.getColor(this, R.color.color_draw))
            showResultPanel()
            isPlaying = false
            btnPlay.isEnabled = true
            return
        }

        val result = GameLogic.determineWinner(humanMove, aiMove)
        showResult(humanMove, aiMove, result)

        // Update scores
        when (result) {
            GameLogic.Result.WIN -> {
                humanScore++
                tvHumanScore.text = humanScore.toString()
            }
            GameLogic.Result.LOSE -> {
                aiScore++
                tvAiScore.text = aiScore.toString()
            }
            else -> {}
        }

        // Add log entry
        logAdapter.addEntry(GameLogic.LogEntry(roundNumber, humanMove, aiMove, result))
        tvLogEmpty.visibility = View.GONE
        rvLog.scrollToPosition(0)

        roundNumber++
        tvRoundNumber.text = roundNumber.toString()

        isPlaying = false
        btnPlay.isEnabled = true
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

        // Bounce emojis
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
        humanScore = 0
        aiScore = 0
        roundNumber = 1
        tvHumanScore.text = "0"
        tvAiScore.text = "0"
        tvRoundNumber.text = "1"
        resultPanel.visibility = View.GONE
        logAdapter.clear()
        tvLogEmpty.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarker?.close()
    }
}
