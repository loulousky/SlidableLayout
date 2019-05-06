package com.yy.mobile.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.view.NestedScrollingChild2
import android.support.v4.view.NestedScrollingChildHelper
import android.support.v4.view.ViewCompat
import android.support.v4.view.ViewCompat.TYPE_NON_TOUCH
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Scroller

/**
 * Created by 张宇 on 2019/4/11.
 * E-mail: zhangyu4@yy.com
 * YY: 909017428
 *
 * 支持上下滑的布局。
 * 使用 [setAdapter] 方法来构造上下滑切换的视图。
 *
 * 可以直接对 [View] 进行上下滑，参考 [SlideAdapter] 或者 [SlideViewAdapter]。
 * 可以对 [Fragment] 进行上下滑，参考 [SlideFragmentAdapter]。
 *
 */
class SlidableLayout : FrameLayout, NestedScrollingChild2 {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val childHelper = NestedScrollingChildHelper(this)

    private val mTouchSlop: Int

    init {
        val configuration = ViewConfiguration.get(context)
        mTouchSlop = configuration.scaledPagingTouchSlop
        isNestedScrollingEnabled = true
    }

    private enum class State(val flag: Int) {
        /**
         * 静止状态
         */
        IDLE(Mask.IDLE),
        /**
         * 正在向下一页拖动
         */
        SLIDE_NEXT(Mask.SLIDE or Mask.NEXT),
        /**
         * 正在向上一页拖动
         */
        SLIDE_PREV(Mask.SLIDE or Mask.PREV),
        /**
         * 无法拖动到下一页
         */
        SLIDE_REJECT_NEXT(Mask.REJECT or Mask.SLIDE or Mask.NEXT),
        /**
         * 无法拖动到上一页
         */
        SLIDE_REJECT_PREV(Mask.REJECT or Mask.SLIDE or Mask.PREV),
        /**
         * 手指离开，惯性滑行到下一页
         */
        FLING_NEXT(Mask.FLING or Mask.NEXT),
        /**
         * 手指离开，惯性滑行到上一页
         */
        FLING_PREV(Mask.FLING or Mask.PREV);

        infix fun satisfy(mask: Int): Boolean =
            flag and mask == mask

        companion object {

            fun of(vararg mask: Int): State {
                val flag = mask.fold(0) { acc, next -> acc or next }
                return values().first { it.flag == flag }
            }
        }
    }

    private object Mask {
        const val IDLE = 0x000001
        const val NEXT = 0x000010
        const val PREV = 0x000100
        const val SLIDE = 0x001000
        const val FLING = 0x010000
        const val REJECT = 0x100000
    }

    private var mState = State.of(Mask.IDLE)

    private val mInflater by lazy { LayoutInflater.from(context) }

    private val mScroller = Scroller(context)
    private var mAnimator: ValueAnimator? = null

    private var mViewHolderDelegate: ViewHolderDelegate<out SlideViewHolder>? = null

    private val mCurrentView: View?
        get() = mViewHolderDelegate?.currentViewHolder?.view

    private val mBackupView: View?
        get() = mViewHolderDelegate?.backupViewHolder?.view

    private var downY = 0f
    private var downX = 0f

    private var mScrollConsumed: IntArray = IntArray(2)
    private var mScrollOffset: IntArray = IntArray(2)

