package com.example.mangareader

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.mangareader.ml.MangaAnalyzer
import com.example.mangareader.model.Emotion
import com.example.mangareader.model.MangaPage
import com.example.mangareader.tts.TTSManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main reading activity with auto-narration and scrolling
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var genderToggleButton: MaterialButton
    private lateinit var voiceInfoButton: MaterialButton
    
    private lateinit var ttsManager: TTSManager
    private lateinit var mangaAnalyzer: MangaAnalyzer
    private lateinit var adapter: MangaPageAdapter
    
    private val mangaPages = mutableListOf<MangaPage>()
    private var currentPageIndex = 0
    private var isPlaying = false
    private var currentBubbleIndex = 0
    
    // Gender detection override
    private var manualGenderMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        genderToggleButton = findViewById(R.id.genderToggleButton)
        voiceInfoButton = findViewById(R.id.voiceInfoButton)
        
        // Setup RecyclerView
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        adapter = MangaPageAdapter(mangaPages)
        recyclerView.adapter = adapter
        
        // Add snap helper for page-by-page scrolling
        val snapHelper = PagerSnapHelper()
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
        
        // Play/Pause button
        playPauseButton.setOnClickListener {
            togglePlayPause()
        }
        
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
        
        // Gender detection toggle
        genderToggleButton.setOnClickListener {
            manualGenderMode = !manualGenderMode
            genderToggleButton.text = if (manualGenderMode) "Manual Gender" else "Auto Gender"
            Toast.makeText(this, 
                if (manualGenderMode) "Manual gender selection enabled" else "Auto gender detection enabled",
                Toast.LENGTH_SHORT
            ).show()
        }
        
        // Voice info button
        voiceInfoButton.setOnClickListener {
            val voiceInfo = ttsManager.getVoiceInfo()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("TTS Voice Information")
                .setMessage(voiceInfo)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun initManagers() {
        ttsManager = TTSManager(this)
        mangaAnalyzer = MangaAnalyzer()
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
                            // Analyze page
                            val mangaPage = mangaAnalyzer.analyzePage(bitmap, index)
                                .copy(imagePath = path)
                            
                            withContext(Dispatchers.Main) {
                                mangaPages.add(mangaPage)
                                adapter.notifyItemInserted(mangaPages.size - 1)
                            }
                        }
                    }
                }
                
                progressBar.visibility = View.GONE
                statusText.text = "Ready to play"
                
                if (mangaPages.isEmpty()) {
                    Toast.makeText(this@ReaderActivity, 
                        "No manga pages loaded. Please select manga images.", 
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
        
        if (isPlaying) {
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            startNarration()
        } else {
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            ttsManager.stop()
        }
    }

    /**
     * Start narrating current page
     */
    private fun startNarration() {
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
                    statusText.text = "$emotionIcon ${bubble.text.take(30)}..."
                    
                    // Highlight current bubble on the page
                    adapter.highlightBubble(currentPageIndex, currentBubbleIndex)
                    
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
