package com.example.virtualeye

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.virtualeye.databinding.ActivityMainBinding
import com.example.virtualeye.features.*
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.graphics.RectF

class MainActivity : AppCompatActivity(), 
    ObjectDetectionListener,
    TextRecognitionListener,
    SceneRecognitionListener,
    ObstacleDetectionListener,
    NavigationListener,
    EmergencyAlertListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var objectDetectionManager: ObjectDetectionManager
    private lateinit var sceneRecognitionManager: SceneRecognitionManager
    private lateinit var textRecognitionManager: TextRecognitionManager
    private lateinit var obstacleDetectionManager: ObstacleDetectionManagerAdapter
    private lateinit var navigationManager: NavigationManagerAdapter
    private lateinit var emergencyAlertManager: EmergencyAlertManagerAdapter
    private lateinit var navigationObstacleHandler: NavigationObstacleHandlerAdapter
    private lateinit var translationManager: TranslationManagerAdapter
    private lateinit var cameraProvider: ProcessCameraProvider

    private var isObjectDetectionEnabled = false
    private var isSceneRecognitionEnabled = false
    private var isTextRecognitionEnabled = false

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set default language to English
            val result = textToSpeech.setLanguage(Locale.ENGLISH)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Selected language is not supported.")
                Toast.makeText(this, "Default language not supported.", Toast.LENGTH_SHORT).show()
            } else {
                Log.i(TAG, "TextToSpeech initialized successfully.")
                
                // Initialize object detection manager now that TTS is ready
                objectDetectionManager = ObjectDetectionManager(
                    context = this,
                    textToSpeech = textToSpeech,
                    listener = this
                )
                Log.d(TAG, "ObjectDetectionManager initialized")

                // Enable object detection by default
                isObjectDetectionEnabled = true
                
                // Start camera after manager initialization
                startCamera()
                
                // Update UI
                updateButtonAppearance()
                
                // Announce ready state
                textToSpeech.speak(
                    "Object detection ready",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "init_ready"
                )
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed.")
            Toast.makeText(this, "Text to speech initialization failed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchLanguage(languageCode: String? = null) {
        if (!::textToSpeech.isInitialized) {
            Log.e(TAG, "Cannot switch language - TTS not initialized")
            return
        }
        
        // Our specific supported languages in the desired cycle order - removed Kannada
        val ourSupportedLanguages = listOf("en", "te", "hi", "ta", "ml", "ko", "ja")
        
        // If a specific language was requested, try to use that
        if (languageCode != null) {
            try {
                val targetLocale = Locale(languageCode)
                val availability = textToSpeech.isLanguageAvailable(targetLocale)
                
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    // Language is available, switch to it
                    val result = textToSpeech.setLanguage(targetLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Language $languageCode not supported")
                        Toast.makeText(this, "Language $languageCode not supported", Toast.LENGTH_SHORT).show()
                    } else {
                        // Language successfully changed
                        val languageName = translationManager.getLanguageName(targetLocale.language)
                        Toast.makeText(this, "Switched to $languageName", Toast.LENGTH_SHORT).show()
                        speakText("Switched to $languageName")
                        
                        // Update UI - keep button text as just "Languages"
                        binding.languageSelectButton.text = "Languages"
                    }
                } else {
                    Log.e(TAG, "Language $languageCode not available")
                    Toast.makeText(this, "Language $languageCode not available", Toast.LENGTH_SHORT).show()
                }
                
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error switching to specific language $languageCode: ${e.message}")
            }
        }
        
        // Get current language
        val currentLocale = textToSpeech.language ?: Locale.ENGLISH
        val currentLanguageCode = currentLocale.language
        
        // Find the index of the current language in our list
        val currentIndex = ourSupportedLanguages.indexOf(currentLanguageCode)
        
        // Get the next language in the list (or go back to the first one)
        val nextIndex = if (currentIndex >= 0 && currentIndex < ourSupportedLanguages.size - 1) {
            currentIndex + 1
        } else {
            0 // Start from the beginning if not found or at the end
        }
        
        // Switch to the next language
        val nextLanguageCode = ourSupportedLanguages[nextIndex]
        val nextLocale = Locale(nextLanguageCode)
        val result = textToSpeech.setLanguage(nextLocale)
        
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language $nextLanguageCode not supported")
            Toast.makeText(this, "Language $nextLanguageCode not supported", Toast.LENGTH_SHORT).show()
            
            // Try the next language
            if (ourSupportedLanguages.size > 1) {
                switchLanguage(ourSupportedLanguages[(nextIndex + 1) % ourSupportedLanguages.size])
            }
        } else {
            // Language successfully changed
            val languageName = translationManager.getLanguageName(nextLocale.language)
            Toast.makeText(this, "Switched to $languageName", Toast.LENGTH_SHORT).show()
            speakText("Switched to $languageName")
            
            // Update UI - keep button text as just "Languages"
            binding.languageSelectButton.text = "Languages"
            
            // If we're in text recognition mode, update the UI with the new language
            if (isTextRecognitionEnabled) {
                binding.sceneRecognitionTextView.text = "Text recognition active - point camera at text to read in $languageName"
            }
        }
    }

    private fun showLanguageSelectionDialog(callback: (Locale) -> Unit) {
        // Ensure TTS is initialized before accessing languages
        if (!::textToSpeech.isInitialized || textToSpeech.availableLanguages == null) {
            Toast.makeText(this, "TTS Languages not available yet.", Toast.LENGTH_SHORT).show()
            return
        }

        // Define supported languages with their display names - removed Kannada
        val supportedLanguages = mapOf(
            "en" to "English",
            "hi" to "Hindi",
            "te" to "Telugu",
            "ta" to "Tamil",
            "ml" to "Malayalam",
            "ko" to "Korean",
            "ja" to "Japanese"
        )

        val availableLocales: List<Locale> = textToSpeech.availableLanguages
            ?.filterNotNull()
            ?.filter { locale -> supportedLanguages.containsKey(locale.language) }
            ?.sortedBy { locale -> supportedLanguages[locale.language] }
            ?: listOf(Locale.ENGLISH)

        val languageNames: Array<String> = availableLocales.map { locale -> 
            supportedLanguages[locale.language] ?: locale.displayName 
        }.toTypedArray()

        val currentLocale = textToSpeech.voice?.locale ?: textToSpeech.language ?: Locale.getDefault()
        var selectedIndex = availableLocales.indexOfFirst { locale -> locale == currentLocale }
        if (selectedIndex == -1) selectedIndex = availableLocales.indexOfFirst { locale -> locale.language == currentLocale.language }
        if (selectedIndex == -1) selectedIndex = 0 // Default to first if not found

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Language")
            .setSingleChoiceItems(languageNames, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("OK") { dialog, _ ->
                if (selectedIndex >= 0 && selectedIndex < availableLocales.size) {
                    val selectedLocale = availableLocales[selectedIndex]
                    
                    // First clear any pending translations
                    pendingTranslationMessage = null
                    fallbackRunnable?.let {
                        Handler(Looper.getMainLooper()).removeCallbacks(it)
                        fallbackRunnable = null
                    }
                    
                    // Update UI to show language change
                    val langName = supportedLanguages[selectedLocale.language] ?: selectedLocale.displayName
                    runOnUiThread {
                        binding.sceneRecognitionTextView.text = "Changing language to $langName"
                        binding.sceneRecognitionTextView.setBackgroundColor(
                            resources.getColor(android.R.color.holo_blue_light, theme)
                        )
                    }
                    
                    // Set TTS language
                    val result = textToSpeech.setLanguage(selectedLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // Long press on language button shows a popup with available languages
    binding.languageSelectButton.setOnLongClickListener {
        // Show popup menu with common languages
        val popupMenu = PopupMenu(this, binding.languageSelectButton)
        
        // Add requested languages - removed Kannada
        popupMenu.menu.add("English").setOnMenuItemClickListener {
            switchLanguage("en")
            true
        }
        popupMenu.menu.add("Telugu").setOnMenuItemClickListener {
            switchLanguage("te")
            true
        }
        popupMenu.menu.add("Hindi").setOnMenuItemClickListener {
            switchLanguage("hi")
            true
        }
        popupMenu.menu.add("Tamil").setOnMenuItemClickListener {
            switchLanguage("ta")
            true
        }
        popupMenu.menu.add("Malayalam").setOnMenuItemClickListener {
            switchLanguage("ml")
            true
        }
        popupMenu.menu.add("Korean").setOnMenuItemClickListener {
            switchLanguage("ko")
            true
        }
        popupMenu.menu.add("Japanese").setOnMenuItemClickListener {
            switchLanguage("ja")
            true
        }
        
        // Show the popup menu
        popupMenu.show()
        
        // Return true to indicate the long click was handled
        true
    }

    private fun initializeManagers() {
        try {
            // Wait for TextToSpeech to be ready
            if (!::textToSpeech.isInitialized) {
                Log.e(TAG, "TextToSpeech not initialized")
                return
            }

            // Initialize object detection manager first
            objectDetectionManager = ObjectDetectionManager(
                context = this,
                textToSpeech = textToSpeech,
                listener = this
            )
            Log.d(TAG, "ObjectDetectionManager initialized successfully")

            // Initialize other managers...
            // ... existing code ...
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing managers", e)
            Toast.makeText(this, "Failed to initialize managers: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check permissions
        if (allPermissionsGranted()) {
            requestPermissions()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up object detection button
        binding.objectDetectionButton.setOnClickListener {
            isObjectDetectionEnabled = !isObjectDetectionEnabled
            if (isObjectDetectionEnabled) {
                // Disable other modes
                isSceneRecognitionEnabled = false
                isTextRecognitionEnabled = false
                
                // Update UI
                binding.objectDetectionTextView.text = "Object Detection: Active"
                binding.objectDetectionTextView.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                )
                
                // Restart camera with object detection
                startCamera()
                
                // Announce activation
                textToSpeech.speak(
                    "Object detection activated",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "obj_activation"
                )
            } else {
                // Update UI
                binding.objectDetectionTextView.text = "Object Detection: Inactive"
                binding.objectDetectionTextView.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.transparent)
                )
                
                // Restart camera without object detection
                startCamera()
                
                // Announce deactivation
                textToSpeech.speak(
                    "Object detection deactivated",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "obj_deactivation"
                )
            }
            updateButtonAppearance()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Preview use case
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    }

                // Image analysis use case for object detection
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                // Set up analyzer if object detection is enabled
                if (isObjectDetectionEnabled && ::objectDetectionManager.isInitialized) {
                    Log.d(TAG, "Setting up object detection analyzer")
                    imageAnalyzer.setAnalyzer(
                        cameraExecutor,
                        objectDetectionManager
                    )
                } else {
                    Log.d(TAG, "Object detection not enabled or not initialized")
                }

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // Unbind all use cases before rebinding
                    cameraProvider.unbindAll()

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )

                    Log.d(TAG, "Camera setup successful with object detection: $isObjectDetectionEnabled")
                    
                    // Update UI to show status
                    runOnUiThread {
                        if (isObjectDetectionEnabled) {
                            binding.objectDetectionTextView.text = "Object Detection: Active"
                            binding.objectDetectionTextView.setBackgroundColor(
                                ContextCompat.getColor(this, android.R.color.holo_green_light)
                            )
                        } else {
                            binding.objectDetectionTextView.text = "Object Detection: Inactive"
                            binding.objectDetectionTextView.setBackgroundColor(
                                ContextCompat.getColor(this, android.R.color.transparent)
                            )
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Use case binding failed", e)
                    Toast.makeText(this, "Failed to bind camera: ${e.message}", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera provider is not available", e)
                Toast.makeText(this, "Camera provider not available: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateButtonAppearance() {
        binding.objectDetectionButton.setBackgroundColor(
            if (isObjectDetectionEnabled)
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            else
                ContextCompat.getColor(this, android.R.color.darker_gray)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::objectDetectionManager.isInitialized) {
            objectDetectionManager.shutdown()
        }
        cameraExecutor.shutdown()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ObjectDetectionListener implementation
    override fun onObjectDetected(objectName: String, confidence: Float, boundingBox: RectF?) {
        runOnUiThread {
            binding.objectDetectionTextView.text = "Detected: $objectName (${String.format("%.2f", confidence)})"
            binding.objectDetectionTextView.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
            
            // Reset background after delay
            Handler(Looper.getMainLooper()).postDelayed({
                binding.objectDetectionTextView.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.transparent)
                )
            }, 2000)
        }
    }

    override fun onNoObjectsDetected() {
        runOnUiThread {
            binding.objectDetectionTextView.text = "No objects detected"
            binding.objectDetectionTextView.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.transparent)
            )
        }
    }

    override fun onObjectDetectionError(error: String) {
        runOnUiThread {
            binding.objectDetectionTextView.text = "Error: $error"
            binding.objectDetectionTextView.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_light)
            )
            Toast.makeText(this, "Object detection error: $error", Toast.LENGTH_SHORT).show()
        }
    }
} 