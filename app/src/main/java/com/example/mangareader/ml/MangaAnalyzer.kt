package com.example.mangareader.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.mangareader.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min
import kotlin.math.sqrt

class MangaAnalyzer(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)


    private fun scaleBitmapSafely(bitmap: Bitmap): Bitmap {
        val MAX_DIMENSION = 2048
        if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap
        
        val scale = min(MAX_DIMENSION.toFloat() / bitmap.width, MAX_DIMENSION.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    suspend fun analyzePage(bitmap: Bitmap): List<SpeechBubble> {
        val safeBitmap = scaleBitmapSafely(bitmap)
        val image = InputImage.fromBitmap(safeBitmap, 0)
        
        val textResult = textRecognizer.process(image).await()
        val faces = faceDetector.process(image).await()
        
        val speechBubbles = mutableListOf<SpeechBubble>()
        
        textResult.textBlocks.forEachIndexed { index, block ->
            val boundingBox = block.boundingBox ?: return@forEachIndexed
            val text = block.text
            
            if (isLikelyDialogue(text)) {
                val closestFace = faces.minByOrNull { face ->
                    calculateDistance(boundingBox, face.boundingBox)
                }
                
                val gender = closestFace?.let { face ->
                    analyzeGenderFromFace(face.boundingBox, safeBitmap, face.smilingProbability ?: 0f)
                } ?: Gender.UNKNOWN
                
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
        
        return speechBubbles.sortedBy { it.readingOrder }
    }

    private fun isLikelyDialogue(text: String): Boolean {
        if (text.length < 3) return false
        if (text.matches(Regex("^[0-9]+$"))) return false
        if (text.matches(Regex("^[A-Z]{1,3}$"))) return false
        return text.any { it.isLetter() }
    }

    private fun calculateDistance(box1: Rect, box2: Rect): Float {
        val dx = box1.exactCenterX() - box2.exactCenterX()
        val dy = box1.exactCenterY() - box2.exactCenterY()
        return sqrt(dx * dx + dy * dy)
    }

    private fun analyzeGenderFromFace(faceBox: Rect, bitmap: Bitmap, smilingProbability: Float): Gender {
        val aspectRatio = faceBox.width().toFloat() / faceBox.height()
        return when {
            aspectRatio > 0.85 -> Gender.MALE
            aspectRatio < 0.75 -> Gender.FEMALE
            smilingProbability > 0.7 -> Gender.FEMALE
            else -> Gender.UNKNOWN
        }
    }

    private fun detectEmotion(text: String): Emotion {
        val lowerText = text.lowercase()
        val exclamationCount = text.count { it == '!' }
        val questionCount = text.count { it == '?' }
        val capitalRatio = if (text.isNotEmpty()) text.count { it.isUpperCase() }.toFloat() / text.length else 0f
        
        val happyWords = listOf("haha", "hehe", "yay", "great", "awesome", "love", "happy", "wonderful", "amazing", "fantastic", "hooray", "yippee", "cool", "nice", "perfect", "excellent", "glad")
        if (happyWords.any { lowerText.contains(it) } || (exclamationCount >= 1 && !lowerText.contains("no") && !lowerText.contains("stop"))) return Emotion.HAPPY
        
        val angryWords = listOf("damn", "hate", "angry", "stop", "shut", "idiot", "stupid", "never", "get out", "leave", "grr", "argh", "shut up", "annoying", "furious", "mad")
        if (angryWords.any { lowerText.contains(it) } || (capitalRatio > 0.6 && text.length > 5) || exclamationCount >= 2) return Emotion.ANGRY
        
        val sadWords = listOf("cry", "sad", "sorry", "miss", "lost", "gone", "lonely", "hurt", "pain", "sigh", "sob", "tears", "depressed", "unhappy", "miserable")
        if (sadWords.any { lowerText.contains(it) } || lowerText.contains("...")) return Emotion.SAD
        
        val surprisedWords = listOf("what", "wow", "oh", "ah", "huh", "really", "seriously", "no way", "can't believe", "impossible", "omg", "whoa", "shocking", "unbelievable")
        if (surprisedWords.any { lowerText.contains(it) } || (exclamationCount >= 1 && questionCount >= 1) || questionCount >= 2) return Emotion.SURPRISED
        
        val scaredWords = listOf("help", "no", "please", "don't", "scared", "afraid", "run", "hide", "eek", "ahh", "terrified", "frightened", "panic", "horror", "fear")
        if (scaredWords.any { lowerText.contains(it) } && exclamationCount >= 1) return Emotion.SCARED
        
        return Emotion.NEUTRAL
    }

    fun close() {
        textRecognizer.close()
        faceDetector.close()
    }
}
