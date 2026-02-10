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
                            
                            // Check if this is a tall continuous webtoon
                            val aspectRatio = bitmap.height.toFloat() / bitmap.width
                            val isContinuousWebtoon = aspectRatio > 5.0f
                            
                            if (isContinuousWebtoon) {
                                Log.d(TAG, "")
                                Log.d(TAG, "✅ CONTINUOUS WEBTOON DETECTED!")
                                Log.d(TAG, "Aspect ratio: $aspectRatio")
                                Log.d(TAG, "SKIPPING upfront OCR - will OCR on-demand during playback")
                                Log.d(TAG, "This is MUCH faster!")
                                Log.d(TAG, "")
                                
                                // Create a dummy page with no bubbles
                                // Bubbles will be detected viewport-by-viewport during playback
                                val mangaPage = MangaPage(
                                    pageNumber = mangaPages.size,
                                    imagePath = path,
                                    speechBubbles = emptyList(), // Empty - will OCR viewports during playback
                                    bitmap = null
                                )
                                
                                mangaPages.add(mangaPage)
                                bitmap.recycle()
                                
                            } else {
                                Log.d(TAG, "Normal manga page - running full OCR")
                                
                                withContext(Dispatchers.Main) {
                                    mangaPages.add(mangaPage)
                                    adapter.notifyItemInserted(mangaPages.size - 1)
                                }
                                
                                bitmap.recycle()
                            }
                        }
                    }
                }
                
                progressBar.visibility = View.GONE
                
                Log.d(TAG, "=== LOADING COMPLETE ===")
                Log.d(TAG, "Pages: ${mangaPages.size}")
                
                // ALWAYS use continuous mode for single-page documents
                if (mangaPages.size == 1) {
                    isContinuousMode = true
                    
                    // For continuous webtoons, bubbles will be detected on-demand during playback
                    // For normal pages, bubbles are already detected
                    allBubbles.addAll(mangaPages[0].speechBubbles)
                    
                    if (allBubbles.isEmpty()) {
                        Log.d(TAG, "✅ CONTINUOUS WEBTOON MODE - Viewport OCR")
                        Log.d(TAG, "Bubbles will be detected on-demand during playback")
                    } else {
                        Log.d(TAG, "✅ SINGLE PAGE MODE - Using ScrollView")
                        Log.d(TAG, "Total bubbles: ${allBubbles.size}")
                    }
                    
                    // Load and scale bitmap
                    val originalBitmap = BitmapFactory.decodeFile(mangaPages[0].imagePath)
                    if (originalBitmap != null) {
                        val scaledBitmap = scaleBitmapForDisplay(originalBitmap)
                        
                        // Save scaled bitmap to file
                        val scaledFile = File(cacheDir, "webtoon_scaled.jpg")
                        scaledFile.outputStream().use { out ->
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        
                        // Load into PhotoView
                        val displayBitmap = BitmapFactory.decodeFile(scaledFile.absolutePath)
                        webtoonImage.setImageBitmap(displayBitmap)
                        
                        // Scale bubble coordinates
                        val scaleY = scaledBitmap.height.toFloat() / originalBitmap.height
                        val scaledBubbles = allBubbles.map { bubble ->
                            SpeechBubble(
                                text = bubble.text,
                                boundingBox = android.graphics.Rect(
                                    bubble.boundingBox.left,
                                    (bubble.boundingBox.top * scaleY).toInt(),
                                    bubble.boundingBox.right,
                                    (bubble.boundingBox.bottom * scaleY).toInt()
                                ),
                                confidence = bubble.confidence,
                                characterGender = bubble.characterGender,
                                emotion = bubble.emotion
                            )
                        }
                        
                        allBubbles.clear()
                        allBubbles.addAll(scaledBubbles)
                        
                        Log.d(TAG, "Scaled image: ${originalBitmap.width}x${originalBitmap.height} → ${scaledBitmap.width}x${scaledBitmap.height}")
                        Log.d(TAG, "Scale factor Y: $scaleY")
                        Log.d(TAG, "First bubble Y: ${scaledBubbles.firstOrNull()?.boundingBox?.top}")
                        
                        originalBitmap.recycle()
                        scaledBitmap.recycle()
                    }
                    
                    // Show ScrollView, hide RecyclerView
                    scrollView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    
                    Log.d(TAG, "ScrollView shown, RecyclerView hidden")
                } else {
                    isContinuousMode = false
                    scrollView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    Log.d(TAG, "Multi-page mode - using RecyclerView")
                }
                
                // Count total bubbles
                val totalBubbles = if (isContinuousMode) allBubbles.size else mangaPages.sumOf { it.speechBubbles.size }
                Log.d(TAG, "=== READY TO PLAY ===")
                Log.d(TAG, "Mode: ${if (isContinuousMode) "CONTINUOUS SCROLL" else "MULTI-PAGE"}")
                Log.d(TAG, "Total bubbles: $totalBubbles")
                
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
            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════╗")
            Log.d(TAG, "║      AUTO-PLAY STARTING!               ║")
            Log.d(TAG, "╚════════════════════════════════════════╝")
            
            if (isContinuousMode) {
                // CONTINUOUS MODE: OCR viewport, read, scroll, repeat
                Log.d(TAG, "✅ CONTINUOUS VIEWPORT MODE")
                Log.d(TAG, "Will OCR only visible area, then scroll")
                Log.d(TAG, "")
                
                val screenHeight = scrollView.height
                scrollView.scrollTo(0, 0) // Start at top
                
                Log.d(TAG, "Screen height: $screenHeight px")
                
                var currentScrollY = 0
                var totalBubblesRead = 0
                
                while (isPlaying) {
                    Log.d(TAG, "")
                    Log.d(TAG, "┌─────────────────────────────────────┐")
                    Log.d(TAG, "│  OCR-ING VIEWPORT at Y=$currentScrollY")
                    Log.d(TAG, "└─────────────────────────────────────┘")
                    
                    // Get visible portion of image
                    val originalBitmap = BitmapFactory.decodeFile(mangaPages[0].imagePath)
                    if (originalBitmap == null) {
                        Log.e(TAG, "Failed to load bitmap!")
                        break
                    }
                    
                    // Calculate visible region (with some overlap for bubbles on edge)
                    val overlap = 300 // px overlap to catch bubbles on edges
                    val startY = (currentScrollY - overlap).coerceAtLeast(0)
                    val endY = (currentScrollY + screenHeight + overlap).coerceAtMost(originalBitmap.height)
                    val viewportHeight = endY - startY
                    
                    Log.d(TAG, "Viewport: Y=$startY to Y=$endY (height=$viewportHeight px)")
                    
                    // Extract viewport bitmap
                    val viewportBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        0,
                        startY,
                        originalBitmap.width,
                        viewportHeight
                    )
                    
                    // OCR this viewport ONLY
                    Log.d(TAG, "Running OCR on viewport...")
                    val viewportResult = mangaAnalyzer.analyzePage(viewportBitmap, screenHeight)
                    
                    // Adjust bubble Y coordinates (they're relative to viewport, need to be absolute)
                    val viewportBubbles = mutableListOf<SpeechBubble>()
                    viewportResult.pages.forEach { pageData ->
                        pageData.speechBubbles.forEach { bubble ->
                            val adjustedBubble = SpeechBubble(
                                text = bubble.text,
                                boundingBox = android.graphics.Rect(
                                    bubble.boundingBox.left,
                                    bubble.boundingBox.top + startY, // Adjust to absolute position
                                    bubble.boundingBox.right,
                                    bubble.boundingBox.bottom + startY
                                ),
                                confidence = bubble.confidence,
                                characterGender = bubble.characterGender,
                                emotion = bubble.emotion
                            )
                            
                            // Only include bubbles in current viewport (not overlap area)
                            val bubbleY = adjustedBubble.boundingBox.centerY()
                            if (bubbleY >= currentScrollY && bubbleY < currentScrollY + screenHeight) {
                                viewportBubbles.add(adjustedBubble)
                            }
                        }
                    }
                    
                    Log.d(TAG, "Found ${viewportBubbles.size} bubbles in viewport")
                    
                    viewportBitmap.recycle()
                    originalBitmap.recycle()
                    
                    // Read bubbles in this viewport
                    if (viewportBubbles.isEmpty()) {
                        Log.d(TAG, "No bubbles in this viewport - scrolling down")
                    } else {
                        for ((index, bubble) in viewportBubbles.withIndex()) {
                            if (!isPlaying) break
                            
                            totalBubblesRead++
                            Log.d(TAG, "")
                            Log.d(TAG, "🗣️ Reading bubble $totalBubblesRead: '${bubble.text.take(30)}...'")
                            
                            val emotionIcon = when (bubble.emotion) {
                                Emotion.HAPPY -> "😊"
                                Emotion.SAD -> "😢"
                                Emotion.ANGRY -> "😠"
                                Emotion.SURPRISED -> "😲"
                                Emotion.SCARED -> "😰"
                                Emotion.NEUTRAL -> "💬"
                            }
                            val statusMessage = "$emotionIcon ${bubble.text.take(30)}..."
                            
                            withContext(Dispatchers.Main) {
                                statusText.text = statusMessage
                                miniStatusText.text = statusMessage
                            }
                            
                            ttsManager.speak(bubble)
                        }
                    }
                    
                    // Scroll down to next viewport
                    currentScrollY += screenHeight
                    
                    // Check if we've reached the bottom
                    if (currentScrollY >= mangaPages[0].run {
                        val bmp = BitmapFactory.decodeFile(imagePath)
                        val h = bmp?.height ?: 0
                        bmp?.recycle()
                        h
                    }) {
                        Log.d(TAG, "Reached bottom of webtoon!")
                        break
                    }
                    
                    Log.d(TAG, "")
                    Log.d(TAG, "⬇️ Scrolling to next viewport (Y=$currentScrollY)")
                    
                    withContext(Dispatchers.Main) {
                        scrollView.smoothScrollTo(0, currentScrollY)
                    }
                    
                    kotlinx.coroutines.delay(800) // Wait for scroll
                }
                
                // Finished
                isPlaying = false
                withContext(Dispatchers.Main) {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    statusText.text = "Finished! Read $totalBubblesRead bubbles"
                    miniStatusCard.visibility = View.GONE
                    scrollSpeedCard.visibility = View.GONE
                }
                
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
