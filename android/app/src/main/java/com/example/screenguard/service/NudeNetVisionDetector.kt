package com.example.screenguard.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.Collections

object NudeNetVisionDetector {
    private const val TAG = "NudeNetVisionAI"
    private const val MODEL_ASSET_PATH = "models/320n.onnx"
    private const val INPUT_SIZE = 320

    // 18 Anatomical classes from NudeNet YOLOv8
    val LABELS = arrayOf(
        "FEMALE_GENITALIA_COVERED",
        "FACE_FEMALE",
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "FEET_EXPOSED",
        "BELLY_COVERED",
        "FEET_COVERED",
        "ARMPITS_COVERED",
        "ARMPITS_EXPOSED",
        "FACE_MALE",
        "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED",
        "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED"
    )

    // Strict Adult Explicit Classes
    val EXPLICIT_CLASSES = setOf(
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "BUTTOCKS_EXPOSED"
    )

    // Bikini / Swimwear / Lingerie Classes
    val SUGGESTIVE_CLASSES = setOf(
        "FEMALE_BREAST_COVERED",    // Bikini top / Bra / Revealing top
        "FEMALE_GENITALIA_COVERED", // Bikini bottom / Underwear
        "BUTTOCKS_COVERED"          // Swimwear / Underwear
    )

    private val TARGET_CLASSES = EXPLICIT_CLASSES + SUGGESTIVE_CLASSES

    data class DetectionResult(
        val label: String,
        val humanReadableName: String,
        val score: Float,
        val isExplicit: Boolean
    )

    @Volatile
    private var isInitialized = false
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private val mutex = Mutex()

    /**
     * Initializes the ONNX model from assets. Thread-safe and non-blocking.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && ortSession != null) return@withContext true

        mutex.withLock {
            if (isInitialized && ortSession != null) return@withLock true

            try {
                Log.i(TAG, "Loading YOLOv8 NudeNet ONNX model from assets...")
                val modelBytes: ByteArray = context.assets.open(MODEL_ASSET_PATH).use { input: InputStream ->
                    input.readBytes()
                }

                val env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(2)
                }

                val session = env.createSession(modelBytes, sessionOptions)
                ortEnv = env
                ortSession = session
                inputName = session.inputNames.iterator().next()
                isInitialized = true
                Log.i(TAG, "✓ YOLOv8 NudeNet Vision AI loaded successfully (${modelBytes.size / 1024} KB)")
                return@withLock true
            } catch (e: Exception) {
                Log.w(TAG, "NudeNet ONNX model not loaded: ${e.message}")
                return@withLock false
            }
        }
    }

    /**
     * Runs inference on the given bitmap.
     * Returns matching adult or bikini/swimwear detections above confidence threshold.
     */
    suspend fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.35f
    ): List<DetectionResult> = withContext(Dispatchers.Default) {
        val session = ortSession ?: return@withContext emptyList()
        val env = ortEnv ?: return@withContext emptyList()
        val inName = inputName ?: return@withContext emptyList()

        try {
            // Resize bitmap to 320x320
            val scaledBitmap = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            }

            val numPixels = INPUT_SIZE * INPUT_SIZE
            val pixels = IntArray(numPixels)
            scaledBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            // Convert to NCHW float buffer normalized to 0.0f - 1.0f
            val floatBuffer = FloatBuffer.allocate(1 * 3 * numPixels)
            val offsetG = numPixels
            val offsetB = numPixels * 2

            for (i in 0 until numPixels) {
                val color = pixels[i]
                floatBuffer.put(i, ((color shr 16) and 0xFF) / 255.0f)
                floatBuffer.put(offsetG + i, ((color shr 8) and 0xFF) / 255.0f)
                floatBuffer.put(offsetB + i, (color and 0xFF) / 255.0f)
            }
            floatBuffer.rewind()

            var tensor: OnnxTensor? = null
            var results: OrtSession.Result? = null
            val detections = mutableListOf<DetectionResult>()

            try {
                tensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
                results = session.run(Collections.singletonMap(inName, tensor))
                val outputTensor = results[0]

                // YOLOv8 output shape is [1, 22, 2100]
                val output = outputTensor.value as? Array<Array<FloatArray>>

                if (output != null && output.isNotEmpty() && output[0].size >= 22) {
                    val predictions = output[0]
                    val numAnchors = predictions[0].size // 2100

                    var bestMatchScore = 0f
                    var bestMatchLabel = ""

                    for (anchor in 0 until numAnchors) {
                        var anchorMaxScore = 0f
                        var anchorBestClass = -1

                        for (c in 0 until 18) {
                            val score = predictions[4 + c][anchor]
                            if (score > anchorMaxScore) {
                                anchorMaxScore = score
                                anchorBestClass = c
                            }
                        }

                        if (anchorMaxScore >= confThreshold && anchorBestClass >= 0 && anchorBestClass < LABELS.size) {
                            val label = LABELS[anchorBestClass]
                            if (TARGET_CLASSES.contains(label) && anchorMaxScore > bestMatchScore) {
                                bestMatchScore = anchorMaxScore
                                bestMatchLabel = label
                            }
                        }
                    }

                    if (bestMatchScore >= confThreshold && bestMatchLabel.isNotEmpty()) {
                        val isExplicit = EXPLICIT_CLASSES.contains(bestMatchLabel)
                        val friendlyName = when (bestMatchLabel) {
                            "FEMALE_BREAST_COVERED" -> "Bikini / Swimwear (Revealing)"
                            "FEMALE_GENITALIA_COVERED" -> "Swimwear / Undergarment"
                            "BUTTOCKS_COVERED" -> "Revealing Swimwear Bottom"
                            "FEMALE_BREAST_EXPOSED" -> "Explicit Adult Content"
                            "FEMALE_GENITALIA_EXPOSED" -> "Explicit Adult Content"
                            "MALE_GENITALIA_EXPOSED" -> "Explicit Adult Content"
                            "BUTTOCKS_EXPOSED" -> "Exposed Nudity"
                            "ANUS_EXPOSED" -> "Explicit Adult Content"
                            else -> "Explicit Content"
                        }

                        detections.add(
                            DetectionResult(
                                label = bestMatchLabel,
                                humanReadableName = friendlyName,
                                score = bestMatchScore,
                                isExplicit = isExplicit
                            )
                        )
                    }
                }
            } finally {
                try { tensor?.close() } catch (ignored: Exception) {}
                try { results?.close() } catch (ignored: Exception) {}
            }

            return@withContext detections
        } catch (e: Exception) {
            Log.e(TAG, "Vision detection error: ${e.message}", e)
            return@withContext emptyList()
        }
    }
}
