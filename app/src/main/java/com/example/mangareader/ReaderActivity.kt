package com.example.mangareader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mangareader.ml.MangaAnalyzer
import com.example.mangareader.model.Emotion
import com.example.mangareader.model.MangaPage
import com.example.mangareader.model.SpeechBubble
import com.example.mangareader.tts.TTSManager
import com.example.mangareader.utils.DebugLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main reading activity with auto-narration and scrolling
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ReaderActivity"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var webtoonImage: com.github.chrisbanes.photoview.PhotoView
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var selectVoiceButton: MaterialButton
    
    private lateinit var ttsManager: TTSManager
    private lateinit var mangaAnalyzer: MangaAnalyzer
    private lateinit var adapter: MangaPageAdapter
    
    private val mangaPages = mutableListOf<MangaPage>()
    private val allBubbles = mutableListOf<SpeechBubble>()  // For continuous mode
    private var isContinuousMode = false  // true = ScrollView, false = RecyclerView
    private var currentPageIndex = 0
    private var isPlaying = false
    private var currentBubbleIndex = 0
    private var scrollSpeedMultiplier = 1.0f  // Controls delay between bubbles

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // FULLSCREEN MODE - Hide action bar and status bar
        supportActionBar?.hide()
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        try {
            setContentView(R.layout.activity_reader)
            
            initViews()
            initManagers()
            loadMangaPages()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Error loading reader: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
            finish()
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        scrollView = findViewById(R.id.scrollView)
        webtoonImage = findViewById(R.id.webtoonImage)
        playPauseButton = findViewById(R.id.playPauseButton)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedLabel = findViewById(R.id.speedLabel)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        selectVoiceButton = findViewById(R.id.selectVoiceButton)
        
        // Get new UI elements
        val floatingPlayButton = findViewById<FloatingActionButton>(R.id.floatingPlayButton)
        val miniStatusCard = findViewById<View>(R.id.miniStatusCard)
        val miniStatusText = findViewById<TextView>(R.id.miniStatusText)
        val controlsContainer = findViewById<View>(R.id.controlsContainer)
        val closeControlsButton = findViewById<ImageView>(R.id.closeControlsButton)
        val rootLayout = findViewById<View>(R.id.rootLayout)
        
        // Scroll speed control
        val scrollSpeedCard = findViewById<View>(R.id.scrollSpeedCard)
        val scrollSpeedSeekBar = findViewById<SeekBar>(R.id.scrollSpeedSeekBar)
        val scrollSpeedLabel = findViewById<TextView>(R.id.scrollSpeedLabel)
        
        // Setup RecyclerView (for multi-page mode)
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recyclerView.layoutManager = layoutManager
        adapter = MangaPageAdapter(mangaPages)
        recyclerView.adapter = adapter
        
        DebugLogger.log(TAG, "Views initialized - will detect mode after loading pages")
        
        // Track page changes
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val position = layoutManager.findFirstVisibleItemPosition()
                    if (position >= 0 && position != currentPageIndex) {
                        currentPageIndex = position
                        currentBubbleIndex = 0
                        updateStatus()
                    }
                }
            }
        })
        
        // Floating play button - Main control
        floatingPlayButton.setOnClickListener {
            togglePlayPause()
            // Also sync with main play button
            playPauseButton.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
        
        // Play/Pause button (in controls panel)
        playPauseButton.setOnClickListener {
            togglePlayPause()
            // Also sync with floating button
            floatingPlayButton.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
        
        // Long press on floating button or tap screen = Show full controls
        floatingPlayButton.setOnLongClickListener {
            controlsContainer.visibility = View.VISIBLE
            floatingPlayButton.visibility = View.GONE
            miniStatusCard.visibility = View.GONE
            true
        }
        
        rootLayout.setOnClickListener {
            // Tap screen to toggle controls
            if (controlsContainer.visibility == View.VISIBLE) {
                controlsContainer.visibility = View.GONE
                floatingPlayButton.visibility = View.VISIBLE
                if (isPlaying) {
                    miniStatusCard.visibility = View.VISIBLE
                    scrollSpeedCard.visibility = View.VISIBLE
                }
            } else {
                controlsContainer.visibility = View.VISIBLE
                floatingPlayButton.visibility = View.GONE
                miniStatusCard.visibility = View.GONE
                scrollSpeedCard.visibility = View.GONE
            }
        }
        
        // Close button hides controls
        closeControlsButton.setOnClickListener {
            controlsContainer.visibility = View.GONE
            floatingPlayButton.visibility = View.VISIBLE
            if (isPlaying) {
                miniStatusCard.visibility = View.VISIBLE
                scrollSpeedCard.visibility = View.VISIBLE
            }
        }
        
        // Scroll speed control
        scrollSpeedSeekBar.max = 30
        scrollSpeedSeekBar.progress = 10  // Default 1.0x
        scrollSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Range: 0.3x to 3.0x
                // progress 0-30 maps to 0.3-3.0
                scrollSpeedMultiplier = 0.3f + (progress / 10f)
                scrollSpeedLabel.text = String.format("Scroll: %.1fx", scrollSpeedMultiplier)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Speed control
        speedSeekBar.max = 20
        speedSeekBar.progress = 9 // 0.9 speed
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = (progress + 5) / 10f // Range: 0.5 to 2.5
                ttsManager.setSpeechRate(speed)
                speedLabel.text = String.format("Speed: %.1fx", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Voice selection button
        selectVoiceButton.setOnClickListener {
            showVoiceSelectionDialog()
        }
    }
    
    /**
     * Show dialog to select TTS voices
     */
    private fun showVoiceSelectionDialog() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val femaleVoices = ttsManager.femaleVoices
            val maleVoices = ttsManager.maleVoices
            
            if (femaleVoices.isEmpty() && maleVoices.isEmpty()) {
                Toast.makeText(this, "No voices available. Install Google TTS from Play Store!", Toast.LENGTH_LONG).show()
                return
            }
            
            // Build voice selection dialog
            val items = mutableListOf<String>()
            items.add("--- FEMALE VOICES (${femaleVoices.size}) ---")
            femaleVoices.forEach { voice ->
                items.add("👧 ${ttsManager.getVoiceName(voice)}")
            }
            items.add("--- MALE VOICES (${maleVoices.size}) ---")
            maleVoices.forEach { voice ->
                items.add("👨 ${ttsManager.getVoiceName(voice)}")
            }
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🎤 Select Voice")
                .setItems(items.toTypedArray()) { _, which ->
                    // Skip header rows
                    if (items[which].startsWith("---")) return@setItems
                    
                    // Determine if female or male
                    val isFemale = which <= femaleVoices.size
                    
                    if (isFemale) {
                        val voiceIndex = which - 1 // Skip header
                        if (voiceIndex >= 0 && voiceIndex < femaleVoices.size) {
                            val selectedVoice = femaleVoices[voiceIndex]
                            ttsManager.setVoiceFor(com.example.mangareader.model.Gender.FEMALE, selectedVoice)
                            selectVoiceButton.text = "🎤 ${ttsManager.getVoiceName(selectedVoice)}"
                            Toast.makeText(this, "Female voice updated!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val voiceIndex = which - femaleVoices.size - 2 // Skip both headers
                        if (voiceIndex >= 0 && voiceIndex < maleVoices.size) {
                            val selectedVoice = maleVoices[voiceIndex]
                            ttsManager.setVoiceFor(com.example.mangareader.model.Gender.MALE, selectedVoice)
                            Toast.makeText(this, "Male voice updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            Toast.makeText(this, "Voice selection requires Android 5.0+", Toast.LENGTH_LONG).show()
        }
    }

    private fun initManagers() {
        ttsManager = TTSManager(this)
        mangaAnalyzer = MangaAnalyzer(this)
    }

    /**
     * Load and analyze manga pages
     */
    private fun loadMangaPages() {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Analyzing manga pages..."
            
            try {
                // Get manga images from intent or assets
                val imagePaths = intent.getStringArrayListExtra("IMAGE_PATHS") ?: run {
                    // For demo, use sample images from assets
                    listOf("sample_page_1.jpg", "sample_page_2.jpg")
                }
                
                withContext(Dispatchers.IO) {
                    imagePaths.forEachIndexed { index, path ->
                        // Update progress
                        withContext(Dispatchers.Main) {
                            statusText.text = "Analyzing page ${index + 1}/${imagePaths.size}..."
                        }
                        
                        // Load bitmap
                        val bitmap = try {
                            // Try to load from file path
                            BitmapFactory.decodeFile(path)
                        } catch (e: Exception) {
                            // Try to load from assets
                            assets.open(path).use { inputStream ->
                                BitmapFactory.decodeStream(inputStream)
                            }
                        }
                        
                        if (bitmap != null) {
                            // Analyze page for speech bubbles
                            Log.d(TAG, "Analyzing page $index...")
                            DebugLogger.log(TAG, "=== PAGE $index LOADED ===")
                            DebugLogger.log(TAG, "Original bitmap size: ${bitmap.width} x ${bitmap.height}")
                            
                            // Get screen height for optimal chunking (each chunk = one full screen)
                            val screenHeight = recyclerView.height
                            val chunkHeight = if (screenHeight > 0) {
                                screenHeight
                            } else {
                                // Fallback: use display metrics if RecyclerView not measured yet  
                                resources.displayMetrics.heightPixels
                            }
                            
                            DebugLogger.log(TAG, "Chunk height: ${chunkHeight}px (full screen)")
                            DebugLogger.log(TAG, "Each chunk = one complete screen!")
                            
                            val analysisResult = mangaAnalyzer.analyzePage(bitmap, chunkHeight)
                            
                            // SCENE-BASED READING: Keep each chunk as a separate page!
                            // Each chunk = one scene = multiple bubbles
                            // User reads all bubbles on scene, then moves to next scene
                            
                            if (analysisResult.pages.size == 1) {
                                // Normal single page
                                val pageData = analysisResult.pages[0]
                                Log.d(TAG, "Page $index: Found ${pageData.speechBubbles.size} bubbles")
                                
                                val mangaPage = MangaPage(
                                    pageNumber = mangaPages.size,
                                    imagePath = path,
                                    speechBubbles = pageData.speechBubbles,
                                    isProcessed = true
                                )
                                
                                withContext(Dispatchers.Main) {
                                    mangaPages.add(mangaPage)
                                    adapter.notifyItemInserted(mangaPages.size - 1)
                                }
                            } else {
                                // Tall webtoon chunked for OCR processing
                                // Combine all chunks into ONE continuous page!
                                Log.d(TAG, "Page $index: Webtoon with ${analysisResult.pages.size} OCR chunks - combining into one continuous page")
                                
                                // Combine all speech bubbles from all chunks
                                val allBubbles = mutableListOf<SpeechBubble>()
                                analysisResult.pages.forEach { pageData ->
                                    Log.d(TAG, "  Chunk at Y=${pageData.originalYOffset}: Found ${pageData.speechBubbles.size} bubbles")
                                    
                                    // Add bubbles with their original Y positions from the full image
                                    allBubbles.addAll(pageData.speechBubbles)
                                }
                                
                                Log.d(TAG, "Total bubbles from all chunks: ${allBubbles.size}")
                                
                                // Create ONE page with the original full image
                                val mangaPage = MangaPage(
                                    pageNumber = mangaPages.size,
                                    imagePath = path,  // Original full webtoon image
                                    speechBubbles = allBubbles,
                                    isProcessed = true
                                )
                                
                                withContext(Dispatchers.Main) {
                                    mangaPages.add(mangaPage)
                                    adapter.notifyItemInserted(mangaPages.size - 1)
                                }
                            }
                        }
                    }
                }
                
                progressBar.visibility = View.GONE
                
                // Check if continuous mode (1 page with many bubbles)
                if (mangaPages.size == 1 && mangaPages[0].speechBubbles.isNotEmpty()) {
                    isContinuousMode = true
                    allBubbles.addAll(mangaPages[0].speechBubbles)
                    
                    Log.d(TAG, "CONTINUOUS MODE DETECTED")
                    Log.d(TAG, "  1 page with ${allBubbles.size} bubbles")
                    Log.d(TAG, "  Switching to ScrollView for smooth scrolling")
                    
                    // Load and SCALE image for safe display
                    val originalBitmap = BitmapFactory.decodeFile(mangaPages[0].imagePath)
                    if (originalBitmap != null) {
                        Log.d(TAG, "  Original bitmap: ${originalBitmap.width}x${originalBitmap.height}")
                        
                        // Scale to safe size (max 1080 width, 8192 height for webtoons)
                        val scaledBitmap = scaleBitmapForDisplay(originalBitmap)
                        
                        Log.d(TAG, "  Scaled bitmap: ${scaledBitmap.width}x${scaledBitmap.height}")
                        
                        // CRITICAL: Save scaled bitmap to NEW file
                        // PhotoView will load from this file instead of original
                        val scaledFile = File(cacheDir, "webtoon_scaled.jpg")
                        scaledFile.outputStream().use { out ->
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        Log.d(TAG, "  Scaled bitmap saved to: ${scaledFile.absolutePath}")
                        
                        // Load scaled bitmap into PhotoView from file
                        val displayBitmap = BitmapFactory.decodeFile(scaledFile.absolutePath)
                        webtoonImage.setImageBitmap(displayBitmap)
                        
                        // Calculate scale factor for bubble coordinates
                        val scaleX = scaledBitmap.width.toFloat() / originalBitmap.width
                        val scaleY = scaledBitmap.height.toFloat() / originalBitmap.height
                        
                        Log.d(TAG, "  Scale factors: X=${scaleX}, Y=${scaleY}")
                        
                        // Scale bubble coordinates to match scaled image
                        val scaledBubbles = allBubbles.map { bubble ->
                            SpeechBubble(
                                text = bubble.text,
                                boundingBox = android.graphics.Rect(
                                    (bubble.boundingBox.left * scaleX).toInt(),
                                    (bubble.boundingBox.top * scaleY).toInt(),
                                    (bubble.boundingBox.right * scaleX).toInt(),
                                    (bubble.boundingBox.bottom * scaleY).toInt()
                                ),
                                confidence = bubble.confidence,
                                characterGender = bubble.characterGender,
                                emotion = bubble.emotion
                            )
                        }
                        
                        allBubbles.clear()
                        allBubbles.addAll(scaledBubbles)
                        
                        Log.d(TAG, "  Bubble coordinates scaled - first bubble Y: ${scaledBubbles[0].boundingBox.top}")
                        
                        // Recycle to free memory
                        originalBitmap.recycle()
                        scaledBitmap.recycle()
                    } else {
                        Log.e(TAG, "  ERROR: Could not load original bitmap!")
                    }
                    
                    // Show ScrollView, hide RecyclerView
                    scrollView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    
                    DebugLogger.log(TAG, "Continuous webtoon loaded - ${allBubbles.size} bubbles ready")
                } else {
                    isContinuousMode = false
                    
                    Log.d(TAG, "MULTI-PAGE MODE")
                    Log.d(TAG, "  ${mangaPages.size} pages")
                    
                    // Show RecyclerView, hide ScrollView
                    scrollView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
                
                // Count total bubbles
                val totalBubbles = if (isContinuousMode) allBubbles.size else mangaPages.sumOf { it.speechBubbles.size }
                Log.d(TAG, "=== FINAL RESULT: ${mangaPages.size} pages, $totalBubbles total bubbles ===")
                
                statusText.text = if (totalBubbles > 0) {
                    "Ready to play - $totalBubbles bubbles found"
                } else {
                    "Ready to play - NO BUBBLES DETECTED! Check logs!"
                }
                
                if (mangaPages.isEmpty()) {
                    Toast.makeText(this@ReaderActivity, 
                        "No manga pages loaded. Please select manga images.", 
                        Toast.LENGTH_LONG
                    ).show()
                } else if (totalBubbles == 0) {
                    Toast.makeText(this@ReaderActivity,
                        "No speech bubbles detected! OCR may have failed. Check Debug Logs menu.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = "Error loading manga"
                Toast.makeText(this@ReaderActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Toggle play/pause narration
     */
    private fun togglePlayPause() {
        isPlaying = !isPlaying
        
        val floatingPlayButton = findViewById<FloatingActionButton>(R.id.floatingPlayButton)
        val miniStatusCard = findViewById<View>(R.id.miniStatusCard)
        val scrollSpeedCard = findViewById<View>(R.id.scrollSpeedCard)
        
        if (isPlaying) {
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            floatingPlayButton.setImageResource(android.R.drawable.ic_media_pause)
            startNarration()
        } else {
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            floatingPlayButton.setImageResource(android.R.drawable.ic_media_play)
            miniStatusCard.visibility = View.GONE
            scrollSpeedCard.visibility = View.GONE
            ttsManager.stop()
        }
    }

    /**
     * Start narrating current page
     */
    private fun startNarration() {
        // Show mini status and scroll speed when playing
        val miniStatusCard = findViewById<View>(R.id.miniStatusCard)
        val miniStatusText = findViewById<TextView>(R.id.miniStatusText)
        val scrollSpeedCard = findViewById<View>(R.id.scrollSpeedCard)
        
        miniStatusCard.visibility = View.VISIBLE
        scrollSpeedCard.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            if (isContinuousMode) {
                // CONTINUOUS MODE: Scroll through one big webtoon
                DebugLogger.log(TAG, "Starting continuous mode narration")
                
                while (isPlaying && currentBubbleIndex < allBubbles.size) {
                    val bubble = allBubbles[currentBubbleIndex]
                    
                    // Update status with emotion indicator
                    val emotionIcon = when (bubble.emotion) {
                        Emotion.HAPPY -> "😊"
                        Emotion.SAD -> "😢"
                        Emotion.ANGRY -> "😠"
                        Emotion.SURPRISED -> "😲"
                        Emotion.SCARED -> "😰"
                        Emotion.NEUTRAL -> "💬"
                    }
                    val statusMessage = "$emotionIcon ${bubble.text.take(30)}..."
                    statusText.text = statusMessage
                    miniStatusText.text = statusMessage
                    
                    // CRITICAL: SCROLL TO BUBBLE FIRST!
                    val bubbleY = bubble.boundingBox.centerY()
                    val screenHeight = scrollView.height
                    val targetY = (bubbleY - screenHeight / 2).toInt().coerceAtLeast(0)
                    
                    DebugLogger.log(TAG, "Scrolling to bubble $currentBubbleIndex at Y=$bubbleY (target scroll=$targetY)")
                    
                    withContext(Dispatchers.Main) {
                        scrollView.smoothScrollTo(0, targetY)
                    }
                    
                    // Wait for scroll to complete
                    kotlinx.coroutines.delay(500)
                    
                    // NOW read the bubble
                    val success = ttsManager.speak(bubble)
                    
                    if (success) {
                        currentBubbleIndex++
                    } else {
                        currentBubbleIndex++
                    }
                }
                
                // Finished
                isPlaying = false
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                statusText.text = "Finished reading webtoon"
                currentBubbleIndex = 0
                
            } else {
                // MULTI-PAGE MODE: Use RecyclerView
                while (isPlaying && currentPageIndex < mangaPages.size) {
                    val currentPage = mangaPages[currentPageIndex]
                    
                    if (currentBubbleIndex < currentPage.speechBubbles.size) {
                        val bubble = currentPage.speechBubbles[currentBubbleIndex]
                        
                        // Update status with emotion indicator
                        val emotionIcon = when (bubble.emotion) {
                            Emotion.HAPPY -> "😊"
                            Emotion.SAD -> "😢"
                            Emotion.ANGRY -> "😠"
                            Emotion.SURPRISED -> "😲"
                            Emotion.SCARED -> "😰"
                            Emotion.NEUTRAL -> "💬"
                        }
                        val statusMessage = "$emotionIcon ${bubble.text.take(30)}..."
                        statusText.text = statusMessage
                        miniStatusText.text = statusMessage
                        
                        // Scroll to bubble first
                        adapter.scrollToBubble(currentPageIndex, currentBubbleIndex, recyclerView)
                        kotlinx.coroutines.delay(500)
                        
                        // Highlight bubble
                        adapter.highlightBubble(currentPageIndex, currentBubbleIndex)
                        
                        // Speak the text
                        val success = ttsManager.speak(bubble)
                        
                        if (success) {
                            currentBubbleIndex++
                        } else {
                            currentBubbleIndex++
                        }
                    } else {
                        // Move to next page
                        currentBubbleIndex = 0
                        currentPageIndex++
                        
                        if (currentPageIndex < mangaPages.size) {
                            recyclerView.smoothScrollToPosition(currentPageIndex)
                            kotlinx.coroutines.delay(800)
                        } else {
                            isPlaying = false
                            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                            statusText.text = "Finished reading manga"
                            currentPageIndex = 0
                            break
                        }
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        if (currentPageIndex < mangaPages.size) {
            val page = mangaPages[currentPageIndex]
            statusText.text = "Page ${currentPageIndex + 1}/${mangaPages.size} - ${page.speechBubbles.size} bubbles"
        }
    }

    /**
     * Scale bitmap to safe size for display - prevents 810MB crash!
     */
    private fun scaleBitmapForDisplay(bitmap: Bitmap): Bitmap {
        // Android Canvas limit is ~100MB
        // Safe max dimension: 4096x4096 = ~67MB for ARGB_8888
        // For webtoons (tall images), use 1080 width max
        val MAX_WIDTH = 1080
        val MAX_HEIGHT = 8192  // Allow tall webtoons
        
        val width = bitmap.width
        val height = bitmap.height
        
        // If already small enough, return as-is
        if (width <= MAX_WIDTH && height <= MAX_HEIGHT) {
            return bitmap
        }
        
        // Calculate scale to fit within limits
        val scaleWidth = MAX_WIDTH.toFloat() / width
        val scaleHeight = MAX_HEIGHT.toFloat() / height
        val scale = kotlin.math.min(scaleWidth, scaleHeight)
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        Log.d(TAG, "Scaling bitmap: ${width}x${height} -> ${newWidth}x${newHeight} (scale=$scale)")
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
            // Recycle original to free memory
            if (it != bitmap) bitmap.recycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        mangaAnalyzer.close()
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) {
            togglePlayPause()
        }
    }
}
