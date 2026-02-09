package com.example.mangareader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mangareader.model.MangaPage
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.min

/**
 * Adapter for displaying manga pages with zoom support
 */
class MangaPageAdapter(
    private val pages: List<MangaPage>
) : RecyclerView.Adapter<MangaPageAdapter.PageViewHolder>() {

    private var highlightedPage = -1
    private var highlightedBubble = -1

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photoView: PhotoView = view.findViewById(R.id.mangaPageImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        
        // Load and SCALE the manga page image to prevent crashes
        val bitmap = try {
            val fullBitmap = BitmapFactory.decodeFile(page.imagePath)
            if (fullBitmap != null) {
                scaleBitmapForDisplay(fullBitmap)
            } else null
        } catch (e: Exception) {
            // Try assets
            try {
                holder.itemView.context.assets.open(page.imagePath).use { inputStream ->
                    val fullBitmap = BitmapFactory.decodeStream(inputStream)
                    scaleBitmapForDisplay(fullBitmap)
                }
            } catch (e: Exception) {
                null
            }
        }
        
        if (bitmap != null) {
            // Set zoom limits for webtoon viewing
            // Start zoomed OUT to see more content
            holder.photoView.minimumScale = 0.5f   // Can zoom out to half size
            holder.photoView.mediumScale = 1.0f    // Default/fit size
            holder.photoView.maximumScale = 3.0f   // Can zoom in to 3x
            
            // Start at fit-to-screen scale (not 1:1)
            holder.photoView.setScale(1.0f, true)
            
            // If this page and bubble are highlighted, draw overlay
            if (position == highlightedPage && highlightedBubble >= 0 && highlightedBubble < page.speechBubbles.size) {
                val mutableBitmap = bitmap.copy(bitmap.config, true)
                val canvas = Canvas(mutableBitmap)
                val paint = Paint().apply {
                    color = Color.argb(100, 255, 255, 0) // Semi-transparent yellow
                    style = Paint.Style.FILL
                }
                
                val bubble = page.speechBubbles[highlightedBubble]
                canvas.drawRect(bubble.boundingBox, paint)
                
                // Draw border
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = Color.YELLOW
                canvas.drawRect(bubble.boundingBox, paint)
                
                holder.photoView.setImageBitmap(mutableBitmap)
            } else {
                holder.photoView.setImageBitmap(bitmap)
            }
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
        val scale = min(scaleWidth, scaleHeight)
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
            // Recycle original to free memory
            if (it != bitmap) bitmap.recycle()
        }
    }

    override fun getItemCount(): Int = pages.size

    /**
     * Highlight a specific speech bubble on a page
     */
    fun highlightBubble(pageIndex: Int, bubbleIndex: Int) {
        val previousPage = highlightedPage
        highlightedPage = pageIndex
        highlightedBubble = bubbleIndex
        
        // Refresh the highlighted page
        if (previousPage >= 0 && previousPage != pageIndex) {
            notifyItemChanged(previousPage)
        }
        notifyItemChanged(pageIndex)
    }
    
    /**
     * Auto-pan PhotoView to show current bubble being read
     * IMPROVED: Waits for ViewHolder to be available
     */
    fun panToBubble(pageIndex: Int, bubbleIndex: Int, recyclerView: androidx.recyclerview.widget.RecyclerView) {
        // Try multiple times to get ViewHolder (it might not be ready immediately after scroll)
        var attempts = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        fun tryPan() {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(pageIndex) as? PageViewHolder
            
            if (viewHolder != null) {
                val page = pages[pageIndex]
                if (bubbleIndex < page.speechBubbles.size) {
                    val bubble = page.speechBubbles[bubbleIndex]
                    val bubbleRect = bubble.boundingBox
                    
                    val photoView = viewHolder.photoView
                    
                    // Ensure PhotoView is laid out
                    photoView.post {
                        val viewHeight = photoView.height.toFloat()
                        val imageHeight = photoView.drawable?.intrinsicHeight?.toFloat() ?: 0f
                        
                        if (viewHeight > 0 && imageHeight > 0) {
                            // Calculate bubble position in the image
                            val bubbleCenterY = bubbleRect.centerY().toFloat()
                            
                            // Calculate scale to fit image to screen width
                            val scale = photoView.width.toFloat() / photoView.drawable.intrinsicWidth.toFloat()
                            
                            // Pan to show bubble - using setScale with animate=true
                            photoView.setScale(
                                scale,  // Fit to width
                                bubbleRect.centerX().toFloat(),  // X position (bubble center X)
                                bubbleCenterY,  // Y position (bubble center Y) 
                                true  // Animate smoothly
                            )
                        }
                    }
                }
            } else if (attempts < 5) {
                // ViewHolder not ready yet, try again in 100ms
                attempts++
                handler.postDelayed({ tryPan() }, 100)
            }
        }
        
        tryPan()
    }
}
