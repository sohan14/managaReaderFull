package com.example.mangareader.tts

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.mangareader.model.Emotion
import com.example.mangareader.model.Gender
import com.example.mangareader.model.SpeechBubble
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

class TTSManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentUtteranceId = 0
    
    // MULTIPLE VOICE SUPPORT
    private var selectedMaleVoice: Voice? = null
    private var selectedFemaleVoice: Voice? = null
    private var availableVoices: Set<Voice> = emptySet()
    
    // Lists of available voices by gender
    var maleVoices: List<Voice> = emptyList()
    var femaleVoices: List<Voice> = emptyList()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    discoverAllVoices()
                }
                
                isInitialized = true
            }
        }
    }
    
    /**
     * Discover ALL available TTS voices on device
     */
    private fun discoverAllVoices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            availableVoices = tts?.voices ?: emptySet()
            
            Log.d("TTSManager", "Found ${availableVoices.size} total voices")
            
            // Categorize voices by gender
            val males = mutableListOf<Voice>()
            val females = mutableListOf<Voice>()
            
            availableVoices.forEach { voice ->
                val name = voice.name.lowercase()
                
                // Classify as female if contains female indicators
                val isFemale = name.contains("female") || 
                               name.contains("woman") ||
                               name.contains("girl") ||
                               (name.contains("en-us") && name.last().isDigit() && name.last().digitToInt() % 2 == 1)
                
                // Classify as male if contains male indicators
                val isMale = name.contains("male") && !name.contains("female") ||
                            name.contains("man") && !name.contains("woman") ||
                            name.contains("boy") ||
                            (name.contains("en-us") && name.last().isDigit() && name.last().digitToInt() % 2 == 0)
                
                when {
                    isFemale -> females.add(voice)
                    isMale -> males.add(voice)
                    else -> {
                        // Try to guess from voice features
                        // Higher quality voices are usually better
                        if (voice.quality >= Voice.QUALITY_HIGH) {
                            females.add(voice) // Default unclear voices to female for user preference
                        }
                    }
                }
            }
            
            maleVoices = males
            femaleVoices = females
            
            // Auto-select best voices
            selectedMaleVoice = males.firstOrNull()
            selectedFemaleVoice = females.firstOrNull()
            
            Log.d("TTSManager", "Found ${femaleVoices.size} female voices, ${maleVoices.size} male voices")
        }
    }
    
    /**
     * Set selected voice for gender
     */
    fun setVoiceFor(gender: Gender, voice: Voice) {
        when (gender) {
            Gender.MALE -> selectedMaleVoice = voice
            Gender.FEMALE -> selectedFemaleVoice = voice
            else -> {}
        }
    }
    
    /**
     * Get voice name for display
     */
    fun getVoiceName(voice: Voice): String {
        return voice.name.replace("en-us-x-", "Voice ")
                         .replace("en_US", "US English")
                         .replace("_", " ")
                         .replaceFirstChar { it.uppercase() }
    }

    /**
     * Speak with IMPROVED EMOTION VARIATION
     */
    suspend fun speak(speechBubble: SpeechBubble): Boolean = suspendCancellableCoroutine { continuation ->
        if (!isInitialized) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val utteranceId = "utterance_${currentUtteranceId++}"
        
        // Select voice based on gender
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val targetVoice = when (speechBubble.characterGender) {
                Gender.MALE -> selectedMaleVoice
                Gender.FEMALE -> selectedFemaleVoice
                Gender.UNKNOWN -> tts?.defaultVoice
            }
            
            if (targetVoice != null) {
                tts?.voice = targetVoice
            }
        }
        
        // IMPROVED EMOTION MODULATION
        val (pitch, speed) = getEmotionModulation(speechBubble.emotion, speechBubble.characterGender)
        
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // Reset to defaults
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                continuation.resume(true)
            }

            override fun onError(utteranceId: String?) {
                continuation.resume(false)
            }
        })

        // Clean and enhance text for better speech
        val enhancedText = enhanceTextForSpeech(speechBubble.text, speechBubble.emotion)
        
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        
        tts?.speak(enhancedText, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        continuation.invokeOnCancellation {
            tts?.stop()
        }
    }
    
    /**
     * IMPROVED emotion-based pitch and speed
     */
    private fun getEmotionModulation(emotion: Emotion, gender: Gender): Pair<Float, Float> {
        // Base pitch varies by gender
        val basePitch = when (gender) {
            Gender.MALE -> 0.9f
            Gender.FEMALE -> 1.1f
            Gender.UNKNOWN -> 1.0f
        }
        
        // Emotion modifies both pitch and speed MORE dramatically
        return when (emotion) {
            Emotion.HAPPY -> Pair(basePitch + 0.3f, 1.15f)      // Higher, faster, cheerful
            Emotion.SAD -> Pair(basePitch - 0.25f, 0.7f)        // Lower, slower, melancholic
            Emotion.ANGRY -> Pair(basePitch + 0.1f, 1.25f)      // Slightly higher, fast, intense
            Emotion.SURPRISED -> Pair(basePitch + 0.35f, 1.2f)  // Much higher, fast, shocked
            Emotion.SCARED -> Pair(basePitch + 0.4f, 1.3f)      // Highest, fastest, panicked
            Emotion.NEUTRAL -> Pair(basePitch, 0.95f)           // Normal pace
        }
    }
    
    /**
     * Enhance text with pauses and emphasis
     */
    private fun enhanceTextForSpeech(text: String, emotion: Emotion): String {
        var enhanced = text
        
        // Add pauses for ellipsis
        enhanced = enhanced.replace("...", " ... ")
        
        // Add emphasis for multiple exclamations
        if (text.count { it == '!' } >= 2) {
            enhanced = enhanced.replace("!!", "!  ")
        }
        
        // Add pause before questions for better inflection
        if (text.contains("?")) {
            enhanced = enhanced.replace("?", " ?")
        }
        
        // For scared emotion, add slight stutter effect
        if (emotion == Emotion.SCARED && enhanced.length > 10) {
            val words = enhanced.split(" ")
            if (words.isNotEmpty()) {
                val firstWord = words[0]
                enhanced = "$firstWord... $enhanced"
            }
        }
        
        return enhanced.trim()
    }

    /**
     * Get info about available voices
     */
    fun getVoiceInfo(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            """
            Total voices: ${availableVoices.size}
            Female voices: ${femaleVoices.size}
            Male voices: ${maleVoices.size}
            
            Selected female: ${selectedFemaleVoice?.let { getVoiceName(it) } ?: "Default"}
            Selected male: ${selectedMaleVoice?.let { getVoiceName(it) } ?: "Default"}
            """.trimIndent()
        } else {
            "Voice selection requires Android 5.0+"
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
