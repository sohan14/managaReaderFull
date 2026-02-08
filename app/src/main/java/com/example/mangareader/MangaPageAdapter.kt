package com.example.mangareader

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
        
        // Load the manga page image
        val bitmap = try {
            BitmapFactory.decodeFile(page.imagePath)
        } catch (e: Exception) {
            // Try assets
            try {
                holder.itemView.context.assets.open(page.imagePath).use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: Exception) {
                null
            }
        }
        
        if (bitmap != null) {
            // Set zoom limits for webtoon viewing
            holder.photoView.maximumScale = 5.0f
            holder.photoView.mediumScale = 2.5f
            holder.photoView.minimumScale = 1.0f
            
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
}
