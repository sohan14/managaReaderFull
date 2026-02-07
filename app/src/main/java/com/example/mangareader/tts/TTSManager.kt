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

/**
 * Text-to-Speech manager with multiple gender-specific voices
 */
class TTSManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentUtteranceId = 0
    
    private var maleVoice: Voice? = null
    private var femaleVoice: Voice? = null
    private var availableVoices: Set<Voice> = emptySet()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f)
                
                // Discover and assign different voices for male/female
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    discoverVoices()
                }
                
                isInitialized = true
            }
        }
    }
    
    /**
     * Discover available TTS voices and assign best options for male/female
     */
    private fun discoverVoices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            availableVoices = tts?.voices ?: emptySet()
            
            Log.d("TTSManager", "Available voices: ${availableVoices.size}")
            
            // Find male voice - prefer names with "male" and lower pitch
            maleVoice = availableVoices.firstOrNull { voice ->
                voice.name.contains("male", ignoreCase = true) && 
                !voice.name.contains("female", ignoreCase = true)
            } ?: availableVoices.firstOrNull { voice ->
                // Fallback: Look for deeper/masculine sounding voice names
                voice.name.contains("deep", ignoreCase = true) ||
                voice.name.contains("bass", ignoreCase = true) ||
                voice.name.contains("en-us-x-", ignoreCase = true) && 
                voice.name.last().isDigit() && voice.name.last().digitToInt() % 2 == 0
            }
            
            // Find female voice - prefer names with "female" or higher pitch indicators
            femaleVoice = availableVoices.firstOrNull { voice ->
                voice.name.contains("female", ignoreCase = true)
            } ?: availableVoices.firstOrNull { voice ->
                // Fallback: Look for softer/feminine sounding voice names
                voice.name.contains("high", ignoreCase = true) ||
                voice.name.contains("soprano", ignoreCase = true) ||
                voice.name.contains("en-us-x-", ignoreCase = true) && 
                voice.name.last().isDigit() && voice.name.last().digitToInt() % 2 == 1
            }
            
            // If we found different voices, log them
            if (maleVoice != null) {
                Log.d("TTSManager", "Male voice: ${maleVoice?.name}")
            }
            if (femaleVoice != null) {
                Log.d("TTSManager", "Female voice: ${femaleVoice?.name}")
            }
            
            // If we didn't find distinct voices, we'll use pitch adjustment instead
            if (maleVoice == null || femaleVoice == null || maleVoice == femaleVoice) {
                Log.d("TTSManager", "Using pitch adjustment fallback (voices not distinct)")
            }
        }
    }

    /**
     * Speak a speech bubble with appropriate voice and emotion
     */
    suspend fun speak(speechBubble: SpeechBubble): Boolean = suspendCancellableCoroutine { continuation ->
        if (!isInitialized) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val utteranceId = "utterance_${currentUtteranceId++}"
        
        // Try to use actual different voices (Android 5.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            when (speechBubble.characterGender) {
                Gender.MALE -> {
                    if (maleVoice != null) {
                        tts?.voice = maleVoice
                    }
                    tts?.setPitch(0.85f)  // Lower for male
                }
                Gender.FEMALE -> {
                    if (femaleVoice != null) {
                        tts?.voice = femaleVoice
                    }
                    tts?.setPitch(1.15f)  // Higher for female
                }
                Gender.UNKNOWN -> {
                    tts?.voice = tts?.defaultVoice
                    tts?.setPitch(1.0f)
                }
            }
        }
        
        // Adjust speech rate based on emotion
        val emotionRate = when (speechBubble.emotion) {
            Emotion.HAPPY -> 1.0f      // Normal to fast
            Emotion.SAD -> 0.75f       // Slower, melancholic
            Emotion.ANGRY -> 1.1f      // Faster, more intense
            Emotion.SURPRISED -> 1.05f // Slightly faster
            Emotion.SCARED -> 1.15f    // Faster, urgent
            Emotion.NEUTRAL -> 0.9f    // Default rate
        }
        tts?.setSpeechRate(emotionRate)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Speech started
            }

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

        // Clean up text for better TTS
        val cleanText = cleanTextForTTS(speechBubble.text)
        
        // Use simple params Bundle
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        
        tts?.speak(cleanText, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        continuation.invokeOnCancellation {
            tts?.stop()
        }
    }
    
    /**
     * Get information about available voices
     */
    fun getVoiceInfo(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            """
            Available voices: ${availableVoices.size}
            Male voice: ${maleVoice?.name ?: "Using pitch adjustment"}
            Female voice: ${femaleVoice?.name ?: "Using pitch adjustment"}
            """.trimIndent()
        } else {
            "Using pitch adjustment (Android 5.0+ required for multiple voices)"
        }
    }

    /**
     * Clean text for better text-to-speech output
     */
    private fun cleanTextForTTS(text: String): String {
        var cleaned = text
            .replace("...", " pause ")  // Handle ellipsis
            .replace("!!", "!")  // Normalize multiple exclamation marks
            .replace("??", "?")  // Normalize multiple question marks
            .replace(Regex("[*_~]"), "")  // Remove emphasis markers
        
        // Handle manga-specific text patterns
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        
        return cleaned
    }

    /**
     * Stop current speech
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Check if TTS is currently speaking
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    /**
     * Set speech rate
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
