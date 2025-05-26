package com.example.virtualeye

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.virtualeye.features.ObjectDetectionListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

class ObjectDetectionManager(
    private val context: Context,
    private val textToSpeech: TextToSpeech,
    private val listener: ObjectDetectionListener? = null
) : ImageAnalysis.Analyzer {
    private val TAG = "ObjectDetectionManager"
    private val isProcessing = AtomicBoolean(false)
    private var lastSpokenTime = 0L
    private val SPEECH_COOLDOWN = 3000L // 3 seconds cooldown between speech outputs
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var frameCount = 0
    private val PROCESS_EVERY_N_FRAMES = 3 // Process every 3rd frame

    // Use ML Kit's ImageLabeler with very low confidence threshold
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f) // Increased threshold for more reliable detections
            .build()
    )

    // Priority items to detect with more common variations
    private val priorityItems = listOf(
        // Common objects (high priority)
        "person", "face", "hand", "phone", "book", "chair", "table",
        // Electronics
        "laptop", "computer", "tablet", "screen", "monitor", "keyboard", "mouse",
        // Furniture
        "desk", "bed", "sofa", "couch", "cabinet", "shelf",
        // Personal items
        "bag", "backpack", "wallet", "watch", "glasses",
        // Food and drink
        "cup", "bottle", "glass", "plate", "bowl", "food",
        // Room elements
        "door", "window", "wall", "floor", "ceiling", "light",
        // Common items
        "box", "paper", "pen", "pencil", "book", "clock"
    )

    init {
        Log.d(TAG, "ObjectDetectionManager initialized with ${priorityItems.size} priority items")
    }

    override fun analyze(imageProxy: ImageProxy) {
        frameCount++
        if (frameCount % PROCESS_EVERY_N_FRAMES != 0) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            Log.w(TAG, "Skipping frame - null media image")
            imageProxy.close()
            return
        }

        if (isProcessing.get()) {
            Log.v(TAG, "Skipping frame - still processing previous frame")
            imageProxy.close()
            return
        }

        isProcessing.set(true)
        Log.v(TAG, "Processing frame #$frameCount")

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        
        imageLabeler.process(image)
            .addOnSuccessListener { labels ->
                // Log all detected labels for debugging
                val allLabels = labels.joinToString { "${it.text}(${it.confidence})" }
                Log.d(TAG, "All detected labels: $allLabels")
                
                // Filter and sort labels by confidence
                val relevantLabels = labels
                    .filter { label ->
                        val labelText = label.text.lowercase()
                        val isRelevant = priorityItems.any { item -> 
                            labelText.contains(item) || item.contains(labelText)
                        }
                        if (isRelevant) {
                            Log.d(TAG, "Found relevant label: $labelText with confidence ${label.confidence}")
                        }
                        isRelevant
                    }
                    .sortedByDescending { it.confidence }

                if (relevantLabels.isNotEmpty()) {
                    // Get the most confident detection
                    val topLabel = relevantLabels[0]
                    Log.d(TAG, "Processing top detection: ${topLabel.text} with confidence ${topLabel.confidence}")
                    processDetection(topLabel, mediaImage.width, mediaImage.height)
                } else {
                    Log.v(TAG, "No relevant objects detected")
                    handler.post {
                        listener?.onNoObjectsDetected()
                    }
                }

                isProcessing.set(false)
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Object detection failed", e)
                handler.post {
                    listener?.onObjectDetectionError(e.message ?: "Unknown error")
                }
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    private fun processDetection(label: ImageLabel, imageWidth: Int, imageHeight: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpokenTime < SPEECH_COOLDOWN) {
            Log.v(TAG, "Skipping speech - cooldown active")
            return
        }

        val objectName = label.text
        val confidence = label.confidence
        
        Log.d(TAG, "Processing detection: $objectName with confidence $confidence")

        // Create a dummy bounding box in the center
        val boundingBox = RectF(
            imageWidth * 0.25f,
            imageHeight * 0.25f,
            imageWidth * 0.75f,
            imageHeight * 0.75f
        )

        // Create spatial description
        val description = createSpatialDescription(objectName, boundingBox, imageWidth, imageHeight)

        // Speak the detection
        speakDetection(objectName, description)

        // Notify listener
        handler.post {
            listener?.onObjectDetected(objectName, confidence, boundingBox)
        }

        lastSpokenTime = currentTime
    }

    private fun createSpatialDescription(objectName: String, boundingBox: RectF, imageWidth: Int, imageHeight: Int): String {
        val centerX = boundingBox.centerX() / imageWidth
        val centerY = boundingBox.centerY() / imageHeight

        val horizontalPosition = when {
            centerX < 0.33f -> "on the left"
            centerX > 0.66f -> "on the right"
            else -> "in the center"
        }

        val verticalPosition = when {
            centerY < 0.33f -> "at the top"
            centerY > 0.66f -> "at the bottom"
            else -> "in the middle"
        }

        return "$horizontalPosition $verticalPosition"
    }

    private fun speakDetection(objectName: String, location: String) {
        val speechText = "Detected $objectName $location"
        Log.d(TAG, "Speaking: $speechText")
        textToSpeech.speak(
            speechText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "obj_${System.currentTimeMillis()}"
        )
    }

    fun getImageAnalyzer(): ImageAnalysis.Analyzer = this

    fun shutdown() {
        cameraExecutor.shutdown()
    }
} 