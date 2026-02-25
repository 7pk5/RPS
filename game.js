/* ================================================================
   RPS Arena — Game Engine
   MediaPipe Hand Landmarker + Gesture Classification + Game Logic
   ================================================================ */

import {
  HandLandmarker,
  FilesetResolver,
  DrawingUtils,
} from "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.18/vision_bundle.mjs";

// ─── DOM References ─── //
const video = document.getElementById("camera-feed");
const canvas = document.getElementById("landmark-canvas");
const ctx = canvas.getContext("2d");
const gestureIcon = document.getElementById("gesture-icon");
const gestureLabel = document.getElementById("gesture-label");
const countdownOverlay = document.getElementById("countdown-overlay");
const countdownText = document.getElementById("countdown-text");
const playBtn = document.getElementById("play-btn");
const resetBtn = document.getElementById("reset-btn");
const resultPanel = document.getElementById("result-panel");
const resultHumanEmoji = document.getElementById("result-human-emoji");
const resultHumanGesture = document.getElementById("result-human-gesture");
const resultAiEmoji = document.getElementById("result-ai-emoji");
const resultAiGesture = document.getElementById("result-ai-gesture");
const resultOutcome = document.getElementById("result-outcome");
const humanScoreEl = document.getElementById("human-score");
const aiScoreEl = document.getElementById("ai-score");
const roundNumberEl = document.getElementById("round-number");
const logList = document.getElementById("log-list");
const logEmpty = document.getElementById("log-empty");
const cameraPlaceholder = document.getElementById("camera-placeholder");

// ─── State ─── //
let handLandmarker = null;
let lastVideoTime = -1;
let currentGesture = "None";
let humanScore = 0;
let aiScore = 0;
let roundNumber = 1;
let isPlaying = false;   // true while countdown is active

const GESTURE_EMOJI = { Rock: "✊", Paper: "✋", Scissors: "✌️", None: "❓" };
const MOVES = ["Rock", "Paper", "Scissors"];

// ─── Voice (Web Speech API) ─── //

function speak(text, rate = 1.1) {
  const utter = new SpeechSynthesisUtterance(text);
  utter.volume = 1;      // max volume
  utter.rate = rate;
  utter.pitch = 1.05;
  speechSynthesis.speak(utter);
}

// ─── MediaPipe Initialisation ─── //

async function createHandLandmarker() {
  const vision = await FilesetResolver.forVisionTasks(
    "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.18/wasm"
  );

  handLandmarker = await HandLandmarker.createFromOptions(vision, {
    baseOptions: {
      modelAssetPath:
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task",
      delegate: "GPU",
    },
    runningMode: "VIDEO",
    numHands: 1,
    minHandDetectionConfidence: 0.6,
    minHandPresenceConfidence: 0.6,
    minTrackingConfidence: 0.6,
  });

  console.log("[RPS] HandLandmarker ready");
}

// ─── Camera Setup ─── //

async function enableCamera() {
  const stream = await navigator.mediaDevices.getUserMedia({
    video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } },
    audio: false,
  });
  video.srcObject = stream;

  return new Promise((resolve) => {
    video.onloadedmetadata = () => {
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      cameraPlaceholder.classList.add("hidden");
      resolve();
    };
  });
}

// ─── Gesture Classification ─── //

/**
 * Landmark indices (MediaPipe Hand):
 *   0  – WRIST
 *   1- 4  – THUMB  (CMC, MCP, IP, TIP)
 *   5- 8  – INDEX  (MCP, PIP, DIP, TIP)
 *   9-12  – MIDDLE (MCP, PIP, DIP, TIP)
 *  13-16  – RING   (MCP, PIP, DIP, TIP)
 *  17-20  – PINKY  (MCP, PIP, DIP, TIP)
 *
 * A finger is "extended" when TIP.y < PIP.y  (in normalised coords, Y=0 is top).
 * Thumb is extended when TIP.x is far from wrist relative to hand width.
 */

