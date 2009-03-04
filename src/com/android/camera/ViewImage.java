/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import java.util.Random;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Config;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.Scroller;
import android.widget.Toast;
import android.widget.ZoomButtonsController;
import android.widget.ZoomRingController;

import com.android.camera.ImageManager.IImage;

public class ViewImage extends Activity implements View.OnClickListener
{
    static final String TAG = "ViewImage";
    private ImageGetter mGetter;

    static final boolean sSlideShowHidesStatusBar = true;

    // Choices for what adjacents to load.
    static private final int[] sOrder_adjacents = new int[] { 0, 1, -1 };
    static private final int[] sOrder_slideshow = new int[] { 0 };

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
        }
    };

    private Random mRandom = new Random(System.currentTimeMillis());
    private int [] mShuffleOrder;
    private boolean mUseShuffleOrder = false;
    private boolean mSlideShowLoop = false;

    private static final int MODE_NORMAL = 1;
    private static final int MODE_SLIDESHOW = 2;
    private int mMode = MODE_NORMAL;
    private boolean mFullScreenInNormalMode;
    private boolean mShowActionIcons;
    private View mActionIconPanel;
    private View mShutterButton;

    private boolean mSortAscending = false;
    private int mSlideShowInterval;
    private int mLastSlideShowImage;
    private boolean mFirst = true;
    private int mCurrentPosition = 0;
    private boolean mLayoutComplete = false;

    // represents which style animation to use
    private int mAnimationIndex;
    private Animation [] mSlideShowInAnimation;
    private Animation [] mSlideShowOutAnimation;

    private SharedPreferences mPrefs;

    private View mNextImageView, mPrevImageView;
    private Animation mHideNextImageViewAnimation = new AlphaAnimation(1F, 0F);
    private Animation mHidePrevImageViewAnimation = new AlphaAnimation(1F, 0F);
    private Animation mShowNextImageViewAnimation = new AlphaAnimation(0F, 1F);
    private Animation mShowPrevImageViewAnimation = new AlphaAnimation(0F, 1F);


    static final int sPadding = 20;
    static final int sHysteresis = sPadding * 2;
    static final int sBaseScrollDuration = 1000; // ms

    private ImageManager.IImageList mAllImages;

    private int mSlideShowImageCurrent = 0;
    private ImageViewTouch [] mSlideShowImageViews = new ImageViewTouch[2];


    // Array of image views.  The center view is the one the user is focused
    // on.  The one at the zeroth position and the second position reflect
    // the images to the left/right of the center image.
    private ImageViewTouch[] mImageViews = new ImageViewTouch[3];

    // Container for the three image views.  This guy can be "scrolled"
    // to reveal the image prior to and after the center image.
    private ScrollHandler mScroller;

    private MenuHelper.MenuItemsResult mImageMenuRunnable;

    private Runnable mDismissOnScreenControlsRunnable;
    private boolean mCameraReviewMode;

    private int mCurrentOrientation;


    public ViewImage() {
    }

    private void updateNextPrevControls() {
        boolean showPrev =  mCurrentPosition > 0;
        boolean showNext = mCurrentPosition < mAllImages.getCount() - 1;

        boolean prevIsVisible = mPrevImageView.getVisibility() == View.VISIBLE;
        boolean nextIsVisible = mNextImageView.getVisibility() == View.VISIBLE;

        if (showPrev && !prevIsVisible) {
            Animation a = mShowPrevImageViewAnimation;
            a.setDuration(500);
            a.startNow();
            mPrevImageView.setAnimation(a);
            mPrevImageView.setVisibility(View.VISIBLE);
        } else if (!showPrev && prevIsVisible) {
            Animation a = mHidePrevImageViewAnimation;
            a.setDuration(500);
            a.startNow();
            mPrevImageView.setAnimation(a);
            mPrevImageView.setVisibility(View.GONE);
        }

        if (showNext && !nextIsVisible) {
            Animation a = mShowNextImageViewAnimation;
            a.setDuration(500);
            a.startNow();
            mNextImageView.setAnimation(a);
            mNextImageView.setVisibility(View.VISIBLE);
        } else if (!showNext && nextIsVisible) {
            Animation a = mHideNextImageViewAnimation;
            a.setDuration(500);
            a.startNow();
            mNextImageView.setAnimation(a);
            mNextImageView.setVisibility(View.GONE);
        }
    }

    private void showOnScreenControls() {
        updateNextPrevControls();
        scheduleDismissOnScreenControls();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent m) {
        boolean sup = super.dispatchTouchEvent(m);
        if (sup == false) {
            if (mMode == MODE_SLIDESHOW) {
                mSlideShowImageViews[mSlideShowImageCurrent].handleTouchEvent(m);
            } else if (mMode == MODE_NORMAL){
                mImageViews[1].handleTouchEvent(m);
            }
            return true;
        }
        return true;
    }

    private void scheduleDismissOnScreenControls() {
        mHandler.removeCallbacks(mDismissOnScreenControlsRunnable);
        mHandler.postDelayed(mDismissOnScreenControlsRunnable, 1500);
    }

    public void setupDismissOnScreenControlRunnable() {
        mDismissOnScreenControlsRunnable = new Runnable() {
            public void run() {
                if (!mShowActionIcons) {
                    if (mNextImageView.getVisibility() == View.VISIBLE) {
                        Animation a = mHideNextImageViewAnimation;
                        a.setDuration(500);
                        a.startNow();
                        mNextImageView.setAnimation(a);
                        mNextImageView.setVisibility(View.INVISIBLE);
                    }

                    if (mPrevImageView.getVisibility() == View.VISIBLE) {
                        Animation a = mHidePrevImageViewAnimation;
                        a.setDuration(500);
                        a.startNow();
                        mPrevImageView.setAnimation(a);
                        mPrevImageView.setVisibility(View.INVISIBLE);
                    }
                }
            }
        };
    }

    private boolean isPickIntent() {
        String action = getIntent().getAction();
        return (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action));
    }

    private static final boolean sUseBounce = false;
    private static final boolean sAnimateTransitions = false;

    static public class ImageViewTouch extends ImageViewTouchBase {
        private ViewImage mViewImage;
        private boolean mEnableTrackballScroll;
        private GestureDetector mGestureDetector;

        private static int TOUCH_AREA_WIDTH = 60;
        
        // The zoom ring setup:
        // We limit the thumb on the zoom ring in the range 0 to 5/3*PI. The
        // last PI/3 of the ring is left as a region that the thumb can't go in.
        // The 5/3*PI range is divided into 60 steps. Each step scales the image
        // by mScaleRate. We make mScaleRate^60 = maxZoom().
        
        // This is the max step value we can have for the zoom ring.
        private static int MAX_STEP = 60;
        // This is the angle we used to separate each step.
        private static float STEP_ANGLE = (5 * (float) Math.PI / 3) / MAX_STEP;
        // The scale rate for each step.
        private float mScaleRate;

        // Returns current scale step (numbered from 0 to MAX_STEP).
        private int getCurrentStep() {
            float s = getScale();
            float b = mScaleRate;
            int step = (int)Math.round(Math.log(s) / Math.log(b));
            return Math.max(0, Math.min(MAX_STEP, step));
        }

        // Limit the thumb on the zoom ring in the range 0 to 5/3*PI. (clockwise
        // angle is negative, and we need to mod 2*PI for the API to work.)
        private void setZoomRingBounds() {
            mScaleRate = (float) Math.pow(maxZoom(), 1.0 / MAX_STEP);
            float limit = (2 - 5 / 3F) * (float) Math.PI;
            mZoomRingController.setThumbClockwiseBound(limit);
            mZoomRingController.setThumbCounterclockwiseBound(0);
        }

        private ZoomButtonsController mZoomButtonsController;
        
        // The zoom ring is set to visible by a double tap.
        private ZoomRingController mZoomRingController;
        private ZoomRingController.OnZoomListener mZoomListener =
                new ZoomRingController.OnZoomListener() {
            public void onCenter(int x, int y) {
            }

            public void onBeginPan() {
            }

            public boolean onPan(int deltaX, int deltaY) {
                postTranslate(-deltaX, -deltaY, sUseBounce);
                ImageViewTouch.this.center(true, true, false);
                return true;
            }

            public void onEndPan() {
            }

            // The clockwise angle is negative, so we need to mod 2*PI
            private float stepToAngle(int step) {
                float angle = step * STEP_ANGLE;
                angle = (float) Math.PI * 2 - angle;
                return angle;
            }

            private int angleToStep(double angle) {
                angle = Math.PI * 2 - angle;
                int step = (int)Math.round(angle / STEP_ANGLE);
                return step;
            }

            public void onVisibilityChanged(boolean visible) {
                if (visible) {
                    int step = getCurrentStep();
                    float angle = stepToAngle(step);
                    mZoomRingController.setThumbAngle(angle);
                }
            }

            public void onBeginDrag() {
                setZoomRingBounds();
            }

            public void onEndDrag() {
            }

            public boolean onDragZoom(int deltaZoomLevel, int centerX,
                                      int centerY, float startAngle, float curAngle) {
                setZoomRingBounds();
                int deltaStep = angleToStep(curAngle) - getCurrentStep();
                if ((deltaZoomLevel > 0) && (deltaStep < 0)) return false;
                if ((deltaZoomLevel < 0) && (deltaStep > 0)) return false;
                if ((deltaZoomLevel == 0) || (deltaStep == 0)) return false;

                float oldScale = getScale();

                // First move centerX/centerY to the center of the view.
                int deltaX = getWidth() / 2 - centerX;
                int deltaY = getHeight() / 2 - centerY;
                panBy(deltaX, deltaY);

                // Do zoom in/out.                
                if (deltaStep > 0) {
                    zoomIn((float) Math.pow(mScaleRate, deltaStep));
                } else if (deltaStep < 0) {
                    zoomOut((float) Math.pow(mScaleRate, -deltaStep));
                }

                // Reverse the first centering.
                panBy(-deltaX, -deltaY);

                // Return true if the zoom succeeds.
                return (oldScale != getScale());
            }

            public void onSimpleZoom(boolean zoomIn) {
                if (zoomIn) zoomIn();
                else zoomOut();
            }
        };

        public ImageViewTouch(Context context) {
            super(context);
            setup(context);
        }

        public ImageViewTouch(Context context, AttributeSet attrs) {
            super(context, attrs);
            setup(context);
        }

        private void setup(Context context) {
            mViewImage = (ViewImage) context;
            mZoomRingController = new ZoomRingController(context, this);
            mZoomRingController.setVibration(false);
            mZoomRingController.setZoomCallbackThreshold(STEP_ANGLE);
            mZoomRingController.setResetThumbAutomatically(false);
            mZoomRingController.setCallback(mZoomListener);
            mGestureDetector = new GestureDetector(getContext(), new MyGestureListener());
            mGestureDetector.setOnDoubleTapListener(new MyDoubleTapListener());
            mZoomButtonsController = new ZoomButtonsController(context, this);
            mZoomButtonsController.setOverviewVisible(false);
            mZoomButtonsController.setCallback(new ZoomButtonsController.OnZoomListener() {

                public void onCenter(int x, int y) {
                    mZoomListener.onCenter(x, y);
                }

                public void onOverview() {
                }

                public void onVisibilityChanged(boolean visible) {
                    mZoomListener.onVisibilityChanged(visible);
                }

                public void onZoom(boolean zoomIn) {
                    mZoomListener.onSimpleZoom(zoomIn);
                }
            });
        }

        public void setEnableTrackballScroll(boolean enable) {
            mEnableTrackballScroll = enable;
        }

        protected void postTranslate(float dx, float dy, boolean bounceOK) {
            super.postTranslate(dx, dy);
            if (dx != 0F || dy != 0F)
                mViewImage.showOnScreenControls();

            if (!sUseBounce) {
                center(true, false, false);
            }
        }

        protected ScrollHandler scrollHandler() {
            return mViewImage.mScroller;
        }

        public boolean handleTouchEvent(MotionEvent m) {
            return mGestureDetector.onTouchEvent(m);
        }

        private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
            public boolean onScroll(MotionEvent e1, MotionEvent e2,
                    float distanceX, float distanceY) {
                if (getScale() > 1F) {
                    postTranslate(-distanceX, -distanceY, sUseBounce);
                    ImageViewTouch.this.center(true, true, false);
                }
                return true;
            }
        }

        private class MyDoubleTapListener implements GestureDetector.OnDoubleTapListener {
            // On single tap, we show the arrows. We also change to the
            // prev/next image if the user taps on the left/right region.
            public boolean onSingleTapConfirmed(MotionEvent e) {
                ViewImage viewImage = mViewImage;

                int viewWidth = getWidth();
                int x = (int) e.getX();
                int y = (int) e.getY();
                if (x < TOUCH_AREA_WIDTH) {
                    viewImage.moveNextOrPrevious(-1);
                } else if (x > viewWidth - TOUCH_AREA_WIDTH) {
                    viewImage.moveNextOrPrevious(1);
                }

                viewImage.setMode(MODE_NORMAL);
                viewImage.showOnScreenControls();

                return true;
            }

            // On double tap, we show the zoom ring control.
            public boolean onDoubleTapEvent(MotionEvent e) {
                mViewImage.setMode(MODE_NORMAL);
                mZoomRingController.handleDoubleTapEvent(e);
                mZoomButtonsController.handleDoubleTapEvent(e);
                return true;
            }

            public boolean onDoubleTap(MotionEvent e) {
                return false;
            }

        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event)
        {
            // Don't respond to arrow keys if trackball scrolling is not enabled
            if (!mEnableTrackballScroll) {
                if ((keyCode >= KeyEvent.KEYCODE_DPAD_UP)
                        && (keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    return super.onKeyDown(keyCode, event);
                }
            }

            int current = mViewImage.mCurrentPosition;

            int nextImagePos = -2; // default no next image
            try {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER: {
                        if (mViewImage.isPickIntent()) {
                            ImageManager.IImage img = mViewImage.mAllImages.getImageAt(mViewImage.mCurrentPosition);
                            mViewImage.setResult(RESULT_OK,
                                    new Intent().setData(img.fullSizeImageUri()));
                            mViewImage.finish();
                        }
                        break;
                    }
                    case KeyEvent.KEYCODE_DPAD_LEFT: {
                        panBy(sPanRate, 0);
                        int maxOffset = (current == 0) ? 0 : sHysteresis;
                        if (getScale() <= 1F || isShiftedToNextImage(true, maxOffset)) {
                            nextImagePos = current - 1;
                        } else {
                            center(true, false, true);
                        }
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_RIGHT: {
                        panBy(-sPanRate, 0);
                        int maxOffset = (current == mViewImage.mAllImages.getCount()-1) ? 0 : sHysteresis;
                        if (getScale() <= 1F || isShiftedToNextImage(false, maxOffset)) {
                            nextImagePos = current + 1;
                        } else {
                            center(true, false, true);
                        }
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_UP: {
                        panBy(0, sPanRate);
                        center(true, false, false);
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_DOWN: {
                        panBy(0, -sPanRate);
                        center(true, false, false);
                        return true;
                    }
                    case KeyEvent.KEYCODE_DEL:
                        MenuHelper.deletePhoto(mViewImage, mViewImage.mDeletePhotoRunnable);
                        break;
                }
            } finally {
                if (nextImagePos >= 0 && nextImagePos < mViewImage.mAllImages.getCount()) {
                    synchronized (mViewImage) {
                        mViewImage.setMode(MODE_NORMAL);
                        mViewImage.setImage(nextImagePos);
                    }
               } else if (nextImagePos != -2) {
                   center(true, true, false);
               }
            }

            return super.onKeyDown(keyCode, event);
        }

        protected boolean isShiftedToNextImage(boolean left, int maxOffset) {
            boolean retval;
            Bitmap bitmap = mBitmapDisplayed;
            Matrix m = getImageViewMatrix();
            if (left) {
                float [] t1 = new float[] { 0, 0 };
                m.mapPoints(t1);
                retval = t1[0] > maxOffset;
            } else {
                int width = bitmap != null ? bitmap.getWidth() : getWidth();
                float [] t1 = new float[] { width, 0 };
                m.mapPoints(t1);
                retval = t1[0] + maxOffset < getWidth();
            }
            return retval;
        }

        protected void scrollX(int deltaX) {
            scrollHandler().scrollBy(deltaX, 0);
        }

        protected int getScrollOffset() {
            return scrollHandler().getScrollX();
        }

        @Override
        protected void onDetachedFromWindow() {
            mZoomRingController.setVisible(false);
            mZoomButtonsController.setVisible(false);
        }

    }

    static class ScrollHandler extends LinearLayout {
        private Runnable mFirstLayoutCompletedCallback = null;
        private Scroller mScrollerHelper;
        private int mWidth = -1;

        public ScrollHandler(Context context) {
            super(context);
            mScrollerHelper = new Scroller(context);
        }

        public ScrollHandler(Context context, AttributeSet attrs) {
            super(context, attrs);
            mScrollerHelper = new Scroller(context);
        }

        public void setLayoutCompletedCallback(Runnable r) {
            mFirstLayoutCompletedCallback = r;
        }

        public void startScrollTo(int newX, int newY) {
            int oldX = getScrollX();
            int oldY = getScrollY();

            int deltaX = newX - oldX;
            int deltaY = newY - oldY;

            if (mWidth == -1) {
                mWidth = findViewById(R.id.image2).getWidth();
            }
            int viewWidth = mWidth;

            int duration = viewWidth > 0
                    ? sBaseScrollDuration * Math.abs(deltaX) / viewWidth
                    : 0;
            mScrollerHelper.startScroll(oldX, oldY, deltaX, deltaY, duration);
            invalidate();
        }

        @Override
        public void computeScroll() {
            if (mScrollerHelper.computeScrollOffset()) {
                scrollTo(mScrollerHelper.getCurrX(), mScrollerHelper.getCurrY());
                postInvalidate();  // So we draw again
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int x = 0;
            for (View v : new View[] {
                    findViewById(R.id.image1),
                    findViewById(R.id.image2),
                    findViewById(R.id.image3) }) {
                v.layout(x, 0, x + width, bottom);
                x += (width + sPadding);
            }

            findViewById(R.id.padding1).layout(width, 0, width + sPadding, bottom);
            findViewById(R.id.padding2).layout(width+sPadding+width, 0, width+sPadding+width+sPadding, bottom);

            if (changed) {
                if (mFirstLayoutCompletedCallback != null) {
                    mFirstLayoutCompletedCallback.run();
                }
            }
        }
    }

    private void animateScrollTo(int xNew, int yNew) {
        mScroller.startScrollTo(xNew, yNew);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);

        if (! mCameraReviewMode) {
            MenuItem item = menu.add(Menu.CATEGORY_SECONDARY, 203, 0, R.string.slide_show);
            item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    setMode(MODE_SLIDESHOW);
                    mLastSlideShowImage = mCurrentPosition;
                    loadNextImage(mCurrentPosition, 0, true);
                    return true;
                }
            });
            item.setIcon(android.R.drawable.ic_menu_slideshow);
        }

        final SelectedImageGetter selectedImageGetter = new SelectedImageGetter() {
            public ImageManager.IImage getCurrentImage() {
                return mAllImages.getImageAt(mCurrentPosition);
            }

            public Uri getCurrentImageUri() {
                return mAllImages.getImageAt(mCurrentPosition).fullSizeImageUri();
            }
        };

        mImageMenuRunnable = MenuHelper.addImageMenuItems(
                menu,
                MenuHelper.INCLUDE_ALL,
                true,
                ViewImage.this,
                mHandler,
                mDeletePhotoRunnable,
                new MenuHelper.MenuInvoker() {
                    public void run(MenuHelper.MenuCallback cb) {
                        setMode(MODE_NORMAL);
                        cb.run(selectedImageGetter.getCurrentImageUri(), selectedImageGetter.getCurrentImage());
                        for (ImageViewTouchBase iv: mImageViews) {
                            iv.recycleBitmaps();
                            iv.setImageBitmap(null, true);
                        }
                        setImage(mCurrentPosition);
                    }
                });

        if (true) {
            MenuItem item = menu.add(Menu.CATEGORY_SECONDARY, 203, 1000, R.string.camerasettings);
            item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    Intent preferences = new Intent();
                    preferences.setClass(ViewImage.this, GallerySettings.class);
                    startActivity(preferences);
                    return true;
                }
            });
            item.setAlphabeticShortcut('p');
            item.setIcon(android.R.drawable.ic_menu_preferences);
        }

        // Hidden menu just so the shortcut will bring up the zoom controls
        menu.add(Menu.CATEGORY_SECONDARY, 203, 0, R.string.camerasettings)      // the string resource is a placeholder
        .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                showOnScreenControls();
                return true;
            }
        })
        .setAlphabeticShortcut('z')
        .setVisible(false);


        return true;
    }

    protected Runnable mDeletePhotoRunnable = new Runnable() {
        public void run() {
            mAllImages.removeImageAt(mCurrentPosition);
            if (mAllImages.getCount() == 0) {
                finish();
            } else {
                if (mCurrentPosition == mAllImages.getCount()) {
                    mCurrentPosition -= 1;
                }
            }
            for (ImageViewTouchBase iv: mImageViews) {
                iv.setImageBitmapResetBase(null, true, true);
            }
            setImage(mCurrentPosition);
        }
    };

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        super.onPrepareOptionsMenu(menu);
        setMode(MODE_NORMAL);

        if (mImageMenuRunnable != null) {
            mImageMenuRunnable.gettingReadyToOpen(menu, mAllImages.getImageAt(mCurrentPosition));
        }

        return true;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean changed = mCurrentOrientation != newConfig.orientation;
        mCurrentOrientation = newConfig.orientation;
        if (changed) {
            if (mGetter != null) {
                // kill off any background image fetching
                mGetter.cancelCurrent();
                mGetter.stop();
            }
            makeGetter();
            mFirst = true;

            // clear off the current set of images since we need to reload
            // them at the right size
            for (ImageViewTouchBase iv: mImageViews) {
                iv.clear();
            }
            MenuHelper.requestOrientation(this, mPrefs);
        }
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        boolean b = super.onMenuItemSelected(featureId, item);
        if (mImageMenuRunnable != null)
            mImageMenuRunnable.aboutToCall(item, mAllImages.getImageAt(mCurrentPosition));
        return b;
    }

    /*
     * Here's the loading strategy.  For any given image, load the thumbnail
     * into memory and post a callback to display the resulting bitmap.
     *
     * Then proceed to load the full image bitmap.   Three things can
     * happen at this point:
     *
     * 1.  the image fails to load because the UI thread decided
     * to move on to a different image.  This "cancellation" happens
     * by virtue of the UI thread closing the stream containing the
     * image being decoded.  BitmapFactory.decodeStream returns null
     * in this case.
     *
     * 2.  the image loaded successfully.  At that point we post
     * a callback to the UI thread to actually show the bitmap.
     *
     * 3.  when the post runs it checks to see if the image that was
     * loaded is still the one we want.  The UI may have moved on
     * to some other image and if so we just drop the newly loaded
     * bitmap on the floor.
     */

    interface ImageGetterCallback {
        public void imageLoaded(int pos, int offset, Bitmap bitmap, boolean isThumb);
        public boolean wantsThumbnail(int pos, int offset);
        public boolean wantsFullImage(int pos, int offset);
        public int fullImageSizeToUse(int pos, int offset);
        public void completed(boolean wasCanceled);
        public int [] loadOrder();
    }

    class ImageGetter {
        // The thread which does the work.
        private Thread mGetterThread;

        // The base position that's being retrieved.  The actual images retrieved
        // are this base plus each of the offets.
        private int mCurrentPosition = -1;

        // The callback to invoke for each image.
        private ImageGetterCallback mCB;

        // This is the loader cancelable that gets set while we're loading an image.
        // If we change position we can cancel the current load using this.
        private ImageManager.IGetBitmap_cancelable mLoad;

        // True if we're canceling the current load.
        private boolean mCancelCurrent = false;

        // True when the therad should exit.
        private boolean mDone = false;

        // True when the loader thread is waiting for work.
        private boolean mReady = false;

        private void cancelCurrent() {
            synchronized (this) {
                if (!mReady) {
                    mCancelCurrent = true;
                    ImageManager.IGetBitmap_cancelable load = mLoad;
                    if (load != null) {
                        if (Config.LOGV)
                            Log.v(TAG, "canceling load object");
                        load.cancel();
                    }
                    mCancelCurrent = false;
                }
            }
        }

        public ImageGetter() {
            mGetterThread = new Thread(new Runnable() {

                private Runnable callback(final int position, final int offset, final boolean isThumb, final Bitmap bitmap) {
                    return new Runnable() {
                        public void run() {
                            // check for inflight callbacks that aren't applicable any longer
                            // before delivering them
                            if (!isCanceled() && position == mCurrentPosition) {
                                mCB.imageLoaded(position, offset, bitmap, isThumb);
                            } else {
                                if (bitmap != null)
                                    bitmap.recycle();
                            }
                        }
                    };
                }

                private Runnable completedCallback(final boolean wasCanceled) {
                    return new Runnable() {
                        public void run() {
                            mCB.completed(wasCanceled);
                        }
                    };
                }

                public void run() {
                    int lastPosition = -1;
                    while (!mDone) {
                        synchronized (ImageGetter.this) {
                            mReady = true;
                            ImageGetter.this.notify();

                            if (mCurrentPosition == -1 || lastPosition == mCurrentPosition) {
                                try {
                                    ImageGetter.this.wait();
                                } catch (InterruptedException ex) {
                                    continue;
                                }
                            }

                            lastPosition = mCurrentPosition;
                            mReady = false;
                        }

                        if (lastPosition != -1) {
                            int imageCount = mAllImages.getCount();

                            int [] order = mCB.loadOrder();
                            for (int i = 0; i < order.length; i++) {
                                int offset = order[i];
                                int imageNumber = lastPosition + offset;
                                if (imageNumber >= 0 && imageNumber < imageCount) {
                                    ImageManager.IImage image = mAllImages.getImageAt(lastPosition + offset);
                                    if (image == null || isCanceled()) {
                                        break;
                                    }
                                    if (mCB.wantsThumbnail(lastPosition, offset)) {
                                        if (Config.LOGV)
                                            Log.v(TAG, "starting THUMBNAIL load at offset " + offset);
                                        Bitmap b = image.thumbBitmap();
                                        mHandler.post(callback(lastPosition, offset, true, b));
                                    }
                                }
                            }

                            for (int i = 0; i < order.length; i++) {
                                int offset = order[i];
                                int imageNumber = lastPosition + offset;
                                if (imageNumber >= 0 && imageNumber < imageCount) {
                                    ImageManager.IImage image = mAllImages.getImageAt(lastPosition + offset);
                                    if (mCB.wantsFullImage(lastPosition, offset)) {
                                        if (Config.LOGV)
                                            Log.v(TAG, "starting FULL IMAGE load at offset " + offset);
                                        int sizeToUse = mCB.fullImageSizeToUse(lastPosition, offset);
                                        if (image != null && !isCanceled()) {
                                            mLoad = image.fullSizeBitmap_cancelable(sizeToUse);
                                        }
                                        if (mLoad != null) {
                                            long t1;
                                            if (Config.LOGV) t1 = System.currentTimeMillis();

                                            Bitmap b = null;
                                            try {
                                                b = mLoad.get();
                                            } catch (OutOfMemoryError e) {
                                                Log.e(TAG, "couldn't load full size bitmap for " + "");
                                            }
                                            if (Config.LOGV && b != null) {
                                                long t2 = System.currentTimeMillis();
                                                Log.v(TAG, "loading full image for " + image.fullSizeImageUri()
                                                        + " with requested size " + sizeToUse
                                                        + " took " + (t2-t1)
                                                        + " and returned a bitmap with size "
                                                        + b.getWidth() + " / " + b.getHeight());
                                            }

                                            mLoad = null;
                                            if (b != null) {
                                                if (isCanceled()) {
                                                    b.recycle();
                                                } else {
                                                    mHandler.post(callback(lastPosition, offset, false, b));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            mHandler.post(completedCallback(isCanceled()));
                        }
                    }
                }
            });
            mGetterThread.setName("ImageGettter");
            mGetterThread.start();
        }

        private boolean isCanceled() {
            synchronized (this) {
                return mCancelCurrent;
            }
        }

        public void setPosition(int position, ImageGetterCallback cb) {
            synchronized (this) {
                if (!mReady) {
                    try {
                        mCancelCurrent = true;
                        ImageManager.IGetBitmap_cancelable load = mLoad;
                        if (load != null) {
                            load.cancel();
                        }
                        // if the thread is waiting before loading the full size
                        // image then this will free it up
                        ImageGetter.this.notify();
                        ImageGetter.this.wait();
                        mCancelCurrent = false;
                    } catch (InterruptedException ex) {
                        // not sure what to do here
                    }
                }
            }

            mCurrentPosition = position;
            mCB = cb;

            synchronized (this) {
                ImageGetter.this.notify();
            }
        }

        public void stop() {
            synchronized (this) {
                mDone = true;
                ImageGetter.this.notify();
            }
            try {
                mGetterThread.join();
            } catch (InterruptedException ex) {

            }
        }
    }

    private void setImage(int pos) {
        if (!mLayoutComplete) {
            return;
        }

        final boolean left = mCurrentPosition > pos;

        mCurrentPosition = pos;

        ImageViewTouchBase current = mImageViews[1];
        current.mSuppMatrix.reset();
        current.setImageMatrix(current.getImageViewMatrix());

        if (false) {
            Log.v(TAG, "before...");
            for (ImageViewTouchBase ivtb : mImageViews)
                ivtb.dump();
        }

        if (!mFirst) {
            if (left) {
                mImageViews[2].copyFrom(mImageViews[1]);
                mImageViews[1].copyFrom(mImageViews[0]);
            } else {
                mImageViews[0].copyFrom(mImageViews[1]);
                mImageViews[1].copyFrom(mImageViews[2]);
            }
        }
        if (false) {
            Log.v(TAG, "after copy...");
            for (ImageViewTouchBase ivtb : mImageViews)
                ivtb.dump();
        }

        for (ImageViewTouchBase ivt: mImageViews) {
            ivt.mIsZooming = false;
        }
        int width = mImageViews[1].getWidth();
        int from;
        int to = width + sPadding;
        if (mFirst) {
            from = to;
            mFirst = false;
        } else {
            from = left ? (width + sPadding) + mScroller.getScrollX()
                        : mScroller.getScrollX() - (width + sPadding);
        }

        if (sAnimateTransitions) {
            mScroller.scrollTo(from, 0);
            animateScrollTo(to, 0);
        } else {
            mScroller.scrollTo(to, 0);
        }

        ImageGetterCallback cb = new ImageGetterCallback() {
            public void completed(boolean wasCanceled) {
                if (!mShowActionIcons) {
                    mImageViews[1].setFocusableInTouchMode(true);
                    mImageViews[1].requestFocus();
                }
            }

            public boolean wantsThumbnail(int pos, int offset) {
                ImageViewTouchBase ivt = mImageViews[1 + offset];
                return ivt.mThumbBitmap == null;
            }

            public boolean wantsFullImage(int pos, int offset) {
                ImageViewTouchBase ivt = mImageViews[1 + offset];
                if (ivt.mBitmapDisplayed != null && !ivt.mBitmapIsThumbnail) {
                    return false;
                }
                if (offset != 0) {
                    return false;
                }
                return true;
            }

            public int fullImageSizeToUse(int pos, int offset) {
                // TODO
                // this number should be bigger so that we can zoom.  we may need to
                // get fancier and read in the fuller size image as the user starts
                // to zoom.  use -1 to get the full full size image.
                // for now use 480 so we don't run out of memory
                final int imageViewSize = 480;
                return imageViewSize;
            }

            public int [] loadOrder() {
                return sOrder_adjacents;
            }

            public void imageLoaded(int pos, int offset, Bitmap bitmap, boolean isThumb) {
                ImageViewTouchBase ivt = mImageViews[1 + offset];
                ivt.setImageBitmapResetBase(bitmap, isThumb, isThumb);
            }
        };

        // Could be null if we're stopping a slide show in the course of pausing
        if (mGetter != null) {
            mGetter.setPosition(pos, cb);
        }
        showOnScreenControls();
    }

    @Override
    public void onCreate(Bundle instanceState)
    {
        super.onCreate(instanceState);
        Intent intent = getIntent();
        mCameraReviewMode = intent.getBooleanExtra("com.android.camera.ReviewMode", false);
        mFullScreenInNormalMode = intent.getBooleanExtra(MediaStore.EXTRA_FULL_SCREEN, true);
        mShowActionIcons = intent.getBooleanExtra(MediaStore.EXTRA_SHOW_ACTION_ICONS, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mCurrentOrientation = getResources().getConfiguration().orientation;

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.viewimage);

        mImageViews[0] = (ImageViewTouch) findViewById(R.id.image1);
        mImageViews[1] = (ImageViewTouch) findViewById(R.id.image2);
        mImageViews[2] = (ImageViewTouch) findViewById(R.id.image3);

        for(ImageViewTouch v : mImageViews) {
            v.setEnableTrackballScroll(!mShowActionIcons);
        }

        mScroller = (ScrollHandler)findViewById(R.id.scroller);
        makeGetter();

        mAnimationIndex = -1;

        mSlideShowInAnimation = new Animation[] {
            makeInAnimation(R.anim.transition_in),
            makeInAnimation(R.anim.slide_in),
            makeInAnimation(R.anim.slide_in_vertical),
        };

        mSlideShowOutAnimation = new Animation[] {
            makeOutAnimation(R.anim.transition_out),
            makeOutAnimation(R.anim.slide_out),
            makeOutAnimation(R.anim.slide_out_vertical),
        };

        mSlideShowImageViews[0] = (ImageViewTouch) findViewById(R.id.image1_slideShow);
        mSlideShowImageViews[1] = (ImageViewTouch) findViewById(R.id.image2_slideShow);
        for (ImageViewTouch v : mSlideShowImageViews) {
            v.setImageBitmapResetBase(null, true, true);
            v.setVisibility(View.INVISIBLE);
            v.setEnableTrackballScroll(!mShowActionIcons);
        }

        mActionIconPanel = findViewById(R.id.action_icon_panel);
        {
            int[] pickIds = {R.id.attach, R.id.cancel};
            int[] normalIds = {R.id.gallery, R.id.setas, R.id.share, R.id.discard};
            int[] hideIds = pickIds;
            int[] connectIds = normalIds;
            if (isPickIntent()) {
                hideIds = normalIds;
                connectIds = pickIds;
            }
            for(int id : hideIds) {
                mActionIconPanel.findViewById(id).setVisibility(View.GONE);
            }
            for(int id : connectIds) {
                View view = mActionIconPanel.findViewById(id);
                view.setOnClickListener(this);
                Animation animation = new AlphaAnimation(0F, 1F);
                animation.setDuration(500);
                view.setAnimation(animation);
            }
        }
        mShutterButton = findViewById(R.id.shutter_button);
        mShutterButton.setOnClickListener(this);

        Uri uri = getIntent().getData();

        if (Config.LOGV)
            Log.v(TAG, "uri is " + uri);
        if (instanceState != null) {
            if (instanceState.containsKey("uri")) {
                uri = Uri.parse(instanceState.getString("uri"));
            }
        }
        if (uri == null) {
            finish();
            return;
        }
        init(uri);

        Bundle b = getIntent().getExtras();

        boolean slideShow = b != null ? b.getBoolean("slideshow", false) : false;
        if (slideShow) {
            setMode(MODE_SLIDESHOW);
            loadNextImage(mCurrentPosition, 0, true);
        } else {
            if (mFullScreenInNormalMode) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            if (mShowActionIcons) {
                mActionIconPanel.setVisibility(View.VISIBLE);
                mShutterButton.setVisibility(View.VISIBLE);
            }
        }

        setupDismissOnScreenControlRunnable();

        mNextImageView = findViewById(R.id.next_image);
        mPrevImageView = findViewById(R.id.prev_image);
        mNextImageView.setOnClickListener(this);
        mPrevImageView.setOnClickListener(this);

        if (mShowActionIcons) {
            mNextImageView.setFocusable(true);
            mPrevImageView.setFocusable(true);
        }

        setOrientation();
    }

    private void setOrientation() {
        Intent intent = getIntent();
        if (intent.hasExtra(MediaStore.EXTRA_SCREEN_ORIENTATION)) {
            int orientation = intent.getIntExtra(MediaStore.EXTRA_SCREEN_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            if (orientation != getRequestedOrientation()) {
                setRequestedOrientation(orientation);
            }
        } else {
            MenuHelper.requestOrientation(this, mPrefs);
        }
    }

    private Animation makeInAnimation(int id) {
        Animation inAnimation = AnimationUtils.loadAnimation(this, id);
        return inAnimation;
    }

    private Animation makeOutAnimation(int id) {
        Animation outAnimation = AnimationUtils.loadAnimation(this, id);
        return outAnimation;
    }

    private void setMode(int mode) {
        if (mMode == mode) {
            return;
        }

        findViewById(R.id.slideShowContainer).setVisibility(mode == MODE_SLIDESHOW ? View.VISIBLE : View.GONE);
        findViewById(R.id.abs)               .setVisibility(mode == MODE_NORMAL    ? View.VISIBLE : View.GONE);

        Window win = getWindow();
        mMode = mode;
        if (mode == MODE_SLIDESHOW) {
            win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (sSlideShowHidesStatusBar) {
                win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            for (ImageViewTouchBase ivt: mImageViews) {
                ivt.clear();
            }
            mActionIconPanel.setVisibility(View.GONE);
            mShutterButton.setVisibility(View.GONE);

            if (false) {
                Log.v(TAG, "current is " + this.mSlideShowImageCurrent);
                this.mSlideShowImageViews[0].dump();
                this.mSlideShowImageViews[0].dump();
            }

            findViewById(R.id.slideShowContainer).getRootView().requestLayout();
            mUseShuffleOrder   = mPrefs.getBoolean("pref_gallery_slideshow_shuffle_key", false);
            mSlideShowLoop     = mPrefs.getBoolean("pref_gallery_slideshow_repeat_key", false);
            try {
                mAnimationIndex = Integer.parseInt(mPrefs.getString("pref_gallery_slideshow_transition_key", "0"));
            } catch (Exception ex) {
                Log.e(TAG, "couldn't parse preference: " + ex.toString());
                mAnimationIndex = 0;
            }
            try {
                mSlideShowInterval = Integer.parseInt(mPrefs.getString("pref_gallery_slideshow_interval_key", "3")) * 1000;
            } catch (Exception ex) {
                Log.e(TAG, "couldn't parse preference: " + ex.toString());
                mSlideShowInterval = 3000;
            }

            if (Config.LOGV) {
                Log.v(TAG, "read prefs...  shuffle: " + mUseShuffleOrder);
                Log.v(TAG, "read prefs...     loop: " + mSlideShowLoop);
                Log.v(TAG, "read prefs...  animidx: " + mAnimationIndex);
                Log.v(TAG, "read prefs... interval: " + mSlideShowInterval);
            }

            if (mUseShuffleOrder) {
                generateShuffleOrder();
            }
        } else {
            if (Config.LOGV)
                Log.v(TAG, "slide show mode off, mCurrentPosition == " + mCurrentPosition);
            win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (mFullScreenInNormalMode) {
                win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            } else {
                win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            if (mGetter != null)
                mGetter.cancelCurrent();

            if (sSlideShowHidesStatusBar) {
                win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            if (mShowActionIcons) {
                mActionIconPanel.setVisibility(View.VISIBLE);
                mShutterButton.setVisibility(View.VISIBLE);
            }

            ImageViewTouchBase dst = mImageViews[1];
            dst.mLastXTouchPos = -1;
            dst.mLastYTouchPos = -1;

            for (ImageViewTouchBase ivt: mSlideShowImageViews) {
                ivt.clear();
            }

            mShuffleOrder = null;

            // mGetter null is a proxy for being paused
            if (mGetter != null) {
                mFirst = true;  // don't animate
                setImage(mCurrentPosition);
            }
        }

        // this line shouldn't be necessary but the view hierarchy doesn't
        // seem to realize that the window layout changed
        mScroller.requestLayout();
    }

    private void generateShuffleOrder() {
        if (mShuffleOrder == null || mShuffleOrder.length != mAllImages.getCount()) {
            mShuffleOrder = new int[mAllImages.getCount()];
        }

        for (int i = 0; i < mShuffleOrder.length; i++) {
            mShuffleOrder[i] = i;
        }

        for (int i = mShuffleOrder.length - 1; i > 0; i--) {
            int r = mRandom.nextInt(i);
            int tmp = mShuffleOrder[r];
            mShuffleOrder[r] = mShuffleOrder[i];
            mShuffleOrder[i] = tmp;
        }
    }

    private void loadNextImage(final int requestedPos, final long delay, final boolean firstCall) {
        if (firstCall && mUseShuffleOrder) {
            generateShuffleOrder();
        }

        final long targetDisplayTime = System.currentTimeMillis() + delay;

        ImageGetterCallback cb = new ImageGetterCallback() {
            public void completed(boolean wasCanceled) {
            }

            public boolean wantsThumbnail(int pos, int offset) {
                return true;
            }

            public boolean wantsFullImage(int pos, int offset) {
                return false;
            }

            public int [] loadOrder() {
                return sOrder_slideshow;
            }

            public int fullImageSizeToUse(int pos, int offset) {
                return 480; // TODO compute this
            }

            public void imageLoaded(final int pos, final int offset, final Bitmap bitmap, final boolean isThumb) {
                long timeRemaining = Math.max(0, targetDisplayTime - System.currentTimeMillis());
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        if (mMode == MODE_NORMAL) {
                            return;
                        }

                        ImageViewTouchBase oldView = mSlideShowImageViews[mSlideShowImageCurrent];

                        if (++mSlideShowImageCurrent == mSlideShowImageViews.length) {
                            mSlideShowImageCurrent = 0;
                        }

                        ImageViewTouchBase newView = mSlideShowImageViews[mSlideShowImageCurrent];
                        newView.setVisibility(View.VISIBLE);
                        newView.setImageBitmapResetBase(bitmap, isThumb, isThumb);
                        newView.bringToFront();

                        int animation = 0;

                        if (mAnimationIndex == -1) {
                            int n = mRandom.nextInt(mSlideShowInAnimation.length);
                            animation = n;
                        } else {
                            animation = mAnimationIndex;
                        }

                        Animation aIn = mSlideShowInAnimation[animation];
                        newView.setAnimation(aIn);
                        newView.setVisibility(View.VISIBLE);
                        aIn.startNow();

                        Animation aOut = mSlideShowOutAnimation[animation];
                        oldView.setVisibility(View.INVISIBLE);
                        oldView.setAnimation(aOut);
                        aOut.startNow();

                        mCurrentPosition = requestedPos;

                        mHandler.post(new Runnable() {
                            public void run() {
                                if (mCurrentPosition == mLastSlideShowImage && !firstCall) {
                                    if (mSlideShowLoop) {
                                        if (mUseShuffleOrder) {
                                            generateShuffleOrder();
                                        }
                                    } else {
                                        setMode(MODE_NORMAL);
                                        return;
                                    }
                                }

                                if (Config.LOGV)
                                    Log.v(TAG, "mCurrentPosition is now " + mCurrentPosition);
                                loadNextImage((mCurrentPosition + 1) % mAllImages.getCount(), mSlideShowInterval, false);
                            }
                        });
                    }
                }, timeRemaining);
            }
        };
        // Could be null if we're stopping a slide show in the course of pausing
        if (mGetter != null) {
            int pos = requestedPos;
            if (mShuffleOrder != null) {
                pos = mShuffleOrder[pos];
            }
            mGetter.setPosition(pos, cb);
        }
    }

    private void makeGetter() {
        mGetter = new ImageGetter();
    }

    private boolean desiredSortOrder() {
        String sortOrder = mPrefs.getString("pref_gallery_sort_key", null);
        boolean sortAscending = false;
        if (sortOrder != null) {
            sortAscending = sortOrder.equals("ascending");
        }
        if (mCameraReviewMode) {
            // Force left-arrow older pictures, right-arrow newer pictures.
            sortAscending = true;
        }
        return sortAscending;
    }

    private void init(Uri uri) {
        mSortAscending = desiredSortOrder();
        int sort = mSortAscending ? ImageManager.SORT_ASCENDING : ImageManager.SORT_DESCENDING;
        mAllImages = ImageManager.makeImageList(uri, this, sort);

        uri = uri.buildUpon().query(null).build();
        // TODO smarter/faster here please
        for (int i = 0; i < mAllImages.getCount(); i++) {
            ImageManager.IImage image = mAllImages.getImageAt(i);
            if (image.fullSizeImageUri().equals(uri)) {
                mCurrentPosition = i;
                mLastSlideShowImage = mCurrentPosition;
                break;
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        ImageManager.IImage image = mAllImages.getImageAt(mCurrentPosition);

        if (image != null){
            Uri uri = image.fullSizeImageUri();
            String bucket = null;
            if(getIntent()!= null && getIntent().getData()!=null)
                bucket = getIntent().getData().getQueryParameter("bucketId");

            if(bucket!=null)
                uri = uri.buildUpon().appendQueryParameter("bucketId", bucket).build();

            b.putString("uri", uri.toString());
        }
        if (mMode == MODE_SLIDESHOW)
            b.putBoolean("slideshow", true);
    }

    @Override
    public void onResume()
    {
        super.onResume();

        // normally this will never be zero but if one "backs" into this
        // activity after removing the sdcard it could be zero.  in that
        // case just "finish" since there's nothing useful that can happen.
        if (mAllImages.getCount() == 0) {
            finish();
        }

        ImageManager.IImage image = mAllImages.getImageAt(mCurrentPosition);

        if (desiredSortOrder() != mSortAscending) {
            init(image.fullSizeImageUri());
        }

        if (mGetter == null) {
            makeGetter();
        }

        for (ImageViewTouchBase iv: mImageViews) {
            iv.setImageBitmap(null, true);
        }

        mFirst = true;
        mScroller.setLayoutCompletedCallback(new Runnable() {
            public void run() {
                mLayoutComplete = true;
                setImage(mCurrentPosition);
            }
         });
        setImage(mCurrentPosition);

        setOrientation();

        // Show a tutorial for the new zoom interaction (the method ensure we only show it once)
        ZoomRingController.showZoomTutorialOnce(this);
    }

    @Override
    public void onPause()
    {
        super.onPause();

        mGetter.cancelCurrent();
        mGetter.stop();
        mGetter = null;
        setMode(MODE_NORMAL);

        mAllImages.deactivate();

        for (ImageViewTouch iv: mImageViews) {
            iv.recycleBitmaps();
            iv.setImageBitmap(null, true);
        }

        for (ImageViewTouch iv: mSlideShowImageViews) {
            iv.recycleBitmaps();
            iv.setImageBitmap(null, true);
        }
        ZoomRingController.finishZoomTutorial(this, false);
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    public void onClick(View v) {
        switch (v.getId()) {

        case R.id.shutter_button: {
            if (mCameraReviewMode) {
                finish();
            } else {
                MenuHelper.gotoStillImageCapture(this);
            }
        }
        break;

        case R.id.gallery: {
            MenuHelper.gotoCameraImageGallery(this);
        }
        break;

        case R.id.discard: {
            if (mCameraReviewMode) {
                mDeletePhotoRunnable.run();
            } else {
                MenuHelper.deletePhoto(this, mDeletePhotoRunnable);
            }
        }
        break;

        case R.id.share: {
            Uri u = mAllImages.getImageAt(mCurrentPosition).fullSizeImageUri();
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_STREAM, u);
            try {
                startActivity(Intent.createChooser(intent, getText(R.string.sendImage)));
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(this, R.string.no_way_to_share_image, Toast.LENGTH_SHORT).show();
            }
        }
        break;

        case R.id.setas: {
            Uri u = mAllImages.getImageAt(mCurrentPosition).fullSizeImageUri();
            Intent intent = new Intent(Intent.ACTION_ATTACH_DATA, u);
            try {
                startActivity(Intent.createChooser(intent, getText(R.string.setImage)));
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(this, R.string.no_way_to_share_video, Toast.LENGTH_SHORT).show();
            }
        }
        break;

        case R.id.next_image: {
            moveNextOrPrevious(1);
        }
        break;

        case R.id.prev_image: {
            moveNextOrPrevious(-1);
        }
        break;
        }
    }

    private void moveNextOrPrevious(int delta) {
        int nextImagePos = mCurrentPosition + delta;
        if ((0 <= nextImagePos) && (nextImagePos < mAllImages.getCount())) {
            setImage(nextImagePos);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case MenuHelper.RESULT_COMMON_MENU_CROP:
            if (resultCode == RESULT_OK) {
                // The CropImage activity passes back the Uri of the cropped image as
                // the Action rather than the Data.
                Uri dataUri = Uri.parse(data.getAction());
                init(dataUri);
            }
            break;
        }
    }
}
