package com.example.mangareader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class to process PDF files and convert them to images
 */
class PDFProcessor(private val context: Context) {

    /**
     * Data class to hold PDF page information
     */
    data class PDFPageInfo(
        val pageNumber: Int,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int
    )

    /**
     * Extract all pages from a PDF as bitmaps
     */
    suspend fun extractPagesFromPDF(pdfUri: Uri): List<PDFPageInfo> = withContext(Dispatchers.IO) {
        val pages = mutableListOf<PDFPageInfo>()
        
        try {
            // Copy PDF to cache if needed
            val pdfFile = copyUriToFile(pdfUri)
            
            // Open PDF with PdfRenderer
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            
            val pdfRenderer = PdfRenderer(fileDescriptor)
            
            // Process each page
            for (pageIndex in 0 until pdfRenderer.pageCount) {
                val page = pdfRenderer.openPage(pageIndex)
                
                // Calculate rendering dimensions (high quality)
                val renderWidth = page.width * 2  // 2x for better quality
                val renderHeight = page.height * 2
                
                // Create bitmap for this page
                val bitmap = Bitmap.createBitmap(
                    renderWidth,
                    renderHeight,
                    Bitmap.Config.ARGB_8888
                )
                
                // Render PDF page to bitmap
                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                
                pages.add(
                    PDFPageInfo(
                        pageNumber = pageIndex + 1,
                        bitmap = bitmap,
                        width = renderWidth,
                        height = renderHeight
                    )
                )
                
                page.close()
            }
            
            pdfRenderer.close()
            fileDescriptor.close()
            
            // Clean up temp file
            pdfFile.delete()
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw PDFProcessingException("Failed to process PDF: ${e.message}")
        }
        
        return@withContext pages
    }

    /**
     * Extract a single page from PDF
     */
    suspend fun extractSinglePage(pdfUri: Uri, pageNumber: Int): PDFPageInfo? = withContext(Dispatchers.IO) {
        try {
            val pdfFile = copyUriToFile(pdfUri)
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            
            val pdfRenderer = PdfRenderer(fileDescriptor)
            
            if (pageNumber < 0 || pageNumber >= pdfRenderer.pageCount) {
                throw IllegalArgumentException("Invalid page number: $pageNumber")
            }
            
            val page = pdfRenderer.openPage(pageNumber)
            val renderWidth = page.width * 2
            val renderHeight = page.height * 2
            
            val bitmap = Bitmap.createBitmap(
                renderWidth,
                renderHeight,
                Bitmap.Config.ARGB_8888
            )
            
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            val pageInfo = PDFPageInfo(
                pageNumber = pageNumber + 1,
                bitmap = bitmap,
                width = renderWidth,
                height = renderHeight
            )
            
            page.close()
            pdfRenderer.close()
            fileDescriptor.close()
            pdfFile.delete()
            
            return@withContext pageInfo
            
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    /**
     * Get total page count from PDF
     */
    suspend fun getPageCount(pdfUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val pdfFile = copyUriToFile(pdfUri)
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            
            val pdfRenderer = PdfRenderer(fileDescriptor)
            val count = pdfRenderer.pageCount
            
            pdfRenderer.close()
            fileDescriptor.close()
            pdfFile.delete()
            
            return@withContext count
            
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0
        }
    }

    /**
     * Copy URI content to a temporary file
     */
    private fun copyUriToFile(uri: Uri): File {
        val tempFile = File.createTempFile("manga_pdf_", ".pdf", context.cacheDir)
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        
        return tempFile
    }

    /**
     * Save bitmap as temporary image file
     */
    fun saveBitmapToTemp(bitmap: Bitmap, pageNumber: Int): File {
        val tempFile = File(context.cacheDir, "manga_page_$pageNumber.png")
        
        FileOutputStream(tempFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        
        return tempFile
    }

    /**
     * Process PDF and save pages as image files
     */
    suspend fun convertPDFToImages(pdfUri: Uri): List<String> = withContext(Dispatchers.IO) {
        val imagePaths = mutableListOf<String>()
        val pages = extractPagesFromPDF(pdfUri)
        
        pages.forEach { pageInfo ->
            val imageFile = saveBitmapToTemp(pageInfo.bitmap, pageInfo.pageNumber)
            imagePaths.add(imageFile.absolutePath)
        }
        
        return@withContext imagePaths
    }

    /**
     * Clear cached PDF images
     */
    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("manga_page_") || file.name.startsWith("manga_pdf_")) {
                file.delete()
            }
        }
    }
}

/**
 * Custom exception for PDF processing errors
 */
class PDFProcessingException(message: String) : Exception(message)