    private val mGestureCallback = object : GestureDetector.SimpleOnGestureListener() {

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent,
            distanceX: Float, distanceY: Float
        ): Boolean {
            if (mState satisfy Mask.FLING) {
                return waitForFling(distanceX, distanceY)
            }
            val topView = mCurrentView ?: return false
            val delegate = mViewHolderDelegate
                ?: return false
            val adapter = delegate.adapter

            var dyFromDownY = e2.y - downY
            var dxFromDownX = e2.x - downX
            val direction = when {
                dyFromDownY < 0 -> SlideDirection.Next
                dyFromDownY > 0 -> SlideDirection.Prev
                else -> SlideDirection.Origin
            }

            val startToMove = mState satisfy Mask.IDLE &&
                Math.abs(dyFromDownY) > 2 * Math.abs(dxFromDownX)
            val changeDirectionToNext = mState satisfy Mask.PREV && dyFromDownY < 0
            val changeDirectionToPrev = mState satisfy Mask.NEXT && dyFromDownY > 0

            var dx = distanceX.toInt()
            var dy = distanceY.toInt()
            if (dispatchNestedPreScroll(dx, dy, mScrollConsumed, mScrollOffset)) {
                dx -= mScrollConsumed[0]
                dy -= mScrollConsumed[1]
                dxFromDownX -= mScrollConsumed[0]
                dyFromDownY -= mScrollConsumed[1]
            }

            if (startToMove) {
                requestParentDisallowInterceptTouchEvent()
            }

            if (startToMove || changeDirectionToNext || changeDirectionToPrev) {
                val directionMask =
                    if (direction == SlideDirection.Next) Mask.NEXT else Mask.PREV

                if (!adapter.canSlideTo(direction)) {
                    mState = State.of(directionMask, Mask.SLIDE, Mask.REJECT)
                } else {
                    mState = State.of(directionMask, Mask.SLIDE)
                    delegate.prepareBackup(direction)
                }
                log("onMove state = $mState, start = $startToMove, " +
                    "changeToNext = $changeDirectionToNext, changeToPrev = $changeDirectionToPrev")
            }
            if (mState satisfy Mask.REJECT) {
                return dispatchNestedScroll(0, 0, dx, dy, mScrollOffset)

            } else if (mState satisfy Mask.SLIDE) {
                val backView = mBackupView ?: return false
                topView.y = dyFromDownY
                backView.y =
                    if (mState satisfy Mask.NEXT) dyFromDownY + measuredHeight
                    else dyFromDownY - measuredHeight
                return dispatchNestedScroll(0, dy, dx, 0, mScrollOffset)
            }
            return false
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent,
            velocityX: Float, velocityY: Float
        ): Boolean {
            log("onFling ${e2.action} vY = $velocityY state = $mState")
            onUp(velocityX, velocityY)
            return true
        }

