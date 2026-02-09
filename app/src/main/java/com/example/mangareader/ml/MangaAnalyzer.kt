package com.example.mangareader.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.mangareader.model.*
import com.example.mangareader.utils.DebugLogger
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

    // Use Latin text recognizer for English content (faster and more accurate for English)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)
    
    init {
        DebugLogger.init(context)
    }

    /**
     * Scale bitmap safely for webtoon images
     */
    private fun scaleBitmapSafely(bitmap: Bitmap): Bitmap {
        // CRITICAL: Device has 268MB limit (from crash logs)
        // We need to stay WELL BELOW that for ML Kit's internal buffers
        // Target: ~180MB (~45M pixels) to leave 88MB headroom
        
        val MIN_WIDTH_FOR_OCR = 1600  // Balanced for Korean while staying safe
        val MAX_PIXELS = 45_000_000   // ~180MB (45M * 4 bytes) - safe under 268MB limit
        
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width.toLong() * height.toLong()
        
        DebugLogger.log(TAG, "Original image: ${width} x ${height}")
        DebugLogger.log(TAG, "Total pixels: ${totalPixels} (${totalPixels * 4 / 1_000_000}MB)")
        DebugLogger.log(TAG, "Device memory limit: 268MB (from crash logs)")
        DebugLogger.log(TAG, "Target: 180MB (~45M pixels) to leave headroom for ML Kit")
        
        // Check if image fits in memory
        if (totalPixels <= MAX_PIXELS) {
            // Fits in memory!
            if (width >= MIN_WIDTH_FOR_OCR) {
                // Perfect size - keep it!
                DebugLogger.log(TAG, "Image size OK for Korean OCR, no scaling needed")
                return bitmap
            } else {
                // Too narrow but small enough to scale up
                val scale = MIN_WIDTH_FOR_OCR.toFloat() / width
                val newWidth = MIN_WIDTH_FOR_OCR
                val newHeight = (height * scale).toInt()
                
                DebugLogger.log(TAG, "Image too narrow for Korean OCR, scaling UP to min width")
                DebugLogger.log(TAG, "Scaled image: ${newWidth} x ${newHeight}")
                
                return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            }
        }
        
        // Image too large - must scale down
        // Calculate scale to fit in MAX_PIXELS while keeping width as large as possible
        val scale = kotlin.math.sqrt(MAX_PIXELS.toFloat() / totalPixels)
        var newWidth = (width * scale).toInt()
        var newHeight = (height * scale).toInt()
        
        // Ensure width doesn't drop below minimum
        if (newWidth < MIN_WIDTH_FOR_OCR) {
            // Force minimum width for Korean OCR
            val widthScale = MIN_WIDTH_FOR_OCR.toFloat() / width
            newWidth = MIN_WIDTH_FOR_OCR
            newHeight = (height * widthScale).toInt()
            
            val newPixels = newWidth.toLong() * newHeight.toLong()
            val newMemoryMB = newPixels * 4 / 1_000_000
            
            DebugLogger.log(TAG, "Image too large, scaling DOWN but keeping min width for Korean OCR")
            DebugLogger.log(TAG, "Scaled image: ${newWidth} x ${newHeight}")
            DebugLogger.log(TAG, "New pixels: ${newPixels} (~${newMemoryMB}MB)")
            
            if (newMemoryMB > 230) {
                // Getting close to limit - warn but continue
                DebugLogger.log(TAG, "WARNING: Memory usage ${newMemoryMB}MB is close to 268MB limit")
                DebugLogger.log(TAG, "May crash on some devices. Proceeding anyway for Korean OCR quality.")
            } else {
                DebugLogger.log(TAG, "Memory usage ${newMemoryMB}MB is safe (${268 - newMemoryMB}MB headroom)")
            }
        } else {
            val newMemoryMB = newWidth.toLong() * newHeight.toLong() * 4 / 1_000_000
            DebugLogger.log(TAG, "Image too large, scaling DOWN to fit safely in memory")
            DebugLogger.log(TAG, "Scaled image: ${newWidth} x ${newHeight}")
            DebugLogger.log(TAG, "New pixels: ${newWidth.toLong() * newHeight.toLong()} (~${newMemoryMB}MB)")
            DebugLogger.log(TAG, "Memory headroom: ${268 - newMemoryMB}MB")
        }
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Split very tall images (webtoons) into manageable chunks for better OCR
     */
    /**
     * Detect panel boundaries in webtoon by finding horizontal white/black separators
     * Returns Y positions where panels start/end
     */
    private fun detectPanelBoundaries(bitmap: Bitmap): List<Int> {
        val boundaries = mutableListOf<Int>()
        boundaries.add(0) // Start
        
        DebugLogger.log(TAG, "Detecting panel boundaries by scanning for white space...")
        
        // Sample middle column to detect horizontal separators
        val sampleX = bitmap.width / 2
        val threshold = 200 // Brightness threshold for "white"
        
        var inWhiteSpace = false
        var whiteSpaceStart = 0
        val minWhiteSpaceHeight = 20 // Minimum pixels for a separator
        
        for (y in 0 until bitmap.height step 5) { // Sample every 5 pixels
            val pixel = bitmap.getPixel(sampleX, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = (r + g + b) / 3
            
            val isWhite = brightness > threshold
            
            if (isWhite && !inWhiteSpace) {
                // Start of white space
                whiteSpaceStart = y
                inWhiteSpace = true
            } else if (!isWhite && inWhiteSpace) {
                // End of white space
                val whiteSpaceHeight = y - whiteSpaceStart
                if (whiteSpaceHeight >= minWhiteSpaceHeight) {
                    // This is a panel boundary!
                    val boundaryY = whiteSpaceStart + (whiteSpaceHeight / 2)
                    boundaries.add(boundaryY)
                    DebugLogger.log(TAG, "  Panel boundary at Y=$boundaryY")
                }
                inWhiteSpace = false
            }
        }
        
        boundaries.add(bitmap.height) // End
        DebugLogger.log(TAG, "Found ${boundaries.size - 1} panels")
        
        return boundaries
    }
    
    /**
     * Info about a chunk: the bitmap and where it came from in the original image
     */
    private data class ChunkInfo(
        val bitmap: Bitmap,
        val originalYOffset: Int  // Y position in original full image
    )
    
    /**
     * Split webtoon at panel boundaries for perfect story continuity
     * Each chunk contains complete panels (no mid-panel cuts)
     */
    private fun splitIntoChunks(bitmap: Bitmap, screenHeight: Int): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        
        // If image is not super tall, return as-is
        val aspectRatio = bitmap.height.toFloat() / bitmap.width
        if (aspectRatio <= 2.0f) {
            // Normal image - process whole thing
            DebugLogger.log(TAG, "Image aspect ratio ${String.format("%.1f", aspectRatio)} - processing as single image")
            chunks.add(ChunkInfo(bitmap, 0))
            return chunks
        }
        
        // Very tall webtoon - detect panel boundaries!
        DebugLogger.log(TAG, "Very tall webtoon detected (aspect ${String.format("%.1f", aspectRatio)})")
        DebugLogger.log(TAG, "Detecting panel boundaries for natural chunking...")
        
        val panelBoundaries = detectPanelBoundaries(bitmap)
        
        // Filter out tiny panels (white space separators)
        // Keep only REAL panels (content)
        DebugLogger.log(TAG, "Filtering panels - keeping only real content panels...")
        
        val minPanelHeight = 400 // Minimum height for a real panel (skip white space)
        
        for (i in 0 until panelBoundaries.size - 1) {
            val panelStart = panelBoundaries[i]
            val panelEnd = panelBoundaries[i + 1]
            val panelHeight = panelEnd - panelStart
            
            // Only keep panels that are real content (not tiny white space)
            if (panelHeight >= minPanelHeight) {
                val chunkBitmap = Bitmap.createBitmap(bitmap, 0, panelStart, bitmap.width, panelHeight)
                chunks.add(ChunkInfo(chunkBitmap, panelStart))
                DebugLogger.log(TAG, "Panel ${chunks.size}: Y=$panelStart to $panelEnd (${panelHeight}px)")
            } else {
                DebugLogger.log(TAG, "Skipping tiny panel at Y=$panelStart (only ${panelHeight}px - white space)")
            }
        }
        
        DebugLogger.log(TAG, "Created ${chunks.size} pages (one REAL panel per page)")
        DebugLogger.log(TAG, "Each panel FILLS entire screen - no peeking next panel!")
        
        return chunks
    }

    /**
     * Result of analyzing a page - may contain multiple pages if image was chunked
     */
    data class AnalysisResult(
        val pages: List<PageData>
    )
    
    data class PageData(
        val bitmap: Bitmap,
        val speechBubbles: List<SpeechBubble>,
        val originalYOffset: Int = 0  // Y position where this chunk started in the original full image
    )

    /**
     * Analyze manga page for speech bubbles - Returns multiple pages for tall webtoons
     * @param bitmap The image to analyze
     * @param screenHeight Screen height in pixels for optimal chunking (0 if unknown)
     */
    suspend fun analyzePage(bitmap: Bitmap, screenHeight: Int = 0): AnalysisResult {
        DebugLogger.log(TAG, "=== OCR Analysis Start ===")
        DebugLogger.log(TAG, "Original image: ${bitmap.width} x ${bitmap.height}")
        
        // Split tall webtoons into chunks at panel boundaries
        val chunks = splitIntoChunks(bitmap, screenHeight)
        val pages = mutableListOf<PageData>()
        
        chunks.forEachIndexed { chunkIndex, chunkInfo ->
            DebugLogger.log(TAG, "--- Processing chunk ${chunkIndex + 1}/${chunks.size} ---")
            
            val safeBitmap = scaleBitmapSafely(chunkInfo.bitmap)
            val image = InputImage.fromBitmap(safeBitmap, 0)
            
            DebugLogger.log(TAG, "Chunk ${chunkIndex + 1} image size: ${safeBitmap.width} x ${safeBitmap.height} = ${safeBitmap.width * safeBitmap.height} pixels")
            
            // Process text recognition with error handling
            val textResult = try {
                DebugLogger.log(TAG, "Starting Latin text recognition...")
                val result = textRecognizer.process(image).await()
                DebugLogger.log(TAG, "Text recognition completed successfully")
                result
            } catch (e: Exception) {
                DebugLogger.log(TAG, "ERROR in text recognition: ${e.message}")
                DebugLogger.log(TAG, "Error type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                throw e
            }
            
            // Process face detection with error handling
            val faces = try {
                DebugLogger.log(TAG, "Starting face detection...")
                val result = faceDetector.process(image).await()
                DebugLogger.log(TAG, "Face detection completed successfully")
                result
            } catch (e: Exception) {
                DebugLogger.log(TAG, "ERROR in face detection: ${e.message}")
                DebugLogger.log(TAG, "Error type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                emptyList()
            }
            
            DebugLogger.log(TAG, "Chunk ${chunkIndex + 1}: OCR found ${textResult.textBlocks.size} text blocks")
            DebugLogger.log(TAG, "Chunk ${chunkIndex + 1}: Face detection found ${faces.size} faces")
            
            // Collect all text blocks
            val rawBlocks = mutableListOf<Pair<Rect, String>>()
            textResult.textBlocks.forEachIndexed { index, block ->
                val boundingBox = block.boundingBox ?: return@forEachIndexed
                val text = block.text
                rawBlocks.add(Pair(boundingBox, text))
                DebugLogger.log(TAG, "  Block $index: '${text.take(30)}...' size=${boundingBox.width()}x${boundingBox.height()}")
            }
            
            // Filter and merge blocks for this chunk
            var chunkBubbles = filterAndMergeBubbles(rawBlocks, safeBitmap, faces)
            
            // CRITICAL FIX: Sort bubbles by Y position (top to bottom reading order)
            chunkBubbles = chunkBubbles.sortedBy { it.boundingBox.top }
            
            DebugLogger.log(TAG, "Chunk ${chunkIndex + 1}: ${chunkBubbles.size} speech bubbles detected")
            chunkBubbles.forEachIndexed { idx, bubble ->
                DebugLogger.log(TAG, "  Bubble $idx (Y=${bubble.boundingBox.top}): '${bubble.text.take(30)}...'")
            }
            
            // Create a page for this chunk WITH original Y offset
            pages.add(PageData(chunkInfo.bitmap, chunkBubbles, chunkInfo.originalYOffset))
        }
        
        DebugLogger.log(TAG, "=== OCR Analysis Complete ===")
        DebugLogger.log(TAG, "Created ${pages.size} pages from image")
        val totalBubbles = pages.sumOf { it.speechBubbles.size }
        DebugLogger.log(TAG, "Total bubbles across all pages: $totalBubbles")
        
        return AnalysisResult(pages)
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
        
        // ADAPTIVE FILTER: Different thresholds for webtoons vs normal manga
        val aspectRatio = bitmap.height.toFloat() / bitmap.width
        val isWebtoon = aspectRatio > 3.0f  // Height > 3x width = webtoon
        
        // For webtoons: lower threshold (text is smaller % of total image)
        // For normal manga: higher threshold (filters out clothing text better)
        val MIN_AREA_RATIO = if (isWebtoon) {
            0.0001f  // 0.01% for webtoons (very permissive)
        } else {
            0.003f   // 0.3% for normal manga (filters clothing text)
        }
        
        DebugLogger.log(TAG, "--- Filtering ${blocks.size} text blocks ---")
        DebugLogger.log(TAG, "Image area: $imageArea pixels")
        DebugLogger.log(TAG, "Image aspect ratio: ${String.format("%.2f", aspectRatio)} (${if (isWebtoon) "WEBTOON" else "NORMAL MANGA"})")
        DebugLogger.log(TAG, "Using area threshold: ${String.format("%.4f", MIN_AREA_RATIO * 100)}%")
        
        // Filter blocks by characteristics of speech bubbles
        val filteredBlocks = blocks.filterIndexed { index, (box, text) ->
            val area = box.width() * box.height()
            val areaRatio = area.toFloat() / imageArea
            
            DebugLogger.log(TAG, "Block $index: text='${text.take(15)}...'")
            DebugLogger.log(TAG, "  Size: ${box.width()}x${box.height()} = $area pixels")
            DebugLogger.log(TAG, "  Area ratio: ${String.format("%.4f", areaRatio * 100)}%")
            
            // FILTER 1: Must be dialogue (has letters)
            if (!isLikelyDialogue(text)) {
                DebugLogger.log(TAG, "  ❌ REJECTED: Not dialogue (no letters)")
                return@filterIndexed false
            }
            DebugLogger.log(TAG, "  ✅ Filter 1: Is dialogue")
            
            // FILTER 2: Must be big enough (adaptive threshold!)
            if (areaRatio < MIN_AREA_RATIO) {
                DebugLogger.log(TAG, "  ❌ REJECTED: Too small (${String.format("%.4f", areaRatio * 100)}% < ${String.format("%.4f", MIN_AREA_RATIO * 100)}%)")
                return@filterIndexed false
            }
            DebugLogger.log(TAG, "  ✅ Filter 2: Big enough")
            
            // FILTER 3: Aspect ratio check
            // For WEBTOONS: allow very tall blocks (0.1 to 10)
            // For NORMAL: standard range (0.3 to 10)
            val aspectRatio = box.width().toFloat() / box.height()
            val minAspectRatio = if (isWebtoon) 0.1f else 0.3f  // Webtoons can have tall vertical panels
            val maxAspectRatio = 10f
            
            DebugLogger.log(TAG, "  Aspect ratio: ${String.format("%.2f", aspectRatio)}")
            if (aspectRatio > maxAspectRatio || aspectRatio < minAspectRatio) {
                DebugLogger.log(TAG, "  ❌ REJECTED: Bad aspect ratio (${String.format("%.2f", aspectRatio)}) outside ${String.format("%.1f", minAspectRatio)}-${maxAspectRatio}")
                return@filterIndexed false
            }
            DebugLogger.log(TAG, "  ✅ Filter 3: Good aspect ratio")
            
            // FILTER 4: Position check (speech bubbles usually in upper 2/3 of image for webtoons)
            // Allow all for now, but can filter if needed
            
            DebugLogger.log(TAG, "  ✅✅✅ ACCEPTED as speech bubble!")
            true
        }
        
        DebugLogger.log(TAG, "After filtering: ${filteredBlocks.size} blocks passed")
        
        // Group nearby blocks into single bubbles (for multi-line bubbles)
        val grouped = groupNearbyBlocks(filteredBlocks)
        
        DebugLogger.log(TAG, "After grouping: ${grouped.size} bubble groups")
        
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
            
            DebugLogger.log(TAG, "Bubble group $index: '${mergedText.take(20)}...' gender=$gender emotion=$emotion")
            
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
