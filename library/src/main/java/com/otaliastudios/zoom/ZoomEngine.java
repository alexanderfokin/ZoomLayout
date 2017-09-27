package com.otaliastudios.zoom;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.support.annotation.IntDef;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.OverScroller;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * A low level class that listens to touch events and posts zoom and pan updates.
 * The most useful output is a {@link Matrix} that can be used to do pretty much everything,
 * from canvas drawing to View hierarchies translations.
 *
 * Users are required to:
 * - Pass the container view in the constructor
 * - Notify the helper of the content size, using {@link #setContentSize(RectF)}
 * - Pass touch events to {@link #onInterceptTouchEvent(MotionEvent)} and {@link #onTouchEvent(MotionEvent)}
 *
 * This class will try to keep the content centered. It also starts with a "center inside" policy
 * that will apply a base zoom to the content, so that it fits inside the view container.
 */
public final class ZoomEngine implements ViewTreeObserver.OnGlobalLayoutListener/*, View.OnTouchListener*/ {

    private static final String TAG = ZoomEngine.class.getSimpleName();
    private static final Interpolator INTERPOLATOR = new AccelerateDecelerateInterpolator();
    private static final int ANIMATION_DURATION = 200;

    /**
     * An interface to listen for updates in the inner matrix. This will be called
     * typically on animation frames.
     */
    interface Listener {

        /**
         * Notifies that the inner matrix was updated. The passed matrix can be changed,
         * but is not guaranteed to be stable. For a long lasting value it is recommended
         * to make a copy of it using {@link Matrix#set(Matrix)}.
         *
         * @param helper the helper hosting the matrix
         * @param matrix a matrix with the given updates
         */
        void onUpdate(ZoomEngine helper, Matrix matrix);
    }

    private static final int NONE = 0;
    private static final int SCROLLING = 1;
    private static final int PINCHING = 2;
    private static final int ANIMATING = 3;
    private static final int FLINGING = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ NONE, SCROLLING, PINCHING, ANIMATING, FLINGING})
    private @interface Mode {}

    private View mView;
    private Listener mListener;
    private Matrix mMatrix = new Matrix();
    private Matrix mOutMatrix = new Matrix();
    @Mode private int mMode = NONE;
    private float mViewWidth;
    private float mViewHeight;
    private boolean mInitialized;
    private RectF mContentRect = new RectF();
    private RectF mContentBaseRect = new RectF();
    private float mMinZoom = 0.8f;
    private float mMaxZoom = 2.5f;
    private float mZoom = 1f; // Not necessarily equal to the matrix scale.
    private float mBaseZoom; // mZoom * mBaseZoom matches the matrix scale.
    private boolean mOverScrollable = true;
    private boolean mOverPinchable = true;
    private OverScroller mFlingScroller;
    private int[] mTemp = new int[3];

    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mFlingDragDetector;

    /**
     * Constructs an helper instance.
     *
     * @param context a valid context
     * @param container the view hosting the zoomable content
     * @param listener a listener for events
     */
    public ZoomEngine(Context context, View container, Listener listener) {
        mView = container;
        mListener = listener;

        mFlingScroller = new OverScroller(context);
        mScaleDetector = new ScaleGestureDetector(context, new PinchListener());
        mScaleDetector.setQuickScaleEnabled(false);
        mFlingDragDetector = new GestureDetector(context, new FlingScrollListener());
        container.getViewTreeObserver().addOnGlobalLayoutListener(this);
    }

    /**
     * Returns the current matrix. This can be changed from the outside, but is not
     * guaranteed to remain stable.
     *
     * @return the current matrix.
     */
    public Matrix getMatrix() {
        mOutMatrix.set(mMatrix);
        return mOutMatrix;
    }

    private static String ms(@Mode int mode) {
        switch (mode) {
            case NONE: return "NONE";
            case FLINGING: return "FLINGING";
            case SCROLLING: return "SCROLLING";
            case PINCHING: return "PINCHING";
            case ANIMATING: return "ANIMATING";
        }
        return "";
    }

    // Returns true if we should go to that mode.
    private boolean setMode(@Mode int mode) {
        // Log.e(TAG, "setMode: " + ms(mode));
        if (!mInitialized) return false;
        if (mode == mMode) return true;
        int oldMode = mMode;

        switch (oldMode) {
            case FLINGING:
                mFlingScroller.forceFinished(true);
                break;
        }

        switch (mode) {
            case SCROLLING:
                if (oldMode == PINCHING || oldMode == ANIMATING) return false;
                break;
            case FLINGING:
                if (oldMode == ANIMATING) return false;
                break;
            case PINCHING:
                if (oldMode == ANIMATING) return false;
                break;
        }
        mMode = mode;
        return true;
    }

    //region Overscroll

    /**
     * Controls whether the content should be overScrollable.
     * If it is, drag and fling events can scroll the content outside the safe area,
     * then return to safe values.
     *
     * @param overScrollable whether to allow over scrolling
     */
    public void setOverScrollable(boolean overScrollable) {
        mOverScrollable = overScrollable;
    }

    /**
     * Controls whether the content should be overPinchable.
     * If it is, pinch events can change the zoom outside the safe bounds,
     * than return to safe values.
     *
     * @param overPinchable whether to allow over pinching
     */
    public void setOverPinchable(boolean overPinchable) {
        mOverPinchable = overPinchable;
    }

    private int getCurrentOverScroll() {
        float overX = (mViewWidth / 20f) * mZoom;
        float overY = (mViewHeight / 20f) * mZoom;
        return (int) Math.min(overX, overY);
    }

    private float getCurrentOverPinch() {
        return 0.1f * (mMaxZoom - mMinZoom);
    }

    //endregion

    //region Initialize

    @Override
    public void onGlobalLayout() {
        int width = mView.getWidth(); // - mView.getPaddingLeft() - mView.getPaddingRight();
        int height = mView.getHeight(); // - mView.getPaddingTop() - mView.getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        if (width != mViewWidth || height != mViewHeight) {
            mViewWidth = width;
            mViewHeight = height;
            init();
        }
    }

    /**
     * Notifies the helper of the content size (be it a child View, a Bitmap, or whatever else).
     * This is needed for the helper to start working.
     *
     * @param rect the content rect
     */
    public void setContentSize(RectF rect) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        if (!rect.equals(mContentRect)) {
            mContentBaseRect.set(rect);
            mContentRect.set(rect);
            init();
        }
    }

    private void init() {
        if (mContentRect.width() <= 0 || mContentRect.height() <= 0 ||
                mViewWidth <= 0 || mViewHeight <= 0) return;

        // TODO: better behavior if was already initialized.
        if (true) {
            // Auto scale to center-inside.
            float scaleX = mViewWidth / mContentRect.width();
            float scaleY = mViewHeight / mContentRect.height();
            float scale = Math.min(scaleX, scaleY);
            mMatrix.setScale(scale, scale);
            mMatrix.mapRect(mContentRect, mContentBaseRect);
            mZoom = 1f;
            mBaseZoom = scale;

            ensureCurrentTranslationBounds(false);
            dispatchOnMatrix();
        }
        mInitialized = true;
    }

    //endregion

    //region Private helpers

    private void dispatchOnMatrix() {
        if (mListener != null) mListener.onUpdate(this, getMatrix());
    }

    private float ensureScaleBounds(float value, boolean allowOverPinch) {
        float minZoom = mMinZoom;
        float maxZoom = mMaxZoom;
        if (allowOverPinch && mOverPinchable) {
            minZoom -= getCurrentOverPinch();
            maxZoom += getCurrentOverPinch();
        }
        if (value < minZoom) value = minZoom;
        if (value > maxZoom) value = maxZoom;
        return value;
    }

    private void ensureCurrentTranslationBounds(boolean allowOverScroll) {
        float fixX = ensureTranslationBounds(0, true, allowOverScroll);
        float fixY = ensureTranslationBounds(0, false, allowOverScroll);
        if (fixX != 0 || fixY != 0) {
            mMatrix.postTranslate(fixX, fixY);
            mMatrix.mapRect(mContentRect, mContentBaseRect);
        }
    }

    // Checks against the translation value to ensure it is inside our acceptable bounds.
    // If allowOverScroll, overScroll value might be considered to allow "invalid" value.
    private float ensureTranslationBounds(float delta, boolean width, boolean allowOverScroll) {
        float value = width ? getRealPanX() : getRealPanY();
        float viewSize = width ? mViewWidth : mViewHeight;
        float contentSize = width ? mContentRect.width() : mContentRect.height();
        return getTranslationCorrection(value + delta, viewSize, contentSize, allowOverScroll);
    }

    private float getTranslationCorrection(float value, float viewSize, float contentSize, boolean allowOverScroll) {
        int tolerance = (allowOverScroll && mOverScrollable) ? getCurrentOverScroll() : 0;
        float min, max;
        if (contentSize <= viewSize) {
            // If contentSize <= viewSize, we want to stay centered.
            // Need a positive translation, that shows some background.
            min = (viewSize - contentSize) / 2f;
            max = (viewSize - contentSize) / 2f;
        } else {
            // If contentSize is bigger, we just don't want to go outside.
            // Need a negative translation, that hides content.
            min = viewSize - contentSize;
            max = 0;
        }
        min -= tolerance;
        max += tolerance;
        float desired = value;
        if (desired < min) desired = min;
        if (desired > max) desired = max;
        return desired - value;
    }

    //endregion

    //region Touch events and Gesture Listeners

    // Might make these public some day?
    private final static int TOUCH_NO = 0;
    private final static int TOUCH_LISTEN = 1;
    private final static int TOUCH_STEAL = 2;

    /**
     * This is required when the content is a View that has clickable hierarchies inside.
     * If true is returned, implementors should not pass the call to super.
     *
     * @param ev the motion event
     * @return whether we want to intercept the event
     */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return processTouchEvent(ev) > TOUCH_LISTEN;
    }

    /**
     * Process the given touch event.
     * If true is returned, implementors should not pass the call to super.
     *
     * @param ev the motion event
     * @return whether we want to steal the event
     */
    public boolean onTouchEvent(MotionEvent ev) {
        return processTouchEvent(ev) > TOUCH_NO;
    }

    private int processTouchEvent(MotionEvent event) {
        Log.e(TAG, "processTouchEvent: " + "start.");
        if (mMode == ANIMATING) return TOUCH_STEAL;

        boolean result = mScaleDetector.onTouchEvent(event);
        Log.e(TAG, "processTouchEvent: " + "scaleResult: " + result);

        // Pinch detector always returns true. If we actually started a pinch,
        // Don't pass to fling detector.
        if (mMode != PINCHING) {
            result = result | mFlingDragDetector.onTouchEvent(event);
            Log.e(TAG, "processTouchEvent: " + "flingResult: " + result);
        }

        // Detect scroll ends, this appears to be the only way.
        if (mMode == SCROLLING) {
            int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                onScrollEnd();
            }
        }

        if (result && mMode != NONE) {
            Log.e(TAG, "processTouchEvent: " + "returning: " + "TOUCH_STEAL");
            return TOUCH_STEAL;
        } else if (result) {
            Log.e(TAG, "processTouchEvent: " + "returning: " + "TOUCH_LISTEN");
            return TOUCH_LISTEN;
        } else {
            Log.e(TAG, "processTouchEvent: " + "returning: " + "TOUCH_NO");
            setMode(NONE);
            return TOUCH_NO;
        }
    }

    private class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (setMode(PINCHING)) {
                float factor = detector.getScaleFactor();
                float desiredDeltaLeft = -(detector.getFocusX() - mViewWidth / 2f);
                float desiredDeltaTop = -(detector.getFocusY() - mViewHeight / 2f);

                // Reduce the pan strength.
                Log.e(TAG, "onScale: deltaLeft1: " + desiredDeltaLeft + " deltaTop1: " + desiredDeltaTop);
                desiredDeltaLeft /= 4;
                desiredDeltaTop /= 4;

                // Don't pan if we reached the zoom bounds.
                float newZoom = mZoom * factor;
                if (newZoom != ensureScaleBounds(newZoom, true)) {
                    desiredDeltaLeft = 0;
                    desiredDeltaTop = 0;
                }

                // Having both overPinch and overScroll is hard to manage, there are lots of bugs if we do.
                moveTo(newZoom, desiredDeltaLeft, desiredDeltaTop, false, true);
                return true;
            }
            return false;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            if (mOverPinchable) {
                // We might have over pinched. Animate back to reasonable value.
                float zoom = 0f;
                if (getZoom() < mMinZoom) zoom = mMinZoom;
                if (getZoom() > mMaxZoom) zoom = mMaxZoom;
                if (zoom > 0) {
                    animateTo(zoom, 0, 0, false, true);
                    return;
                }
            }
            setMode(NONE);
        }
    }


    private class FlingScrollListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDown(MotionEvent e) {
            return true; // We are interested in the gesture.
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return startFling((int) velocityX, (int) velocityY);
        }


        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (setMode(SCROLLING)) {
                // Allow overScroll. Will be reset in onScrollEnd().
                moveTo(getZoom(), -distanceX, -distanceY, true, false);
                return true;
            }
            return false;
        }
    }

    private void onScrollEnd() {
        if (mOverScrollable) {
            // We might have over scrolled. Animate back to reasonable value.
            float fixX = ensureTranslationBounds(0, true, false);
            float fixY = ensureTranslationBounds(0, false, false);
            if (fixX != 0 || fixY != 0) {
                animateTo(getZoom(), fixX, fixY, true, false);
                return;
            }
        }
        setMode(NONE);
    }

    //endregion

    //region Position APIs

    /**
     * Pans the content until the top-left coordinates match the given x-y
     * values. These are referred to the content size passed in {@link #setContentSize(RectF)},
     * so they do not depend on current zoom.
     *
     * @param x the desired top coordinate
     * @param y the desired left coordinate
     * @param animate whether to animate the transition
     */
    public void panTo(float x, float y, boolean animate) {
        panBy(x - getPanX(), y - getPanY(), animate);
    }

    /**
     * Pans the content by the given quantity in dx-dy values.
     * These are referred to the content size passed in {@link #setContentSize(RectF)},
     * so they do not depend on current zoom.
     *
     * In other words, asking to pan by 1 pixel might result in a bigger pan, if the content
     * was zoomed in.
     *
     * @param dx the desired delta x
     * @param dy the desired delta y
     * @param animate whether to animate the transition
     */
    public void panBy(float dx, float dy, boolean animate) {
        dx *= getRealZoom();
        dy *= getRealZoom();
        if (!mInitialized) return;
        if (animate) {
            animateTo(mZoom, dx, dy, false, false);
        } else {
            moveTo(mZoom, dx, dy, false, false);
        }
    }

    /**
     * Zooms to the given scale. This might not be the actual matrix zoom,
     * see {@link #getZoom()} and {@link #getRealZoom()}.
     *
     * @param zoom the new scale value
     * @param animate whether to animate the transition
     */
    public void zoomTo(float zoom, boolean animate) {
        if (!mInitialized) return;
        if (animate) {
            animateTo(zoom, 0, 0, false, false);
        } else {
            moveTo(zoom, 0, 0, false, false);
        }
    }

    /**
     * Applies the given factor to the current zoom.
     *
     * @param zoomFactor a multiplicative factor
     * @param animate whether to animate the transition
     */
    public void zoomBy(float zoomFactor, boolean animate) {
        zoomTo(mZoom * zoomFactor, animate);
    }

    /**
     * Which is the max zoom that should be allowed.
     * Should be greater than (or equal to) 1 and anyway greater than (or equal to) min zoom.
     * If {@link #setOverPinchable(boolean)} is set to true, this can be over-pinched
     * for a brief time.
     *
     * @see #getZoom()
     * @see #getRealZoom()
     * @param maxZoom the max zoom
     */
    public void setMaxZoom(float maxZoom) {
        if (maxZoom < 1 || maxZoom < mMinZoom) {
            throw new IllegalArgumentException("Max zoom should be >= 1 and >= min zoom.");
        }
        mMaxZoom = maxZoom;
        if (mZoom > maxZoom) {
            zoomTo(maxZoom, true);
        }
    }

    /**
     * Which is the min zoom that should be allowed.
     * Should be smaller than (or equal to) the current max zoom.
     * If {@link #setOverPinchable(boolean)} is set to true, this can be over-pinched
     * for a brief time.
     *
     * @see #getZoom()
     * @see #getRealZoom()
     * @param minZoom the min zoom
     */
    public void setMinZoom(float minZoom) {
        if (minZoom > mMaxZoom) {
            throw new IllegalArgumentException("Min zoom should be < max zoom.");
        }
        mMinZoom = minZoom;
        if (mZoom <= minZoom) {
            zoomTo(minZoom, true);
        }
    }

    /**
     * Gets the current zoom value, which can be used as a reference when calling
     * {@link #zoomTo(float, boolean)} or {@link #zoomBy(float, boolean)}.
     *
     * This can be different than the actual scale you get in the matrix, because at startup
     * we apply a base zoom to respect the "center inside" policy.
     * All zoom calls, including min zoom and max zoom, refer to this axis, where zoom is set to 1
     * right after the initial transformation.
     *
     * @see #getRealZoom()
     * @return the current zoom
     */
    public float getZoom() {
        return mZoom;
    }

    /**
     * Gets the current zoom value, including the base zoom that was eventually applied when
     * initializing to respect the "center inside" policy. This will match the scaleX - scaleY
     * values you get into the {@link Matrix}, and is the actual scale value of the content
     * from its original size.
     *
     * @return the real zoom
     */
    public float getRealZoom() {
        return mZoom * mBaseZoom;
    }

    /**
     * Returns the current horizontal pan value, in content coordinates
     * (that is, as if there was no zoom at all) referring to what was passed
     * to {@link #setContentSize(RectF)}.
     *
     * @return the current horizontal pan
     */
    public float getPanX() {
        return getRealPanX() / getRealZoom();
    }

    /**
     * Returns the current vertical pan value, in content coordinates
     * (that is, as if there was no zoom at all) referring to what was passed
     * to {@link #setContentSize(RectF)}.
     *
     * @return the current vertical pan
     */
    public float getPanY() {
        return getRealPanY() / getRealZoom();
    }

    private float getRealPanX() {
        return mContentRect.left;
    }

    private float getRealPanY() {
        return mContentRect.top;
    }

    private void animateTo(float newZoom, final float deltaX, float deltaY,
                           final boolean allowOverScroll, final boolean allowOverPinch) {
        newZoom = ensureScaleBounds(newZoom, allowOverScroll);
        if (setMode(ANIMATING)) {
            final long startTime = System.currentTimeMillis();
            final float startZoom = mZoom;
            final float endZoom = newZoom;
            final float startX = getRealPanX();
            final float startY = getRealPanY();
            final float endX = startX + deltaX;
            final float endY = startY + deltaY;
            mView.post(new Runnable() {
                @Override
                public void run() {
                    float time = interpolateAnimationTime(startTime);
                    float zoom = startZoom + time * (endZoom - startZoom);
                    float x = startX + time * (endX - startX);
                    float y = startY + time * (endY - startY);
                    moveTo(zoom, x - getRealPanX(), y - getRealPanY(), allowOverScroll, allowOverPinch);
                    if (time < 1f) {
                        mView.postOnAnimation(this);
                    } else {
                        setMode(NONE);
                    }
                }
            });
        }
    }

    // TODO: if scale AND delta, the center scale will invalidate deltas?
    private void moveTo(float newZoom, float deltaX, float deltaY, boolean allowOverScroll, boolean allowOverPinch) {
        // Translation
        mMatrix.postTranslate(deltaX, deltaY);
        mMatrix.mapRect(mContentRect, mContentBaseRect);

        // Scale
        newZoom = ensureScaleBounds(newZoom, allowOverPinch);
        float scaleFactor = newZoom / mZoom;
        mMatrix.postScale(scaleFactor, scaleFactor, mViewWidth / 2f, mViewHeight / 2f);
        mZoom = newZoom;

        ensureCurrentTranslationBounds(allowOverScroll);
        dispatchOnMatrix();
    }

    private float interpolateAnimationTime(long startTime) {
        float time = ((float) (System.currentTimeMillis() - startTime)) / (float) ANIMATION_DURATION;
        time = Math.min(1f, time);
        time = INTERPOLATOR.getInterpolation(time);
        return time;
    }

    //endregion

    //region Fling

    // Puts min, start and max values in the mTemp array.
    // Since axes are shifted (pans are negative), min values are related to bottom-right,
    // while max values are related to top-left.
    private boolean computeScrollerValues(boolean width) {
        int currentPan = (int) (width ? getRealPanX() : getRealPanY());
        int viewDim = (int) (width ? mViewWidth : mViewHeight);
        int contentDim = (int) (width ? mContentRect.width() : mContentRect.height());
        int fix = (int) ensureTranslationBounds(0, width, false);
        if (viewDim >= contentDim) {
            // Content is smaller, we are showing some boundary.
            // We can't move in any direction (but we can overScroll).
            mTemp[0] = currentPan + fix;
            mTemp[1] = currentPan;
            mTemp[2] = currentPan + fix;
        } else {
            // Content is bigger, we can move.
            // in this case minPan + viewDim = contentDim
            mTemp[0] = -(contentDim - viewDim);
            mTemp[1] = currentPan;
            mTemp[2] = 0;
        }
        return fix != 0;
    }

    private boolean startFling(int velocityX, int velocityY) {
        if (!setMode(FLINGING)) return false;

        // Using actual pan values for the scroller.
        // Note: these won't make sense if zoom changes.
        boolean overScrolled;
        overScrolled = computeScrollerValues(true);
        int minX = mTemp[0];
        int startX = mTemp[1];
        int maxX = mTemp[2];
        overScrolled = overScrolled | computeScrollerValues(false);
        int minY = mTemp[0];
        int startY = mTemp[1];
        int maxY = mTemp[2];

        boolean go = overScrolled || mOverScrollable || minX < maxX || minY < maxY;
        if (!go) return false;

        int overScroll = mOverScrollable ? getCurrentOverScroll() : 0;
        // Log.e(TAG, "flingX: min:" + minX + " max:" + maxX + " start:" + startX + " overscroll:" + overScroll);
        // Log.e(TAG, "flingY: min:" + minY + " max:" + maxY + " start:" + startY + " overscroll:" + overScroll);
        mFlingScroller.fling(startX, startY, velocityX, velocityY,
                minX, maxX, minY, maxY,
                overScroll, overScroll);

        mView.post(new Runnable() {
            @Override
            public void run() {
                if (mFlingScroller.isFinished()) {
                    setMode(NONE);
                } else if (mFlingScroller.computeScrollOffset()) {
                    final int newPanX = mFlingScroller.getCurrX();
                    final int newPanY = mFlingScroller.getCurrY();
                    // OverScroller will eventually go back to our bounds.
                    moveTo(getZoom(), newPanX - getRealPanX(), newPanY - getRealPanY(), true, false);
                    mView.postOnAnimation(this);
                }
            }
        });
        return true;
    }

    //endregion
}