        fun onUp(velocityX: Float = 0f, velocityY: Float = 0f): Boolean {
            if (!(mState satisfy Mask.SLIDE)) {
                stopNestedScroll()
                return false
            }

            // if state is reject, don't consume the fling.
            val consumedFling = !(mState satisfy Mask.REJECT)
            if (!dispatchNestedPreFling(velocityX, velocityY)) {
                dispatchNestedFling(velocityX, velocityY, consumedFling)
            }
            stopNestedScroll()

            val backView = mBackupView ?: return resetTouch()
            val topView = mCurrentView ?: return resetTouch()
            val delegate = mViewHolderDelegate
                ?: return resetTouch()
            var direction: SlideDirection? = null
            val duration = 250

            if (consumedFling) {
                val currentOffsetY = topView.y.toInt()

                val highSpeed = Math.abs(velocityY) > 1000
                val sameDirection = (mState == State.SLIDE_NEXT && velocityY < 0) ||
                    (mState == State.SLIDE_PREV && velocityY > 0)
                val moveLongDistance = Math.abs(currentOffsetY) > measuredHeight / 3
                if ((highSpeed && sameDirection) || (!highSpeed && moveLongDistance)) { //fling
                    if (mState == State.SLIDE_NEXT) {
                        direction = SlideDirection.Next
                        mScroller.startScroll(0, currentOffsetY, 0,
                            -currentOffsetY - measuredHeight, duration)
                    } else if (mState == State.SLIDE_PREV) {
                        direction = SlideDirection.Prev
                        mScroller.startScroll(0, currentOffsetY, 0,
                            measuredHeight - currentOffsetY, duration)
                    }
                } else { //back to origin
                    direction = SlideDirection.Origin
                    mScroller.startScroll(0, currentOffsetY, 0, -currentOffsetY, duration)
                }
            }

            if (direction != null) { //perform fling animation
                mAnimator?.cancel()
                mAnimator = ValueAnimator.ofFloat(1f).apply {
                    setDuration(duration.toLong())
                    addUpdateListener {
                        if (mScroller.computeScrollOffset()) {
                            val offset = mScroller.currY.toFloat()
                            topView.y = offset
                            backView.y =
                                if (mState == State.FLING_NEXT) offset + measuredHeight
                                else offset - measuredHeight
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {

                        override fun onAnimationCancel(animation: Animator?) =
                            onAnimationEnd(animation)

                        override fun onAnimationEnd(animation: Animator?) {
                            if (direction != SlideDirection.Origin) {
                                delegate.swap()
                            }
                            delegate.onDismissBackup(direction)
                            mState = State.of(Mask.IDLE)
                            if (direction != SlideDirection.Origin) {
                                delegate.onCompleteCurrent(direction)
                            }
                            delegate.finishSlide(direction)
                        }
                    })
                    start()
                }

                val directionMask = if (mState satisfy Mask.NEXT) Mask.NEXT else Mask.PREV
                mState = State.of(directionMask, Mask.FLING)
                return true
            } else {
                return resetTouch()
            }
        }

        private fun resetTouch(): Boolean {
            mState = State.of(Mask.IDLE)
            mBackupView?.let(::removeView)
            return false
        }

        override fun onDown(e: MotionEvent): Boolean {
            downY = e.y
            downX = e.x
            startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
            return true
        }

        private fun waitForFling(dx: Float, dy: Float): Boolean {
            //eat all the dy
            val unconsumedX = dx.toInt()
            val consumedY = dy.toInt()
            if (!dispatchNestedPreScroll(unconsumedX, consumedY, mScrollConsumed,
                    mScrollOffset, TYPE_NON_TOUCH)) {
                dispatchNestedScroll(0, consumedY, unconsumedX, 0,
                    mScrollOffset, TYPE_NON_TOUCH)
            }
            return true
        }
    }
    private val gestureDetector = GestureDetector(context, mGestureCallback)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action and MotionEvent.ACTION_MASK
        if (gestureDetector.onTouchEvent(event)) {
            return true
        } else if (action == MotionEvent.ACTION_UP
            || action == MotionEvent.ACTION_CANCEL) {
            log("onUp $action state = $mState")
            if (mGestureCallback.onUp()) {
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val action = event.action and MotionEvent.ACTION_MASK
        log("onInterceptTouchEvent action = $action, state = $mState")
        var intercept = false

        if (action != MotionEvent.ACTION_MOVE) {
            if (mState != State.IDLE) {
                intercept = true
            }
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = Math.abs(event.y - downY)
                val dx = Math.abs(event.x - downX)
                if (dy > mTouchSlop && dy > 2 * dx) {
                    log("onInterceptTouchEvent requestDisallow")
                    requestParentDisallowInterceptTouchEvent()
                    intercept = true
                }
            }
        }
        return intercept || super.onInterceptTouchEvent(event)
    }

    private fun requestParentDisallowInterceptTouchEvent() {
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun hasNestedScrollingParent() = childHelper.hasNestedScrollingParent()

    override fun hasNestedScrollingParent(type: Int) = childHelper.hasNestedScrollingParent(type)

    override fun isNestedScrollingEnabled() = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int) = childHelper.startNestedScroll(axes)

    override fun startNestedScroll(axes: Int, type: Int) = childHelper.startNestedScroll(axes, type)

    override fun stopNestedScroll(type: Int) = childHelper.stopNestedScroll(type)

