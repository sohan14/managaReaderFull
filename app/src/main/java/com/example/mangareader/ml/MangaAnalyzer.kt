package com.example.mangareader.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import com.example.mangareader.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.min
import android.content.Context

/**
 * ML-based detector with LARGE IMAGE CRASH FIX
 */
class MangaAnalyzer(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT)
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    /**
     * CRASH FIX: Scale down large images before processing
     */
    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val MAX_DIMENSION = 3000 // Safe size for most devices
        
        if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) {
            return bitmap // Already safe size
        }
        
        val scale = min(
            MAX_DIMENSION.toFloat() / bitmap.width,
            MAX_DIMENSION.toFloat() / bitmap.height
        )
        
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    /**
     * CRASH FIX: Load and scale image from URI safely
     */
    private fun loadBitmapSafely(uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()
            
            // Calculate sample size
            options.inSampleSize = calculateSampleSize(options, 3000, 3000)
            options.inJustDecodeBounds = false
            
            val inputStream2 = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream2, null, options)
            inputStream2?.close()
            
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Analyze manga page with CRASH PROTECTION
     */
    suspend fun analyzePage(bitmap: Bitmap): List<SpeechBubble> {
        // CRASH FIX: Scale if too large
        val safeBitmap = scaleBitmapIfNeeded(bitmap)
        
        val image = InputImage.fromBitmap(safeBitmap, 0)
        
        // Run OCR
        val textResult = textRecognizer.process(image).await()
        
        // Run face detection
        val faces = faceDetector.process(image).await()
        
        val speechBubbles = mutableListOf<SpeechBubble>()
        
        textResult.textBlocks.forEachIndexed { index, block ->
            val boundingBox = block.boundingBox ?: return@forEachIndexed
            val text = block.text
            
            if (isLikelyDialogue(text)) {
                val closestFace = faces.minByOrNull { face ->
                    val faceBox = face.boundingBox
                    calculateDistance(boundingBox, faceBox)
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

    // ... rest of the file stays the same ...
    private fun isLikelyDialogue(text: String): Boolean {
        if (text.length < 3) return false
        if (text.matches(Regex("^[0-9]+$"))) return false
        if (text.matches(Regex("^[A-Z]{1,3}$"))) return false
        return text.any { it.isLetter() }
    }

    private fun calculateDistance(box1: Rect, box2: Rect): Float {
        val centerX1 = box1.exactCenterX()
        val centerY1 = box1.exactCenterY()
        val centerX2 = box2.exactCenterX()
        val centerY2 = box2.exactCenterY()
        
        val dx = centerX1 - centerX2
        val dy = centerY1 - centerY2
        
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun analyzeGenderFromFace(
        faceBox: Rect,
        bitmap: Bitmap,
        smilingProbability: Float
    ): Gender {
        val faceAspectRatio = faceBox.width().toFloat() / faceBox.height()
        
        return when {
            faceAspectRatio > 0.85 -> Gender.MALE
            faceAspectRatio < 0.75 -> Gender.FEMALE
            smilingProbability > 0.7 -> Gender.FEMALE
            else -> Gender.UNKNOWN
        }
    }

    private fun detectEmotion(text: String): Emotion {
        val lowerText = text.lowercase()
        val exclamationCount = text.count { it == '!' }
        val questionCount = text.count { it == '?' }
        val capitalRatio = if (text.isNotEmpty()) text.count { it.isUpperCase() }.toFloat() / text.length else 0f
        
        val happyWords = listOf(
            "haha", "hehe", "yay", "great", "awesome", "love", "happy", 
            "wonderful", "amazing", "fantastic", "yes!", "hooray"
        )
        if (happyWords.any { lowerText.contains(it) } || 
            (exclamationCount >= 1 && !lowerText.contains("no") && !lowerText.contains("stop"))) {
            return Emotion.HAPPY
        }
        
        val angryWords = listOf(
            "damn", "hate", "angry", "stop", "shut", "idiot", "stupid",
            "never", "get out", "leave", "grr"
        )
        if (angryWords.any { lowerText.contains(it) } || 
            (capitalRatio > 0.6 && text.length > 5) ||
            exclamationCount >= 2) {
            return Emotion.ANGRY
        }
        
        val sadWords = listOf(
            "cry", "sad", "sorry", "miss", "lost", "gone", "lonely",
            "hurt", "pain", "sigh", "sob", "tears"
        )
        if (sadWords.any { lowerText.contains(it) } || lowerText.contains("...")) {
            return Emotion.SAD
        }
        
        val surprisedWords = listOf(
            "what", "wow", "oh", "ah", "huh", "really", "seriously",
            "no way", "can't believe", "impossible"
        )
        if (surprisedWords.any { lowerText.contains(it) } || 
            (exclamationCount >= 1 && questionCount >= 1) ||
            questionCount >= 2) {
            return Emotion.SURPRISED
        }
        
        val scaredWords = listOf(
            "help", "no", "please", "don't", "scared", "afraid",
            "run", "hide", "eek", "ahh"
        )
        if (scaredWords.any { lowerText.contains(it) } && exclamationCount >= 1) {
            return Emotion.SCARED
        }
        
        return Emotion.NEUTRAL
    }

    fun close() {
        textRecognizer.close()
        faceDetector.close()
    }
}
