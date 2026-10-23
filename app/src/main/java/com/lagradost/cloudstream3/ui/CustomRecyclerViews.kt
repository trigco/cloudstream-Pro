package com.lagradost.cloudstream3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.withStyledAttributes
import androidx.core.view.children
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class GrdLayoutManager(val context: Context, spanCount: Int) :
    GridLayoutManager(context, spanCount) {
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        return try {
            val fromPos = getPosition(focused)
            val nextPos = getNextViewPos(fromPos, focusDirection)
            findViewByPosition(nextPos)
        } catch (e: Exception) {
            null
        }
    }

    /*override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        // android.widget.FrameLayout$LayoutParams cannot be cast to androidx.recyclerview.widget.RecyclerView$LayoutParams
        return try {
            if(focused != null) {
              //  val pos = maxOf(0, getPosition(focused) - 2) // IDK WHY
                val pos = getPosition(focused)
                if(pos >= 0) parent.scrollToPosition(pos)
            }

            super.onRequestChildFocus(parent, state, child, focused)
        } catch (e: Exception) {
            false
        }
    }*/

    // Allows moving right and left with focus https://gist.github.com/vganin/8930b41f55820ec49e4d
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        return try {
            val fromPos = getPosition(focused)
            val nextPos = getNextViewPos(fromPos, direction)
            findViewByPosition(nextPos)
        } catch (e: Exception) {
            null
        }
    }

    private fun getNextViewPos(fromPos: Int, direction: Int): Int {
        val offset = calcOffsetToNextView(direction)

        if (hitBorder(fromPos, offset)) {
            return fromPos
        }

        return fromPos + offset
    }

    private fun calcOffsetToNextView(direction: Int): Int {
        val spanCount = this.spanCount
        val orientation = this.orientation

        // fixes arabic by inverting left and right layout focus
        val correctDirection = if (this.isLayoutRTL) {
            when (direction) {
                View.FOCUS_RIGHT -> View.FOCUS_LEFT
                View.FOCUS_LEFT -> View.FOCUS_RIGHT
                else -> direction
            }
        } else direction

        if (orientation == VERTICAL) {
            when (correctDirection) {
                View.FOCUS_DOWN -> {
                    return spanCount
                }

                View.FOCUS_UP -> {
                    return -spanCount
                }

                View.FOCUS_RIGHT -> {
                    return 1
                }

                View.FOCUS_LEFT -> {
                    return -1
                }
            }
        } else if (orientation == HORIZONTAL) {
            when (correctDirection) {
                View.FOCUS_DOWN -> {
                    return 1
                }

                View.FOCUS_UP -> {
                    return -1
                }

                View.FOCUS_RIGHT -> {
                    return spanCount
                }

                View.FOCUS_LEFT -> {
                    return -spanCount
                }
            }
        }
        return 0
    }

    private fun hitBorder(from: Int, offset: Int): Boolean {
        val spanCount = spanCount

        return if (abs(offset) == 1) {
            val spanIndex = from % spanCount
            val newSpanIndex = spanIndex + offset
            newSpanIndex < 0 || newSpanIndex >= spanCount
        } else {
            val newPos = from + offset
            newPos in spanCount..-1
        }
    }
}

// https://riptutorial.com/android/example/4810/gridlayoutmanager-with-dynamic-span-count
class AutofitRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RecyclerView(context, attrs) {

    private val manager = GrdLayoutManager(context, 2) // THIS CONTROLS SPANS

    private var columnWidth = -1

    var spanCount = 0
        set(value) {
            field = value
            if (value > 0) {
                manager.spanCount = value
            }
        }

    val itemWidth: Int
        get() = measuredWidth / manager.spanCount

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, intArrayOf(android.R.attr.columnWidth)) {
                columnWidth = getDimensionPixelSize(0, -1)
            }
        }

        layoutManager = manager
    }
}

/**
 * Recyclerview wherein the max item width or height is set by the biggest view to prevent inconsistent view sizes.
 */