    override fun stopNestedScroll() = childHelper.stopNestedScroll()

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed,
        dyUnconsumed, offsetInWindow, type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed,
        dxUnconsumed, dyUnconsumed, offsetInWindow)

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?,
        offsetInWindow: IntArray?, type: Int
    ) = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?,
        offsetInWindow: IntArray?
    ) = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean) =
        childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float) =
        childHelper.dispatchNestedPreFling(velocityX, velocityY)

    private fun log(str: String) = Log.i("SlidableLayout", str)

    fun setAdapter(adapter: SlideAdapter<out SlideViewHolder>) {
        removeAllViews()
        mViewHolderDelegate = ViewHolderDelegate(adapter).apply {
            prepareCurrent(SlideDirection.Origin)
            onCompleteCurrent(SlideDirection.Origin, true)
        }
    }

    private inner class ViewHolderDelegate<ViewHolder : SlideViewHolder>(
        val adapter: SlideAdapter<ViewHolder>
    ) {

        var currentViewHolder: ViewHolder? = null

        var backupViewHolder: ViewHolder? = null

        private fun ViewHolder?.prepare(direction: SlideDirection): ViewHolder {
            val holder = this ?: adapter.onCreateViewHolder(context, this@SlidableLayout, mInflater)
            if (holder.view.parent == null) {
                addView(holder.view, 0)
            }
            adapter.onBindView(holder, direction)
            return holder
        }

        fun prepareCurrent(direction: SlideDirection) =
            currentViewHolder.prepare(direction).also { currentViewHolder = it }

        fun prepareBackup(direction: SlideDirection) =
            backupViewHolder.prepare(direction).also { backupViewHolder = it }

        fun onCompleteCurrent(direction: SlideDirection, isInit: Boolean = false) {
            currentViewHolder?.let {
                if (isInit) {
                    it.view.post {
                        adapter.onViewComplete(it, direction)
                    }
                } else {
                    adapter.onViewComplete(it, direction)
                }
            }
        }

        fun finishSlide(direction: SlideDirection) {
            val visible = currentViewHolder
            val dismiss = backupViewHolder
            if (visible != null && dismiss != null) {
                adapter.finishSlide(dismiss, visible, direction)
            }
        }

        fun onDismissBackup(direction: SlideDirection) {
            backupViewHolder?.let { adapter.onViewDismiss(it, this@SlidableLayout, direction) }
        }

        fun swap() {
            val tmp = currentViewHolder
            currentViewHolder = backupViewHolder
            backupViewHolder = tmp
        }
    }
}

/**
 * 适配 [SlidableLayout] 以及布局中滑动的 [View] 。
 *
 * 假如首次初始化页面【A】，触发的回调是：
 * - onCreateViewHolder(context, inflater)
 * - onViewComplete(viewHolder【A】)
 *
 * 假如从页面【A】滑动下一个页面【B】，触发的回调将会是：
 *
 * - canSlideTo(SlideDirection.Next)
 * - onCreateViewHolder(context, inflater) (如果是首次滑动)
 * - onBindView(viewHolder【B】, SlideDirection.Next)
 * - onViewDismiss(viewHolder【A】, SlideDirection.Next)
 * - onViewComplete(viewHolder【B】)
 * - finishSlide(SlideDirection.Next)
 *
 * 假如再从页面【B】 滑动回上一个页面 【A】，触发的回调是：
 *
 * - canSlideTo(SlideDirection.Prev)
 * - onBindView(viewHolder【A】, SlideDirection.Prev)
 * - onViewDismiss(viewHolder【B】, SlideDirection.Prev)
 * - onViewComplete(viewHolder【A】)
 * - finishSlide(SlideDirection.Prev)
 *
 * 假如从页面【A】试图滑动到页面【B】，但距离或者速度不够，所以放手后回弹到【A】，触发的回调是：
 *
 * - canSlideTo(SlideDirection.Next)
 * - onBindView(viewHolder【B】, SlideDirection.Next)
 * - onViewDismiss(viewHolder【B】, SlideDirection.Next)
 * - finishSlide(SlideDirection.Origin)
 */
interface SlideAdapter<ViewHolder : SlideViewHolder> {

    /**
     * 能否向 [direction] 的方向滑动。
     *
     * @param direction 滑动的方向
     *
     * @return 返回 true 表示可以滑动， false 表示不可滑动
     */
    fun canSlideTo(direction: SlideDirection): Boolean

    /**
     * 创建持有 [View] 的 [SlideViewHolder] 。
     * 一般来说，该方法会在 [SlidableLayout.setAdapter] 方法调用时触发一次，创建当前显示的 [View]，
     * 会在首次开始滑动时触发第二次，创建滑动目标的 [View]。
     */
    fun onCreateViewHolder(context: Context, parent: ViewGroup, inflater: LayoutInflater): ViewHolder

    /**
     * 当 [View] 开始滑动到可见时触发，在这个方法中实现数据和 [View] 的绑定。
     *
     * @param viewHolder 持有 [View] 的 [SlideViewHolder]
     * @param direction  滑动的方向
     */
    fun onBindView(viewHolder: ViewHolder, direction: SlideDirection)

