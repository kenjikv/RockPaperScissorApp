package com.kawaida.rockpaperscissorapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.kawaida.rockpaperscissorapp.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity(), GestureRecognizerHelper.GestureRecognizerListener {

    private var _binding: ActivityMainBinding? = null
    private val binding
        get() = _binding!!

    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper

    // Camara
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Las operaciones de bloqueo de ML se realizan utilizando este ejecutor
    private lateinit var backgroundExecutor: ExecutorService
    private var isGestureRecognitionActive = false

    // Variables para gestionar las imágenes y el resultado del juego
    private var lastImageShown: Int = R.drawable.rock
    private val images = listOf(R.drawable.rock, R.drawable.paper, R.drawable.scissor)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicialización del ejecutor en segundo plano para operaciones de ML
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Configuración inicial de la cámara
        binding.viewFinder.post {
            setUpCamera()
        }

        // Inicialización del reconocedor de gestos
        backgroundExecutor.execute {
            gestureRecognizerHelper = GestureRecognizerHelper(
                context = this,
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = GestureRecognizerHelper.DEFAULT_HAND_DETECTION_CONFIDENCE,
                minHandTrackingConfidence = GestureRecognizerHelper.DEFAULT_HAND_TRACKING_CONFIDENCE,
                minHandPresenceConfidence = GestureRecognizerHelper.DEFAULT_HAND_PRESENCE_CONFIDENCE,
                currentDelegate = GestureRecognizerHelper.DELEGATE_CPU,
                gestureRecognizerListener = this
            )
        }

        // Establecimiento del listener para el botón de inicio\
        binding.button.setOnClickListener {
            startCountdown()
        }
    }

    override fun onResume() {
        super.onResume()

        // Verificación de permisos para la cámara
        val permissionsRequired = arrayOf(Manifest.permission.CAMERA)

        val allPermissionsGranted = permissionsRequired.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allPermissionsGranted) {
            finish()
        }

        // Reconfiguración del reconocedor de gestos al volver a la actividad
        backgroundExecutor.execute {
            if (gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gestureRecognizerHelper.isInitialized) {
            // Close the Gesture Recognizer helper and release resources
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    // Configuración inicial de la cámara
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this)
        )
    }

    // Configuración de los casos de uso de la cámara
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Configuración del Preview
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        // Configuración del análisis de imágenes
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // Luego se puede asignar el analizador a la instancia
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        recognizeHand(image)
                    }
                }

        // Debe desvincular los casos de uso antes de volver a vincularlos.
        cameraProvider.unbindAll()

        try {
            // Vinculación de la cámara con los casos de uso
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Adjunte el proveedor de superficie del visor para obtener una vista previa del caso de uso
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
    }

    // Método para reconocer gestos de manos
    private fun recognizeHand(imageProxy: ImageProxy) {
        gestureRecognizerHelper.recognizeLiveStream(
            imageProxy = imageProxy,
        )
    }

    // Método para iniciar la cuenta regresiva
    private fun startCountdown() {
        isGestureRecognitionActive = true
        val timer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.button.visibility = View.GONE
                binding.number.visibility = View.VISIBLE
                binding.number.text = "${millisUntilFinished / 1000 + 1}"
                binding.image.setImageResource(android.R.color.transparent)
            }

            override fun onFinish() {
                isGestureRecognitionActive = false
                binding.button.visibility = View.VISIBLE
                binding.number.visibility = View.GONE
                lastImageShown = images.random()
                binding.image.setImageResource(lastImageShown)
                judge()
            }
        }
        timer.start()
    }

    // Método para evaluar el resultado del juego
    private fun judge() {
        val playerChoice = binding.category.text.toString().toUpperCase()
        val machineChoice = when (lastImageShown) {
            R.drawable.rock -> "ROCK"
            R.drawable.paper -> "PAPER"
            R.drawable.scissor -> "SCISSORS"
            else -> "UNDEFINED"
        }

        val result = when {
            playerChoice == machineChoice -> "Empate"
            playerChoice == "ROCK" && machineChoice == "SCISSORS" -> "Ganaste"
            playerChoice == "PAPER" && machineChoice == "ROCK" -> "Ganaste"
            playerChoice == "SCISSORS" && machineChoice == "PAPER" -> "Ganaste"
            machineChoice == "UNDEFINED" || playerChoice == "UNDEFINED" -> "Juego no válido"
            else -> "Perdiste"
        }

        // Mostrar el resultado al jugador
        binding.button.text = "$result / Reiniciar Juego"
    }

    // Método que se llama al cambiar la configuración de la actividad
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = binding.viewFinder.display.rotation
    }

    // Método para actualizar la UI después de reconocer un gesto
    override fun onResults(
        resultBundle: GestureRecognizerHelper.ResultBundle
    ) {
        if (isGestureRecognitionActive) {
            runOnUiThread {
                if (binding != null) {
                    // Show result of recognized gesture
                    val gestureCategories = resultBundle.results.first().gestures()
                    if (gestureCategories.isNullOrEmpty() || gestureCategories.first().isNullOrEmpty()) {
                        binding.category.text = "UNDEFINED"
                    } else {
                        val sortedCategories =
                            gestureCategories.first().sortedByDescending { it.score() }
                        val category = sortedCategories.first()
                        binding.category.text = category.categoryName()
                    }
                }
            }
        }
    }

    // Método que se llama al encontrar un error en el reconocimiento
    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            binding.category.text = "Undefined"
        }
    }

}