class MaxRecyclerView(ctx: Context, attrs: AttributeSet) : RecyclerView(ctx, attrs) {
    private var biggestObserved: Int = 0
    private val orientation = LayoutManager.getProperties(context, attrs, 0, 0).orientation
    private val isHorizontal = orientation == HORIZONTAL
    private fun View.updateMaxSize() {
        if (isHorizontal) {
            this.minimumHeight = biggestObserved
        } else {
            this.minimumWidth = biggestObserved
        }
    }

    override fun onChildAttachedToWindow(child: View) {
        child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val observed = if (isHorizontal) child.measuredHeight else child.measuredWidth
        if (observed > biggestObserved) {
            biggestObserved = observed
            children.forEach { it.updateMaxSize() }
        } else {
            child.updateMaxSize()
        }
        super.onChildAttachedToWindow(child)
    }
}

/**
 * Phase 8.10: scroll-in entrance for provider section items (used by the
 * Home and Search master lists, phone only — the fragments gate the call).
 *
 * A [RecyclerView.LayoutAnimationController] only runs on a list's first
 * layout pass, so sections that arrive or scroll in later would just pop.
 * This listener animates an item the first time it attaches at a position
 * beyond everything seen so far: a 220ms fade + slight rise (decelerate —
 * the timings of `@anim/settings_item_enter` / the `feel_*` resources),
 * transform/alpha only, so it stays smooth on API 27 and never triggers
 * a layout pass.
 *
 * Recycling safety:
 * - Scrolling back up never re-animates: re-attached items sit at or below
 *   [lastAnimatedPosition].
 * - A data observer resets the tracker when a fresh list starts (insert
 *   or removal at the first animated position, or a wholesale change),
 *   so a new search / provider reload animates its sections again, while
 *   plain appends further down keep the tracker. (The comparison uses
 *   [firstAnimatedPosition] because BaseAdapter offsets its differ
 *   events by its header count.)
 * - Views are normalised on detach (and before the animate decision on
 *   attach), so a recycled view can never be reused mid-animation with a
 *   stale alpha/translation.
 * - The list's own XML layoutAnimation covers the very first layout pass;
 *   items attached before that pass completes are marked seen instead of
 *   being animated twice.
 *
 * [firstAnimatedPosition] lets Home skip its header (position 0), whose
 * hero has its own scroll-driven treatment; views below it are never
 * touched, on attach or detach.
 */
class SectionEntranceAnimator(
    private val firstAnimatedPosition: Int = 0,
) : RecyclerView.OnChildAttachStateChangeListener {

    private var lastAnimatedPosition = firstAnimatedPosition - 1
    private var firstLayoutPassed = false

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (positionStart <= firstAnimatedPosition) reset()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            if (positionStart <= firstAnimatedPosition) reset()
        }

        override fun onChanged() {
            reset()
        }
    }

    private fun reset() {
        lastAnimatedPosition = firstAnimatedPosition - 1
    }

    /** Registers this listener (and the data observer) on [recyclerView]. */
    fun attachTo(recyclerView: RecyclerView) {
        recyclerView.addOnChildAttachStateChangeListener(this)
        recyclerView.adapter?.registerAdapterDataObserver(dataObserver)
        // Posted runnables run after the current layout pass, so anything
        // attached by the first pass is covered by the list's own
        // layoutAnimation and only marked as seen.
        recyclerView.post { firstLayoutPassed = true }
    }

    override fun onChildViewAttachedToWindow(view: View) {
        val recyclerView = view.parent as? RecyclerView ?: return
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position < firstAnimatedPosition) return

        if (position > lastAnimatedPosition) lastAnimatedPosition = position
        else return // already seen: scrolling back up, or a rebind

        view.animate().cancel()
        if (!firstLayoutPassed) {
            // First layout pass: the XML layoutAnimation owns the entrance.
            view.alpha = 1f
            view.translationY = 0f
            return
        }

        val rise = if (view.height > 0) view.height * 0.06f
        else 16f * view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = rise
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTRANCE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onChildViewDetachedFromWindow(view: View) {
        val recyclerView = view.parent as? RecyclerView ?: return
        val position = recyclerView.getChildAdapterPosition(view)
        if (position != RecyclerView.NO_POSITION && position < firstAnimatedPosition) return
        // Cancel + normalise so the view is never recycled mid-animation.
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }

    private companion object {
        const val ENTRANCE_DURATION_MS = 220L
    }
}
