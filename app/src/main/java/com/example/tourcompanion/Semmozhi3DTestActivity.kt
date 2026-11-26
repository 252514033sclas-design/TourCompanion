package com.example.tourcompanion

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Isolated standalone test screen for validating 3D character rendering,
 * skeletal animations, morph targets (blend shapes), and real-time TTS lip-sync.
 *
 * Does not modify or replace existing 2D character (SaathiView) screens.
 */
class Semmozhi3DTestActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var sceneView: SceneView
    private lateinit var textModelStatusOverlay: TextView
    private lateinit var textWeightLabel: TextView
    private lateinit var seekMorphWeight: SeekBar
    private lateinit var textSpokenWord: TextView

    private lateinit var btnLoadHumanoid: Button
    private lateinit var btnLoadMorphStress: Button
    private lateinit var btnLoadMorphCube: Button

    private lateinit var btnVisemeRest: Button
    private lateinit var btnVisemeA: Button
    private lateinit var btnVisemeE: Button
    private lateinit var btnVisemeO: Button
    private lateinit var btnVisemeM: Button

    private lateinit var btnPlayAnimation: Button
    private lateinit var btnStopAnimation: Button
    private lateinit var btnSpeakTest: Button

    private var currentModelNode: ModelNode? = null
    private var activeModelPath: String = "models/cesium_man.glb"
    private var morphWeight: Float = 1.0f

    // Isolated TTS engine for testing speech-range viseme callbacks
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_3d_test)
        EdgeToEdge.apply(this)

        sceneView = findViewById(R.id.sceneView3D)
        textModelStatusOverlay = findViewById(R.id.textModelStatusOverlay)
        textWeightLabel = findViewById(R.id.textWeightLabel)
        seekMorphWeight = findViewById(R.id.seekMorphWeight)
        textSpokenWord = findViewById(R.id.textSpokenWord)

        btnLoadHumanoid = findViewById(R.id.btnLoadHumanoid)
        btnLoadMorphStress = findViewById(R.id.btnLoadMorphStress)
        btnLoadMorphCube = findViewById(R.id.btnLoadMorphCube)

        btnVisemeRest = findViewById(R.id.btnVisemeRest)
        btnVisemeA = findViewById(R.id.btnVisemeA)
        btnVisemeE = findViewById(R.id.btnVisemeE)
        btnVisemeO = findViewById(R.id.btnVisemeO)
        btnVisemeM = findViewById(R.id.btnVisemeM)

        btnPlayAnimation = findViewById(R.id.btnPlayAnimation)
        btnStopAnimation = findViewById(R.id.btnStopAnimation)
        btnSpeakTest = findViewById(R.id.btnSpeakTest)

        findViewById<Button>(R.id.btnBack3D).setOnClickListener { finish() }

        // Setup morph weight slider
        seekMorphWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                morphWeight = progress / 100.0f
                textWeightLabel.text = "Weight: ${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Model loading buttons
        btnLoadHumanoid.setOnClickListener { load3DModel("models/cesium_man.glb", 1.8f) }
        btnLoadMorphStress.setOnClickListener { load3DModel("models/morph_stress.glb", 2.2f) }
        btnLoadMorphCube.setOnClickListener { load3DModel("models/morph_cube.glb", 2.0f) }

        // Viseme manual buttons
        btnVisemeRest.setOnClickListener { applyViseme(VisemeMapper.Viseme.REST) }
        btnVisemeA.setOnClickListener { applyViseme(VisemeMapper.Viseme.A) }
        btnVisemeE.setOnClickListener { applyViseme(VisemeMapper.Viseme.E) }
        btnVisemeO.setOnClickListener { applyViseme(VisemeMapper.Viseme.O) }
        btnVisemeM.setOnClickListener { applyViseme(VisemeMapper.Viseme.M) }

        // Animation playback controls
        btnPlayAnimation.setOnClickListener {
            currentModelNode?.let { node ->
                if (node.animationCount > 0) {
                    node.playAnimation(0, loop = true)
                    textModelStatusOverlay.text = "Playing Animation #0 (Looping)"
                } else {
                    Toast.makeText(this, "Current model has no animation tracks", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStopAnimation.setOnClickListener {
            currentModelNode?.let { node ->
                if (node.animationCount > 0) {
                    node.stopAnimation(0)
                    textModelStatusOverlay.text = "Animation stopped"
                }
            }
        }

        // Live speech-driven lip-sync test
        btnSpeakTest.setOnClickListener { startLipSyncTestSpeech() }

        // Initialize local TTS
        tts = TextToSpeech(applicationContext, this)

        // Load initial model (Humanoid Character)
        load3DModel(activeModelPath, 1.8f)
    }

    private fun load3DModel(assetPath: String, scaleUnits: Float) {
        activeModelPath = assetPath
        textModelStatusOverlay.text = "Loading: $assetPath…"

        // Clean up previous node
        currentModelNode?.let { sceneView.removeChildNode(it) }
        currentModelNode = null

        sceneView.modelLoader.loadModelInstanceAsync(assetPath) { instance ->
            runOnUiThread {
                if (instance != null) {
                    val node = ModelNode(instance).apply {
                        scaleToUnitCube(scaleUnits)
                        centerOrigin(Float3(0f, 0f, 0f))
                    }

                    sceneView.addChildNode(node)
                    currentModelNode = node

                    // Discover morph targets
                    val morphTargets = mutableListOf<String>()
                    val asset = node.model
                    for (entity in asset.renderableEntities) {
                        val names = asset.getMorphTargetNames(entity)
                        if (names != null) {
                            morphTargets.addAll(names)
                        }
                    }

                    val animCount = node.animationCount
                    textModelStatusOverlay.text = "$assetPath\nAnimations: $animCount | Morph Targets: ${morphTargets.size} (${morphTargets.take(4).joinToString()})"

                    // Auto-play animation if available
                    if (animCount > 0) {
                        node.playAnimation(0, loop = true)
                    }

                    Log.d(TAG, "Successfully loaded $assetPath. Morph targets: $morphTargets")
                } else {
                    textModelStatusOverlay.text = "Failed to load $assetPath"
                }
            }
        }
    }

    /**
     * Applies a viseme shape to the loaded 3D model using blend weights.
     */
    private fun applyViseme(viseme: VisemeMapper.Viseme) {
        val node = currentModelNode ?: return
        val weight = morphWeight

        // Map Viseme to morph target indices
        // Viseme.REST -> all 0.0
        // Viseme.A (Open) -> Key 1 / index 0
        // Viseme.E (Spread) -> Key 2 / index 1
        // Viseme.O (Round) -> Key 3 / index 2
        // Viseme.M (Closed) -> Key 4 / index 3
        val weights = when (viseme) {
            VisemeMapper.Viseme.REST -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            VisemeMapper.Viseme.A -> floatArrayOf(weight, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            VisemeMapper.Viseme.E -> floatArrayOf(0f, weight, 0f, 0f, 0f, 0f, 0f, 0f)
            VisemeMapper.Viseme.O -> floatArrayOf(0f, 0f, weight, 0f, 0f, 0f, 0f, 0f)
            VisemeMapper.Viseme.M -> floatArrayOf(0f, 0f, 0f, weight, 0f, 0f, 0f, 0f)
        }

        try {
            node.setMorphWeights(weights)
            textModelStatusOverlay.text = "$activeModelPath\nActive Viseme: $viseme (${(weight * 100).toInt()}%)"
        } catch (e: Exception) {
            Log.w(TAG, "setMorphWeights error: ${e.message}")
        }
    }

    private fun startLipSyncTestSpeech() {
        if (!ttsReady || tts == null) {
            Toast.makeText(this, "TextToSpeech engine not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val testSentence = "Hello! I am Semmozhi, your virtual 3D travel companion for exploring Chennai."
        textSpokenWord.text = "Speaking: \"$testSentence\""

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    textSpokenWord.text = "Speech started: Listening for visemes…"
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (start in 0..testSentence.length && end in start..testSentence.length) {
                    val word = testSentence.substring(start, end)
                    val viseme = VisemeMapper.map(word, testSentence, start, end)

                    mainHandler.post {
                        textSpokenWord.text = "Word: \"$word\" → Viseme: $viseme"
                        applyViseme(viseme)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    textSpokenWord.text = "Finished speech. Returned mouth to REST."
                    applyViseme(VisemeMapper.Viseme.REST)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    textSpokenWord.text = "Speech error occurred."
                    applyViseme(VisemeMapper.Viseme.REST)
                }
            }
        })

        tts?.speak(testSentence, TextToSpeech.QUEUE_FLUSH, null, "test_3d_speech")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
            ttsReady = true
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Semmozhi3DTest"
    }
}
