package com.example.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mangareader.model.MangaPage
import com.example.mangareader.utils.ReaderImageEngine
import com.github.chrisbanes.photoview.PhotoView

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
        
        // New v2 image pipeline: centralized decode + safe scaling
        val bitmap = ReaderImageEngine
            .loadScaledBitmap(holder.itemView.context, page.imagePath)
            ?.bitmap
        
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
     * Scroll RecyclerView to show bubble position for continuous webtoon
     */
    fun scrollToBubble(pageIndex: Int, bubbleIndex: Int, recyclerView: androidx.recyclerview.widget.RecyclerView) {
        val page = pages.getOrNull(pageIndex) ?: return
        val bubble = page.speechBubbles.getOrNull(bubbleIndex) ?: return
        
        recyclerView.post {
            // Calculate where the bubble is in the full image
            val bubbleY = bubble.boundingBox.centerY()
            
            // Get screen height to center bubble
            val screenHeight = recyclerView.height
            
            // Calculate target scroll position (center bubble on screen)
            val targetScrollY = bubbleY - (screenHeight / 2)
            
            // Get current scroll position
            val currentScrollY = recyclerView.computeVerticalScrollOffset()
            
            // Calculate how much we need to scroll
            val scrollAmount = targetScrollY - currentScrollY
            
            // Smooth scroll to the bubble
            recyclerView.smoothScrollBy(0, scrollAmount)
            
            android.util.Log.d("MangaPageAdapter", "Scrolling to bubble at Y=$bubbleY (center at $targetScrollY), current=$currentScrollY, scrolling by $scrollAmount")
        }
    }
}
