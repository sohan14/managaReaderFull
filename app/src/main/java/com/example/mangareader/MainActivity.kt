package com.example.mangareader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
        try {
            setContentView(R.layout.activity_main)
            
            pdfProcessor = PDFProcessor(this)
            initViews()
            setupListeners()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Error starting app: ${e.message}\nPlease report this bug",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
            // Don't finish() - let user see error
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
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up cached PDF images
        pdfProcessor.clearCache()
    }
}
