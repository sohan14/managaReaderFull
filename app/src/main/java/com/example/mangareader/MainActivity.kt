package com.example.mangareader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.mangareader.utils.CrashLogger
import com.example.mangareader.utils.DebugLogger
import com.example.mangareader.utils.PDFProcessor
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch

/**
 * Main launcher activity for selecting manga images or PDF files
 */
class MainActivity : AppCompatActivity() {

    private lateinit var selectImagesButton: MaterialButton
    private lateinit var selectPDFButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var statusText: MaterialTextView
    
    private val selectedImages = mutableListOf<Uri>()
    private var selectedPDF: Uri? = null
    private var pdfImagePaths: List<String>? = null
    private lateinit var pdfProcessor: PDFProcessor

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages.clear()
            selectedImages.addAll(uris)
            selectedPDF = null
            pdfImagePaths = null
            statusText.text = "✅ ${selectedImages.size} page(s) selected\n📖 Tap 'Start Reading' to begin!"
            startButton.isEnabled = true
        }
    }
    
    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedPDF = it
            selectedImages.clear()
            pdfImagePaths = null
            processPDF(it)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            Toast.makeText(this, "Permission denied. Cannot access images.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash logger
        CrashLogger.getInstance(this)
        
        // Initialize debug logger
        DebugLogger.init(this)
        
        try {
            setContentView(R.layout.activity_main)
            
            pdfProcessor = PDFProcessor(this)
            initViews()
            setupListeners()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Error starting app: ${e.message}\nCheck Debug menu for crash logs",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
        }
    }

    private fun initViews() {
        selectImagesButton = findViewById(R.id.selectImagesButton)
        selectPDFButton = findViewById(R.id.selectPDFButton)
        startButton = findViewById(R.id.startReadingButton)
        statusText = findViewById(R.id.statusText)
        
        startButton.isEnabled = false
    }

    private fun setupListeners() {
        selectImagesButton.setOnClickListener {
            requestPermissionAndPickImages()
        }
        
        selectPDFButton.setOnClickListener {
            openPDFPicker()
        }
        
        startButton.setOnClickListener {
            startReading()
        }
    }

    private fun requestPermissionAndPickImages() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ uses READ_MEDIA_IMAGES
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    == PackageManager.PERMISSION_GRANTED) {
                    openImagePicker()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                }
            }
            else -> {
                // Below Android 13 uses READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                    openImagePicker()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun openImagePicker() {
        imagePickerLauncher.launch("image/*")
    }
    
    private fun openPDFPicker() {
        pdfPickerLauncher.launch("application/pdf")
    }
    
    private fun processPDF(pdfUri: Uri) {
        statusText.text = "Processing PDF..."
        startButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                // Get page count first
                val pageCount = pdfProcessor.getPageCount(pdfUri)
                statusText.text = "Converting PDF ($pageCount pages)..."
                
                // Convert PDF to images
                pdfImagePaths = pdfProcessor.convertPDFToImages(pdfUri)
                
                statusText.text = "PDF ready: $pageCount pages"
                startButton.isEnabled = true
                
            } catch (e: Exception) {
                statusText.text = "Error processing PDF: ${e.message}"
                Toast.makeText(this@MainActivity, 
                    "Failed to process PDF: ${e.message}", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startReading() {
        val imagePaths = when {
            pdfImagePaths != null -> {
                // Using PDF-converted images
                ArrayList(pdfImagePaths!!)
            }
            selectedImages.isNotEmpty() -> {
                // Using selected images
                val paths = ArrayList<String>()
                selectedImages.forEach { uri ->
                    val path = getRealPathFromURI(uri)
                    if (path != null) {
                        paths.add(path)
                    }
                }
                paths
            }
            else -> {
                Toast.makeText(this, "Please select manga images or PDF first", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        if (imagePaths.isEmpty()) {
            Toast.makeText(this, "Failed to load images", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Start reader activity
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putStringArrayListExtra("IMAGE_PATHS", imagePaths)
        startActivity(intent)
    }

    /**
     * Convert content URI to file path
     */
    private fun getRealPathFromURI(uri: Uri): String? {
        var result: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        
        cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                if (columnIndex >= 0) {
                    result = it.getString(columnIndex)
                }
            }
        }
        
        // If we couldn't get the path, return the URI string
        return result ?: uri.toString()
    }
    
    /**
     * Create options menu with debug crash logs option
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "📊 OCR Debug Logs")
        menu?.add(0, 2, 1, "🐛 Crash Logs")
        return true
    }
    
    /**
     * Handle menu item clicks
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                showOCRLogsDialog()
                true
            }
            2 -> {
                showCrashLogsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Show OCR debug logs dialog
     */
    private fun showOCRLogsDialog() {
        val logs = DebugLogger.getAllLogs()
        
        AlertDialog.Builder(this)
            .setTitle("📊 OCR Debug Logs")
            .setMessage(logs)
            .setPositiveButton("📋 Copy to Clipboard") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("OCR Logs", logs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "OCR logs copied! Send them to me!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("🗑️ Clear Logs") { _, _ ->
                DebugLogger.clearLogs()
                Toast.makeText(this, "OCR logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Close", null)
            .show()
    }
    
    /**
     * Show crash logs dialog with copy and clear options
     */
    private fun showCrashLogsDialog() {
        val crashLogger = CrashLogger.getInstance(this)
        val logs = crashLogger.getAllLogs()
        
        AlertDialog.Builder(this)
            .setTitle("🐛 Crash Logs")
            .setMessage(logs)
            .setPositiveButton("📋 Copy to Clipboard") { _, _ ->
                if (crashLogger.copyLogsToClipboard()) {
                    Toast.makeText(this, "Logs copied! You can paste and send them now", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to copy logs", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("🗑️ Clear Logs") { _, _ ->
                crashLogger.clearLogs()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Close", null)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up cached PDF images
        pdfProcessor.clearCache()
    }
}