    /**
     * 当 [View] 完全出现时触发。
     * 这个时机可能是 [SlidableLayout.setAdapter] 后 [View] 的第一次初始化，
     * 也可能是完成一次滑动，在 [finishSlide] 后 **而且** 滑到了一个新的 [View]。
     *
     * 也就是说，如果 [finishSlide] 的 [SlideDirection] 是 [SlideDirection.Origin] ，
     * 也就是滑动回弹到本来的界面上，是不会触发 [onViewComplete] 的。
     *
     * 在这个方法中实现当 [View] 第一次完全出现时才做的业务。比如开始播放视频。
     *
     * @param viewHolder 持有 [View] 的 [SlideViewHolder]
     */
    fun onViewComplete(viewHolder: ViewHolder, direction: SlideDirection) {}

    /**
     * 当滑动完成时，离开的 [View] 会触发，在这个方法中实现对 [View] 的清理。
     *
     * @param viewHolder 持有 [View] 的 [SlideViewHolder]
     * @param direction  滑动的方向
     */
    fun onViewDismiss(viewHolder: ViewHolder, parent: ViewGroup, direction: SlideDirection) {}

    /**
     * 当滑动完成时触发。
     *
     * @param direction 滑动的方向
     */
    fun finishSlide(dismissViewHolder: ViewHolder, visibleViewHolder: ViewHolder, direction: SlideDirection) {}
}

open class SlideViewHolder(val view: View)

abstract class SlideViewAdapter : SlideAdapter<SlideViewHolder> {

    /**
     * 创建 [View] 。
     * 一般来说，该方法会在 [SlidableLayout.setAdapter] 方法调用时触发一次，创建当前显示的 [View]，
     * 会在首次开始滑动时触发第二次，创建滑动目标的 [View]。
     */
    protected abstract fun onCreateView(context: Context, parent: ViewGroup, inflater: LayoutInflater): View

    /**
     * 当 [view] 开始滑动到可见时触发，在这个方法中实现数据和 [view] 的绑定。
     *
     * @param direction  滑动的方向
     */
    protected abstract fun onBindView(view: View, direction: SlideDirection)

    /**
     * 当滑动完成时触发。
     *
     * @param direction 滑动的方向
     */
    protected open fun finishSlide(direction: SlideDirection) {}

    /**
     * 当滑动完成时触发。
     *
     * @param direction 滑动的方向
     */
    protected open fun finishSlide(dismissView: View, visibleView: View, direction: SlideDirection) {}

    /**
     * 当滑动完成时，离开的 [view] 会触发，在这个方法中实现对 [view] 的清理。
     *
     * @param direction  滑动的方向
     */
    protected open fun onViewDismiss(view: View, parent: ViewGroup, direction: SlideDirection) {
        parent.removeView(view)
    }

    /**
     * 当 [view] 完全出现时触发。
     * 这个时机可能是 [SlidableLayout.setAdapter] 后 [view] 的第一次初始化，
     * 也可能是完成一次滑动，在 [finishSlide] 后 **而且** 滑到了一个新的 [view]。
     *
     * 也就是说，如果 [finishSlide] 的 [SlideDirection] 是 [SlideDirection.Origin] ，
     * 也就是滑动回弹到本来的界面上，是不会触发 [onViewComplete] 的。
     *
     * 在这个方法中实现当 [view] 第一次完全出现时才做的业务。比如开始播放视频。
     */
    protected open fun onViewComplete(view: View, direction: SlideDirection) {}

    final override fun onCreateViewHolder(context: Context, parent: ViewGroup, inflater: LayoutInflater): SlideViewHolder {
        return SlideViewHolder(onCreateView(context, parent, inflater))
    }

    final override fun onBindView(viewHolder: SlideViewHolder, direction: SlideDirection) {
        val v = viewHolder.view
        onBindView(v, direction)
        if (v is SlidableUI) {
            v.startVisible(direction)
        }
    }

    final override fun onViewDismiss(viewHolder: SlideViewHolder, parent: ViewGroup, direction: SlideDirection) {
        val v = viewHolder.view
        if (v is SlidableUI) {
            v.invisible(direction)
        }
        onViewDismiss(v, parent, direction)
    }

