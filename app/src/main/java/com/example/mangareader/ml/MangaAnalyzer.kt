package com.example.mangareader.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.mangareader.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * ML-based detector for speech bubbles and character gender detection
 */
class MangaAnalyzer {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    /**
     * Analyze a manga page to detect speech bubbles and characters
     */
    suspend fun analyzePage(bitmap: Bitmap, pageNumber: Int): MangaPage {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        // Detect text
        val visionText = textRecognizer.process(inputImage).await()
        
        // Detect faces/characters
        val faces = faceDetector.process(inputImage).await()
        
        // Extract speech bubbles from text blocks
        val speechBubbles = mutableListOf<SpeechBubble>()
        
        visionText.textBlocks.forEachIndexed { index, block ->
            val boundingBox = block.boundingBox ?: return@forEachIndexed
            val text = block.text
            
            // Filter out likely non-dialogue text (sound effects, etc.)
            if (isLikelyDialogue(text)) {
                // Find closest face to determine speaker
                val closestFace = faces.minByOrNull { face ->
                    val faceBox = face.boundingBox
                    calculateDistance(boundingBox, faceBox)
                }
                
                // Determine gender based on face analysis
                val gender = closestFace?.let { face ->
                    analyzeGenderFromFace(face.boundingBox, bitmap, face.smilingProbability ?: 0f)
                } ?: Gender.UNKNOWN
                
                // Detect emotion from text
                val emotion = detectEmotion(text)
                
                speechBubbles.add(
                    SpeechBubble(
                        text = text,
                        boundingBox = boundingBox,
                        confidence = 0.8f,
                        characterGender = gender,
                        characterPosition = closestFace?.boundingBox,
                        readingOrder = index,
                        emotion = emotion
                    )
                )
            }
        }
        
        // Sort by reading order (right to left, top to bottom for manga)
        val sortedBubbles = sortByMangaReadingOrder(speechBubbles)
        
        return MangaPage(
            pageNumber = pageNumber,
            imagePath = "",
            speechBubbles = sortedBubbles,
            isProcessed = true
        )
    }

    /**
     * Determine if text is likely dialogue vs sound effects
     */
    private fun isLikelyDialogue(text: String): Boolean {
        // Filter out very short text (likely sound effects)
        if (text.length < 3) return false
        
        // Filter out all-caps text (often sound effects in manga)
        val upperCaseRatio = text.count { it.isUpperCase() }.toFloat() / text.length
        if (upperCaseRatio > 0.8 && text.length < 10) return false
        
        return true
    }

