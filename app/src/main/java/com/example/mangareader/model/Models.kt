package com.example.mangareader.model

import android.graphics.Rect

/**
 * Represents a detected speech bubble in a manga panel
 */
data class SpeechBubble(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float,
    val characterGender: Gender = Gender.UNKNOWN,
    val characterPosition: Rect? = null,
    val readingOrder: Int = 0,
    val emotion: Emotion = Emotion.NEUTRAL
)

/**
 * Emotion classification for dialogue
 */
enum class Emotion {
    HAPPY,      // Cheerful, excited
    SAD,        // Melancholic, crying
    ANGRY,      // Yelling, frustrated
    SURPRISED,  // Shocked, amazed
    SCARED,     // Frightened, worried
    NEUTRAL     // Normal conversation
}

/**
 * Gender classification for characters
 */
enum class Gender {
    MALE,
    FEMALE,
    UNKNOWN
}

/**
 * Represents a manga page with detected elements
 */
data class MangaPage(
    val pageNumber: Int,
    val imagePath: String,
    val speechBubbles: List<SpeechBubble>,
    val isProcessed: Boolean = false
)

/**
 * Character detected in the manga
 */
data class Character(
    val id: String,
    val gender: Gender,
    val faceBox: Rect,
    val confidenceScore: Float,
    val features: CharacterFeatures
)

/**
 * Visual features used for character recognition
 */
data class CharacterFeatures(
    val hairLength: HairLength = HairLength.UNKNOWN,
    val faceWidth: Float = 0f,
    val eyeDistance: Float = 0f,
    val hasBeard: Boolean = false
)

enum class HairLength {
    SHORT,
    MEDIUM,
    LONG,
    UNKNOWN
}