function classifyGesture(landmarks) {
  if (!landmarks || landmarks.length === 0) return "None";

  const lm = landmarks;

  // helper: is finger extended? (for index/middle/ring/pinky)
  const isExtended = (tipIdx, pipIdx) => lm[tipIdx].y < lm[pipIdx].y;

  const indexUp = isExtended(8, 6);
  const middleUp = isExtended(12, 10);
  const ringUp = isExtended(16, 14);
  const pinkyUp = isExtended(20, 18);

  // Thumb: compare tip X distance from wrist vs MCP X distance from wrist
  const thumbTipDist = Math.abs(lm[4].x - lm[0].x);
  const thumbMcpDist = Math.abs(lm[2].x - lm[0].x);
  const thumbUp = thumbTipDist > thumbMcpDist * 1.2;

  const extendedCount = [indexUp, middleUp, ringUp, pinkyUp].filter(Boolean).length;

  // ✋ Paper – 4 fingers extended (thumb may or may not be)
  if (extendedCount >= 3 && (thumbUp || extendedCount === 4)) return "Paper";

  // ✌️ Scissors – index + middle up, ring + pinky down
  if (indexUp && middleUp && !ringUp && !pinkyUp) return "Scissors";

  // ✊ Rock – all 4 fingers curled (thumb ignored)
  if (extendedCount <= 1 && !indexUp && !middleUp) return "Rock";

  return "None";
}

// ─── Detection Loop ─── //

function detectLoop() {
  if (!handLandmarker || video.readyState < 2) {
    requestAnimationFrame(detectLoop);
    return;
  }

  const now = performance.now();
  if (video.currentTime !== lastVideoTime) {
    lastVideoTime = video.currentTime;
    const results = handLandmarker.detectForVideo(video, now);

    // clear canvas
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    if (results.landmarks && results.landmarks.length > 0) {
      const hand = results.landmarks[0];

      // Draw landmarks + connectors
      const drawingUtils = new DrawingUtils(ctx);
      drawingUtils.drawConnectors(hand, HandLandmarker.HAND_CONNECTIONS, {
        color: "#6366f1",
        lineWidth: 3,
      });
      drawingUtils.drawLandmarks(hand, {
        color: "#22d3ee",
        lineWidth: 1,
        radius: 4,
      });

      // Classify
      currentGesture = classifyGesture(hand);
    } else {
      currentGesture = "None";
    }

    // Update live badge
    gestureIcon.textContent = GESTURE_EMOJI[currentGesture] || "❓";
    gestureLabel.textContent = currentGesture === "None" ? "No hand" : currentGesture;
  }

  requestAnimationFrame(detectLoop);
}

// ─── Game Logic ─── //

function getAiMove() {
  return MOVES[Math.floor(Math.random() * MOVES.length)];
}

function determineWinner(human, ai) {
  if (human === ai) return "draw";
  if (
    (human === "Rock" && ai === "Scissors") ||
    (human === "Paper" && ai === "Rock") ||
    (human === "Scissors" && ai === "Paper")
  ) return "win";
  return "lose";
}

function showResult(humanMove, aiMove, result) {
  resultHumanEmoji.textContent = GESTURE_EMOJI[humanMove];
  resultHumanGesture.textContent = humanMove;
  resultAiEmoji.textContent = GESTURE_EMOJI[aiMove];
  resultAiGesture.textContent = aiMove;

  resultOutcome.className = "result-outcome " + result;
  if (result === "win") resultOutcome.textContent = "🎉 You Win!";
  else if (result === "lose") resultOutcome.textContent = "💀 AI Wins!";
  else resultOutcome.textContent = "🤝 Draw!";

  resultPanel.classList.remove("hidden");
  // Re-trigger animation
  resultPanel.style.animation = "none";
  requestAnimationFrame(() => { resultPanel.style.animation = ""; });
}