    /**
     * Analyze gender based on facial features
     * This is a simplified heuristic approach
     */
    private fun analyzeGenderFromFace(faceBox: Rect, bitmap: Bitmap, smilingProb: Float): Gender {
        val faceWidth = faceBox.width()
        val faceHeight = faceBox.height()
        
        // Extract face region
        val faceBitmap = try {
            Bitmap.createBitmap(
                bitmap,
                maxOf(0, faceBox.left),
                maxOf(0, faceBox.top),
                minOf(faceWidth, bitmap.width - faceBox.left),
                minOf(faceHeight, bitmap.height - faceBox.top)
            )
        } catch (e: Exception) {
            return Gender.UNKNOWN
        }
        
        // Analyze features
        val aspectRatio = faceWidth.toFloat() / faceHeight.toFloat()
        val avgBrightness = calculateAverageBrightness(faceBitmap)
        
        // Heuristics for gender detection (simplified)
        // In manga, female characters often have:
        // - More delicate features (narrower face)
        // - Lighter/brighter hair
        // - Larger eyes (reflected in face proportions)
        
        var maleScore = 0
        var femaleScore = 0
        
        // Face width ratio
        if (aspectRatio > 0.75) {
            maleScore += 1
        } else {
            femaleScore += 1
        }
        
        // Brightness (lighter hair often indicates female in manga art style)
        if (avgBrightness > 150) {
            femaleScore += 1
        } else {
            maleScore += 1
        }
        
        // Smiling probability (slight bias, as female characters smile more in typical manga)
        if (smilingProb > 0.5) {
            femaleScore += 1
        }
        
        faceBitmap.recycle()
        
        return when {
            femaleScore > maleScore -> Gender.FEMALE
            maleScore > femaleScore -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    /**
     * Calculate average brightness of a bitmap
     */
    private fun calculateAverageBrightness(bitmap: Bitmap): Int {
        var totalBrightness = 0
        var pixelCount = 0
        
        // Sample every 4th pixel for performance
        for (x in 0 until bitmap.width step 4) {
            for (y in 0 until bitmap.height step 4) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                totalBrightness += (r + g + b) / 3
                pixelCount++
            }
        }
        
        return if (pixelCount > 0) totalBrightness / pixelCount else 128
    }

    /**
     * Calculate distance between two rectangles
     */
    private fun calculateDistance(rect1: Rect, rect2: Rect): Float {
        val centerX1 = rect1.centerX().toFloat()
        val centerY1 = rect1.centerY().toFloat()
        val centerX2 = rect2.centerX().toFloat()
        val centerY2 = rect2.centerY().toFloat()
        
        val dx = centerX1 - centerX2
        val dy = centerY1 - centerY2
        
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Sort speech bubbles by manga reading order (right to left, top to bottom)
     */
    private fun sortByMangaReadingOrder(bubbles: List<SpeechBubble>): List<SpeechBubble> {
        return bubbles.sortedWith(compareBy<SpeechBubble> { 
            // First by vertical position (top to bottom)
            it.boundingBox.top / 100 // Group by approximate rows
        }.thenByDescending { 
            // Then by horizontal position (right to left)
            it.boundingBox.right
        }).mapIndexed { index, bubble ->
            bubble.copy(readingOrder = index)
        }
    }

    /**
     * Detect emotion from text content and punctuation
     */
    /**
     * Detect emotion from text content and punctuation
     */
    private fun detectEmotion(text: String): Emotion {
        val lowerText = text.lowercase()
        val exclamationCount = text.count { it == '!' }
        val questionCount = text.count { it == '?' }
        val capitalRatio = if (text.isNotEmpty()) text.count { it.isUpperCase() }.toFloat() / text.length else 0f
        
        // Happy indicators
        val happyWords = listOf(
            "haha", "hehe", "yay", "great", "awesome", "love", "happy", 
            "wonderful", "amazing", "fantastic", "yes!", "hooray"
        )
        if (happyWords.any { lowerText.contains(it) } || 
            (exclamationCount >= 1 && !lowerText.contains("no") && !lowerText.contains("stop"))) {
            return Emotion.HAPPY
        }
        
        // Angry indicators
        val angryWords = listOf(
            "damn", "hate", "angry", "stop", "shut", "idiot", "stupid",
            "never", "get out", "leave", "grr"
        )
        if (angryWords.any { lowerText.contains(it) } || 
            (capitalRatio > 0.6 && text.length > 5) ||
            exclamationCount >= 2) {
            return Emotion.ANGRY
        }
        
        // Sad indicators
        val sadWords = listOf(
            "cry", "sad", "sorry", "miss", "lost", "gone", "lonely",
            "hurt", "pain", "sigh", "sob", "tears"
        )
        if (sadWords.any { lowerText.contains(it) } || lowerText.contains("...")) {
            return Emotion.SAD
        }
        
        // Surprised indicators
        val surprisedWords = listOf(
            "what", "wow", "oh", "ah", "huh", "really", "seriously",
            "no way", "can't believe", "impossible"
        )
        if (surprisedWords.any { lowerText.contains(it) } || 
            (exclamationCount >= 1 && questionCount >= 1) ||
            questionCount >= 2) {
            return Emotion.SURPRISED
        }
        
        // Scared indicators
        val scaredWords = listOf(
            "help", "no", "please", "don't", "scared", "afraid",
            "run", "hide", "eek", "ahh"
        )
        if (scaredWords.any { lowerText.contains(it) } && exclamationCount >= 1) {
            return Emotion.SCARED
        }
        
        return Emotion.NEUTRAL
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        textRecognizer.close()
        faceDetector.close()
    }
}
