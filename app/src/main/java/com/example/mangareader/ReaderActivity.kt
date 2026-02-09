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
        
        // Setup RecyclerView for VERTICAL webtoon scrolling
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recyclerView.layoutManager = layoutManager
        adapter = MangaPageAdapter(mangaPages)
        recyclerView.adapter = adapter
        
        // CRITICAL: Add PagerSnapHelper to snap to one full screen at a time!
        // This ensures only ONE chunk visible, not multiple
        val snapHelper = androidx.recyclerview.widget.PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
        
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
                            
                            // Handle result - may contain multiple pages if image was chunked
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
                                // Tall webtoon - split into multiple pages (one per chunk)
                                Log.d(TAG, "Page $index: Tall webtoon split into ${analysisResult.pages.size} chunks")
                                
                                analysisResult.pages.forEachIndexed { chunkIdx, pageData ->
                                    Log.d(TAG, "  Chunk $chunkIdx: Found ${pageData.speechBubbles.size} bubbles")
                                    
                                    // Save chunk bitmap to temp file
                                    val chunkFile = File(cacheDir, "chunk_${index}_${chunkIdx}.jpg")
                                    chunkFile.outputStream().use { out ->
                                        pageData.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                    }
                                    
                                    val mangaPage = MangaPage(
                                        pageNumber = mangaPages.size,
                                        imagePath = chunkFile.absolutePath,
                                        speechBubbles = pageData.speechBubbles,
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
                }
                
                progressBar.visibility = View.GONE
                
                // Count total bubbles
                val totalBubbles = mangaPages.sumOf { it.speechBubbles.size }
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
                    miniStatusText.text = statusMessage  // Also update mini status
                    
                    // Highlight current bubble on the page
                    adapter.highlightBubble(currentPageIndex, currentBubbleIndex)
                    
                    // CRITICAL FIX: Two-step scroll
                    // Step 1: Scroll RecyclerView to correct PAGE (chunk)
                    recyclerView.smoothScrollToPosition(currentPageIndex)
                    
                    // Step 2: Wait for scroll to complete, then pan to bubble
                    kotlinx.coroutines.delay(400)  // Wait for page scroll
                    
                    // Step 3: Pan PhotoView to show bubble within the page
                    adapter.panToBubble(currentPageIndex, currentBubbleIndex, recyclerView)
                    
                    // Step 4: Wait for pan animation
                    val panDelay = (200 / scrollSpeedMultiplier).toLong().coerceIn(100, 500)
                    kotlinx.coroutines.delay(panDelay)
                    
                    // Speak the text
                    val success = ttsManager.speak(bubble)
                    
                    if (success) {
                        currentBubbleIndex++
                    } else {
                        // TTS failed, skip to next
                        currentBubbleIndex++
                    }
                } else {
                    // Move to next page
                    currentBubbleIndex = 0
                    currentPageIndex++
                    
                    if (currentPageIndex < mangaPages.size) {
                        // Smooth scroll to next page
                        recyclerView.smoothScrollToPosition(currentPageIndex)
                        
                        // Brief pause between pages
                        kotlinx.coroutines.delay(800)
                    } else {
                        // Finished reading all pages
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

    private fun updateStatus() {
        if (currentPageIndex < mangaPages.size) {
            val page = mangaPages[currentPageIndex]
            statusText.text = "Page ${currentPageIndex + 1}/${mangaPages.size} - ${page.speechBubbles.size} bubbles"
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