function addLogEntry(humanMove, aiMove, result) {
  logEmpty.style.display = "none";
  const li = document.createElement("li");
  li.innerHTML =
    `<span class="log-round">R${roundNumber}  ${GESTURE_EMOJI[humanMove]} vs ${GESTURE_EMOJI[aiMove]}</span>` +
    `<span class="log-result ${result}">${result === "win" ? "You Win" : result === "lose" ? "AI Wins" : "Draw"}</span>`;
  logList.prepend(li);
}

function updateScores(result) {
  if (result === "win") humanScore++;
  if (result === "lose") aiScore++;
  humanScoreEl.textContent = humanScore;
  aiScoreEl.textContent = aiScore;
  roundNumber++;
  roundNumberEl.textContent = roundNumber;
}

// ─── Countdown & Play ─── //

function countdown() {
  const words = ["Stone", "Paper", "Scissors"];
  return new Promise((resolve) => {
    let step = 0;
    countdownOverlay.classList.remove("hidden");
    countdownText.textContent = words[step];
    speak(words[step] + "!", 1.0);

    const tick = () => {
      step++;
      if (step < words.length) {
        countdownText.textContent = words[step];
        speak(words[step] + "!", 1.0);
        // re-trigger pop animation
        countdownText.style.animation = "none";
        requestAnimationFrame(() => { countdownText.style.animation = ""; });
        setTimeout(tick, 900);
      } else {
        countdownText.textContent = "SHOOT!";
        speak("Shoot!", 1.2);
        countdownText.style.animation = "none";
        requestAnimationFrame(() => { countdownText.style.animation = ""; });
        setTimeout(() => {
          countdownOverlay.classList.add("hidden");
          resolve();
        }, 500);
      }
    };

    setTimeout(tick, 900);
  });
}

async function playRound() {
  if (isPlaying) return;
  isPlaying = true;
  playBtn.disabled = true;
  resultPanel.classList.add("hidden");

  await countdown();

  // Capture gesture at the moment "GO!" fires
  const humanMove = currentGesture === "None" ? null : currentGesture;

  if (!humanMove) {
    // No valid gesture detected
    resultOutcome.className = "result-outcome draw";
    resultOutcome.textContent = "❌ No gesture detected — try again!";
    resultHumanEmoji.textContent = "❓";
    resultHumanGesture.textContent = "None";
    resultAiEmoji.textContent = "—";
    resultAiGesture.textContent = "—";
    resultPanel.classList.remove("hidden");
    resultPanel.style.animation = "none";
    requestAnimationFrame(() => { resultPanel.style.animation = ""; });
    isPlaying = false;
    playBtn.disabled = false;
    return;
  }

  const aiMove = getAiMove();
  const result = determineWinner(humanMove, aiMove);

  // Voice announcements
  speak(humanMove + "!");
  setTimeout(() => speak("versus " + aiMove + "!"), 600);
  const outcomeText = result === "win" ? "You win!"
    : result === "lose" ? "A I wins!"
      : "It's a draw!";
  setTimeout(() => speak(outcomeText), 1400);

  showResult(humanMove, aiMove, result);
  addLogEntry(humanMove, aiMove, result);
  updateScores(result);

  isPlaying = false;
  playBtn.disabled = false;
}

function resetGame() {
  humanScore = 0;
  aiScore = 0;
  roundNumber = 1;
  humanScoreEl.textContent = "0";
  aiScoreEl.textContent = "0";
  roundNumberEl.textContent = "1";
  logList.innerHTML = "";
  logEmpty.style.display = "";
  resultPanel.classList.add("hidden");
}

// ─── Event Listeners ─── //
playBtn.addEventListener("click", playRound);
resetBtn.addEventListener("click", resetGame);

// ─── Boot ─── //
(async () => {
  try {
    await createHandLandmarker();
    await enableCamera();
    playBtn.disabled = false;
    detectLoop();
    console.log("[RPS] Game engine running");
  } catch (err) {
    console.error("[RPS] Initialisation error:", err);
    gestureLabel.textContent = "Error — see console";
  }
})();