    final override fun onViewComplete(viewHolder: SlideViewHolder, direction: SlideDirection) {
        val v = viewHolder.view
        onViewComplete(v, direction)
        if (v is SlidableUI) {
            v.invisible(direction)
        }
    }

    final override fun finishSlide(dismissViewHolder: SlideViewHolder, visibleViewHolder: SlideViewHolder, direction: SlideDirection) {
        finishSlide(direction)
        finishSlide(dismissViewHolder.view, visibleViewHolder.view, direction)
        if (dismissViewHolder.view is SlidableUI) {
            dismissViewHolder.view.preload(direction)
        }
    }
}

abstract class SlideFragmentAdapter(private val fm: FragmentManager) : SlideAdapter<FragmentViewHolder> {

    private val viewHolderList = mutableListOf<FragmentViewHolder>()

    abstract fun onCreateFragment(context: Context): Fragment

    protected open fun onBindFragment(fragment: Fragment, direction: SlideDirection) {}

    protected open fun finishSlide(direction: SlideDirection) {}

    final override fun onCreateViewHolder(context: Context, parent: ViewGroup, inflater: LayoutInflater): FragmentViewHolder {
        val viewGroup = FrameLayout(context)
        viewGroup.id = ViewCompat.generateViewId()
        val fragment = onCreateFragment(context)
        fm.beginTransaction().add(viewGroup.id, fragment).commitAllowingStateLoss()
        val viewHolder = FragmentViewHolder(viewGroup, fragment)
        viewHolderList.add(viewHolder)
        return viewHolder
    }

    final override fun onBindView(viewHolder: FragmentViewHolder, direction: SlideDirection) {
        val fragment = viewHolder.f
        fm.beginTransaction().show(fragment).commitAllowingStateLoss()
        viewHolder.view.post {
            onBindFragment(fragment, direction)
            if (fragment is SlidableUI) {
                fragment.startVisible(direction)
            }
        }
    }

    final override fun onViewComplete(viewHolder: FragmentViewHolder, direction: SlideDirection) {
        val fragment = viewHolder.f
        fragment.setMenuVisibility(true)
        fragment.userVisibleHint = true
        if (fragment is SlidableUI) {
            fragment.completeVisible(direction)
        }

        viewHolderList.filter { it != viewHolder }.forEach {
            val otherFragment = it.f
            otherFragment.setMenuVisibility(false)
            otherFragment.userVisibleHint = false
        }
    }

    final override fun onViewDismiss(viewHolder: FragmentViewHolder, parent: ViewGroup, direction: SlideDirection) {
        val fragment = viewHolder.f
        fm.beginTransaction().hide(fragment).commitAllowingStateLoss()
        if (fragment is SlidableUI) {
            fragment.invisible(direction)
        }
    }

    final override fun finishSlide(dismissViewHolder: FragmentViewHolder, visibleViewHolder: FragmentViewHolder, direction: SlideDirection) {
        finishSlide(direction)
        if (dismissViewHolder.f is SlidableUI) {
            dismissViewHolder.f.preload(direction)
        }
    }
}

class FragmentViewHolder(v: ViewGroup, val f: Fragment) : SlideViewHolder(v)

enum class SlideDirection {
    /**
     * 滑到下一个
     */
    Next {
        override fun moveTo(index: Int): Int = index + 1
    },
    /**
     * 滑到上一个
     */
    Prev {
        override fun moveTo(index: Int): Int = index - 1
    },
    /**
     * 回到原点
     */
    Origin {
        override fun moveTo(index: Int): Int = index
    };

    /**
     * 计算index的变化
     */
    abstract fun moveTo(index: Int): Int
}

enum class SlideAction {
    /**
     * 正常滑动切换页面
     */
    Slide,
    /**
     * 无法滑动
     */
    Freeze
}

interface SlidableUI {

    /**
     * 滑动开始，页面可见
     */
    fun startVisible(direction: SlideDirection) {}

    /**
     * 滑动结束时，页面完全可见
     */
    fun completeVisible(direction: SlideDirection) {}

    /**
     * 滑动结束时，页面完全不可见
     */
    fun invisible(direction: SlideDirection) {}

    /**
     * 滑动完全结束，可以预加载下一个页面
     */
    fun preload(direction: SlideDirection) {}
}