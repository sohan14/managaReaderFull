package com.example.mangareader.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.mangareader.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.abs

class MangaAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "MangaAnalyzer"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    /**
     * Scale bitmap safely for webtoon images
     */
    private fun scaleBitmapSafely(bitmap: Bitmap): Bitmap {
        val MAX_DIMENSION = 2048
        if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap
        
        val scale = min(MAX_DIMENSION.toFloat() / bitmap.width, MAX_DIMENSION.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Analyze manga page for speech bubbles - IMPROVED FILTERING with LOGGING
     */
    suspend fun analyzePage(bitmap: Bitmap): List<SpeechBubble> {
        val safeBitmap = scaleBitmapSafely(bitmap)
        val image = InputImage.fromBitmap(safeBitmap, 0)
        
        Log.d(TAG, "=== OCR Analysis Start ===")
        Log.d(TAG, "Image size: ${safeBitmap.width} x ${safeBitmap.height} = ${safeBitmap.width * safeBitmap.height} pixels")
        
        val textResult = textRecognizer.process(image).await()
        val faces = faceDetector.process(image).await()
        
        Log.d(TAG, "OCR found ${textResult.textBlocks.size} text blocks")
        Log.d(TAG, "Face detection found ${faces.size} faces")
        
        // First pass: collect all text blocks
        val rawBlocks = mutableListOf<Pair<Rect, String>>()
        textResult.textBlocks.forEachIndexed { index, block ->
            val boundingBox = block.boundingBox ?: return@forEachIndexed
            val text = block.text
            rawBlocks.add(Pair(boundingBox, text))
            Log.d(TAG, "Block $index: '${text.take(20)}...' size=${boundingBox.width()}x${boundingBox.height()} area=${boundingBox.width() * boundingBox.height()}")
        }
        
        // Filter and merge blocks to form speech bubbles
        val speechBubbles = filterAndMergeBubbles(rawBlocks, safeBitmap, faces)
        
        Log.d(TAG, "Final result: ${speechBubbles.size} speech bubbles detected")
        speechBubbles.forEachIndexed { index, bubble ->
            Log.d(TAG, "Bubble $index: '${bubble.text.take(30)}...' emotion=${bubble.emotion} gender=${bubble.characterGender}")
        }
        Log.d(TAG, "=== OCR Analysis End ===")
        
        return speechBubbles.sortedBy { it.readingOrder }
    }

    /**
     * IMPROVED: Filter out non-bubble text and merge lines in same bubble - WITH LOGGING
     */
    private fun filterAndMergeBubbles(
        blocks: List<Pair<Rect, String>>,
        bitmap: Bitmap,
        faces: List<com.google.mlkit.vision.face.Face>
    ): List<SpeechBubble> {
        
        val bubbles = mutableListOf<SpeechBubble>()
        val imageArea = bitmap.width * bitmap.height
        
        Log.d(TAG, "--- Filtering ${blocks.size} text blocks ---")
        Log.d(TAG, "Image area: $imageArea pixels")
        
        // Filter blocks by characteristics of speech bubbles
        val filteredBlocks = blocks.filterIndexed { index, (box, text) ->
            val area = box.width() * box.height()
            val areaRatio = area.toFloat() / imageArea
            
            Log.d(TAG, "Block $index: text='${text.take(15)}...'")
            Log.d(TAG, "  Size: ${box.width()}x${box.height()} = $area pixels")
            Log.d(TAG, "  Area ratio: ${String.format("%.4f", areaRatio * 100)}%")
            
            // FILTER 1: Must be dialogue (has letters)
            if (!isLikelyDialogue(text)) {
                Log.d(TAG, "  ❌ REJECTED: Not dialogue (no letters)")
                return@filterIndexed false
            }
            Log.d(TAG, "  ✅ Filter 1: Is dialogue")
            
            // FILTER 2: Must be big enough
            // LOWERED for webtoons! Speech bubbles in tall images are smaller %
            // Min 0.01% = about 2000 pixels (45x45) for scaled image
            if (areaRatio < 0.0001f) {
                Log.d(TAG, "  ❌ REJECTED: Too small (${String.format("%.4f", areaRatio * 100)}% < 0.01%)")
                return@filterIndexed false
            }
            Log.d(TAG, "  ✅ Filter 2: Big enough")
            
            // FILTER 3: Aspect ratio check (speech bubbles are usually wider than tall)
            val aspectRatio = box.width().toFloat() / box.height()
            Log.d(TAG, "  Aspect ratio: ${String.format("%.2f", aspectRatio)}")
            if (aspectRatio > 10 || aspectRatio < 0.3) {
                Log.d(TAG, "  ❌ REJECTED: Bad aspect ratio (${String.format("%.2f", aspectRatio)})")
                return@filterIndexed false
            }
            Log.d(TAG, "  ✅ Filter 3: Good aspect ratio")
            
            // FILTER 4: Position check (speech bubbles usually in upper 2/3 of image for webtoons)
            // Allow all for now, but can filter if needed
            
            Log.d(TAG, "  ✅✅✅ ACCEPTED as speech bubble!")
            true
        }
        
        Log.d(TAG, "After filtering: ${filteredBlocks.size} blocks passed")
        
        // Group nearby blocks into single bubbles (for multi-line bubbles)
        val grouped = groupNearbyBlocks(filteredBlocks)
        
        Log.d(TAG, "After grouping: ${grouped.size} bubble groups")
        
        // Convert groups to SpeechBubbles
        grouped.forEachIndexed { index, group ->
            val (mergedBox, mergedText) = mergeGroup(group)
            
            val closestFace = faces.minByOrNull { face ->
                calculateDistance(mergedBox, face.boundingBox)
            }
            
            val gender = closestFace?.let { face ->
                analyzeGenderFromFace(face.boundingBox, bitmap, face.smilingProbability ?: 0f)
            } ?: Gender.UNKNOWN
            
            val emotion = detectEmotion(mergedText)
            
            Log.d(TAG, "Bubble group $index: '${mergedText.take(20)}...' gender=$gender emotion=$emotion")
            
            bubbles.add(
                SpeechBubble(
                    text = mergedText,
                    boundingBox = mergedBox,
                    confidence = 0.8f,
                    characterGender = gender,
                    characterPosition = closestFace?.boundingBox,
                    readingOrder = index,
                    emotion = emotion
                )
            )
        }
        
        return bubbles
    }

    /**
     * Group nearby text blocks that are likely part of same bubble
     */
    private fun groupNearbyBlocks(blocks: List<Pair<Rect, String>>): List<List<Pair<Rect, String>>> {
        if (blocks.isEmpty()) return emptyList()
        
        val groups = mutableListOf<MutableList<Pair<Rect, String>>>()
        val visited = BooleanArray(blocks.size)
        
        blocks.forEachIndexed { i, block ->
            if (visited[i]) return@forEachIndexed
            
            val group = mutableListOf(block)
            visited[i] = true
            
            // Find all nearby blocks
            blocks.forEachIndexed { j, other ->
                if (!visited[j] && areBlocksNearby(block.first, other.first)) {
                    group.add(other)
                    visited[j] = true
                }
            }
            
            groups.add(group)
        }
        
        return groups
    }

    /**
     * Check if two blocks are close enough to be same bubble
     */
    private fun areBlocksNearby(box1: Rect, box2: Rect): Boolean {
        val maxDistance = (box1.height() + box2.height()) / 2f
        val distance = calculateDistance(box1, box2)
        return distance < maxDistance
    }

    /**
     * Merge group of blocks into single text and bounding box
     */
    private fun mergeGroup(group: List<Pair<Rect, String>>): Pair<Rect, String> {
        // Sort by vertical position (top to bottom)
        val sorted = group.sortedBy { it.first.top }
        
        // Merge bounding boxes
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        
        sorted.forEach { (box, _) ->
            left = min(left, box.left)
            top = min(top, box.top)
            right = kotlin.math.max(right, box.right)
            bottom = kotlin.math.max(bottom, box.bottom)
        }
        
        val mergedBox = Rect(left, top, right, bottom)
        
        // Merge text with single space (no line breaks!)
        val mergedText = sorted.joinToString(" ") { it.second }
        
        return Pair(mergedBox, mergedText)
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
