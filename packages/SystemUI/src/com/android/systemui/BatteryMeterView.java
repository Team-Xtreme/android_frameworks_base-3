/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.systemui;

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_NONE;

import static com.android.settingslib.graph.BatteryMeterDrawableBase.BATTERY_STYLE_PORTRAIT;
import static com.android.settingslib.graph.BatteryMeterDrawableBase.BATTERY_STYLE_CIRCLE;
import static com.android.settingslib.graph.BatteryMeterDrawableBase.BATTERY_STYLE_DOTTED_CIRCLE;
import static com.android.settingslib.graph.BatteryMeterDrawableBase.BATTERY_STYLE_TEXT;
import static com.android.settingslib.graph.BatteryMeterDrawableBase.BATTERY_STYLE_SOLID;
import static com.android.settingslib.graph.BatteryMeterDrawableBase.BATTERY_STYLE_HIDDEN;

import static com.android.systemui.util.SysuiLifecycle.viewAttachLifecycle;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StyleRes;

import com.android.settingslib.Utils;
import com.android.settingslib.graph.BatteryMeterDrawableBase;
import com.android.settingslib.graph.ThemedBatteryDrawable;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.Utils.DisableStateTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.text.NumberFormat;

public class BatteryMeterView extends LinearLayout implements
        BatteryStateChangeCallback, Tunable, DarkReceiver, ConfigurationListener {

    public static final String STATUS_BAR_BATTERY_STYLE =
            "system:" + Settings.System.STATUS_BAR_BATTERY_STYLE;
    public static final String STATUS_BAR_SHOW_BATTERY_PERCENT =
            "system:" + Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT;
    public static final String STATUS_BAR_BATTERY_TEXT_CHARGING =
            "system:" + Settings.System.STATUS_BAR_BATTERY_TEXT_CHARGING;

    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ON, MODE_OFF, MODE_ESTIMATE})
    public @interface BatteryPercentMode {}
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_ESTIMATE = 3; // Not to be used

    private final BatteryMeterDrawableBase mXDrawable;
    private final ThemedBatteryDrawable mDrawable;
    private final String mSlotBattery;
    private final ImageView mBatteryIconView;
    private final CurrentUserTracker mUserTracker;
    private TextView mBatteryPercentView;

    private BatteryController mBatteryController;
    private SettingObserver mSettingObserver;
    private final @StyleRes int mPercentageStyleId;
    private int mTextColor;
    private int mLevel;
    private int mShowPercentMode = MODE_DEFAULT;
    // Some places may need to show the battery conditionally, and not obey the tuner
    private boolean mIgnoreTunerUpdates;
    private boolean mIsSubscribedForTunerUpdates;

    private boolean mCharging;
    public int mBatteryStyle = BATTERY_STYLE_PORTRAIT;
    public int mShowBatteryPercent;
    public int mShowBatteryEstimate = 0;
    private boolean mBatteryPercentCharging;

    private DualToneHandler mDualToneHandler;
    private int mUser;

    /**
     * Whether we should use colors that adapt based on wallpaper/the scrim behind quick settings.
     */
    private boolean mUseWallpaperTextColors;

    private int mNonAdaptedSingleToneColor;
    private int mNonAdaptedForegroundColor;
    private int mNonAdaptedBackgroundColor;

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.meter_background_color));
        mPercentageStyleId = atts.getResourceId(R.styleable.BatteryMeterView_textAppearance, 0);
        mDrawable = new ThemedBatteryDrawable(context, frameColor);
        mXDrawable = new BatteryMeterDrawableBase(context, frameColor);
        atts.recycle();

        mSettingObserver = new SettingObserver(new Handler(context.getMainLooper()));

        addOnAttachStateChangeListener(
                new DisableStateTracker(DISABLE_NONE, DISABLE2_SYSTEM_ICONS));

        setupLayoutTransition();

        mSlotBattery = context.getString(
                com.android.internal.R.string.status_bar_battery);
        mBatteryIconView = new ImageView(context);
        mBatteryIconView.setImageDrawable(mDrawable);
        final MarginLayoutParams mlp = new MarginLayoutParams(
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
        mlp.setMargins(0, 0, 0,
                getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
        addView(mBatteryIconView, mlp);

        updateShowPercent();
        mDualToneHandler = new DualToneHandler(context);
        // Init to not dark at all.
        onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mUser = newUserId;
                getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
                updateShowPercent();
            }
        };

        setClipChildren(false);
        setClipToPadding(false);
        Dependency.get(ConfigurationController.class).observe(viewAttachLifecycle(this), this);
    }

    private void setupLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

        setLayoutTransition(transition);
    }

    public void setForceShowPercent(boolean show) {
        setPercentShowMode(show ? MODE_ON : MODE_DEFAULT);
    }

    /**
     * Force a particular mode of showing percent
     *
     * 0 - No preference
     * 1 - Force on
     * 2 - Force off
     * @param mode desired mode (none, on, off)
     */
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        mShowPercentMode = mode;
        updateShowPercent();
    }

    /**
     * Set {@code true} to turn off BatteryMeterView's subscribing to the tuner for updates, and
     * thus avoid it controlling its own visibility
     *
     * @param ignore whether to ignore the tuner or not
     */
    public void setIgnoreTunerUpdates(boolean ignore) {
        mIgnoreTunerUpdates = ignore;
        updateTunerSubscription();
    }

    private void updateTunerSubscription() {
        if (mIgnoreTunerUpdates) {
            unsubscribeFromTunerUpdates();
        } else {
            subscribeForTunerUpdates();
        }
    }

    private void subscribeForTunerUpdates() {
        if (mIsSubscribedForTunerUpdates || mIgnoreTunerUpdates) {
            return;
        }

        Dependency.get(TunerService.class)
                .addTunable(this, STATUS_BAR_BATTERY_STYLE,
                                  STATUS_BAR_SHOW_BATTERY_PERCENT,
                                  STATUS_BAR_BATTERY_TEXT_CHARGING);
        mIsSubscribedForTunerUpdates = true;
    }

    private void unsubscribeFromTunerUpdates() {
        if (!mIsSubscribedForTunerUpdates) {
            return;
        }

        Dependency.get(TunerService.class).removeTunable(this);
        mIsSubscribedForTunerUpdates = false;
    }

    /**
     * Sets whether the battery meter view uses the wallpaperTextColor. If we're not using it, we'll
     * revert back to dark-mode-based/tinted colors.
     *
     * @param shouldUseWallpaperTextColor whether we should use wallpaperTextColor for all
     *                                    components
     */
    public void useWallpaperTextColor(boolean shouldUseWallpaperTextColor) {
        if (shouldUseWallpaperTextColor == mUseWallpaperTextColors) {
            return;
        }

        mUseWallpaperTextColors = shouldUseWallpaperTextColor;

        if (mUseWallpaperTextColors) {
            updateColors(
                    Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor),
                    Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColorSecondary),
                    Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor));
        } else {
            updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor,
                    mNonAdaptedSingleToneColor);
        }
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        mDualToneHandler.setColorsFromContext(context);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case STATUS_BAR_BATTERY_STYLE:
                mBatteryStyle =
                        TunerService.parseInteger(newValue, BATTERY_STYLE_PORTRAIT);
                updateBatteryStyle();
                updatePercentView();
                updateVisibility();
                break;
            case STATUS_BAR_SHOW_BATTERY_PERCENT:
                mShowBatteryPercent =
                        TunerService.parseInteger(newValue, 0);
                updatePercentView();
                break;
            case STATUS_BAR_BATTERY_TEXT_CHARGING:
                mBatteryPercentCharging =
                        TunerService.parseIntegerSwitch(newValue, true);
                updatePercentView();
                break;
            default:
                break;
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
        mUser = ActivityManager.getCurrentUser();
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME),
                false, mSettingObserver);
        updateShowPercent();
        subscribeForTunerUpdates();
        mUserTracker.startTracking();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mUserTracker.stopTracking();
        mBatteryController.removeCallback(this);
        getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
        unsubscribeFromTunerUpdates();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        if (mLevel != level) {
            mLevel = level;
            mDrawable.setBatteryLevel(mLevel);
            mXDrawable.setBatteryLevel(level);
        }
        if (mCharging != pluggedIn) {
            mCharging = pluggedIn;
            mDrawable.setCharging(mCharging);
            mXDrawable.setCharging(mCharging);
            updateShowPercent();
        } else {
            updatePercentText();
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mDrawable.setPowerSaveEnabled(isPowerSave);
        mXDrawable.setPowerSave(isPowerSave);
        updateShowPercent();
    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    /**
     * Updates percent view by removing old one and reinflating if necessary
     */
    public void updatePercentView() {
        removeBatteryPercentView();
        updateShowPercent();
    }

    private void updatePercentText() {
        if (mBatteryController == null) {
            return;
        }

        if (mBatteryPercentView != null) {
            setPercentTextAtCurrentLevel();
        } else {
            setContentDescription(
                    getContext().getString(mCharging ? R.string.accessibility_battery_level_charging
                            : R.string.accessibility_battery_level, mLevel));
        }
    }

    private void setPercentTextAtCurrentLevel() {
        // Use the high voltage symbol ⚡ (u26A1 unicode) but prevent the system
        // to load its emoji colored variant with the uFE0E flag
        String bolt = "\u26A1";
        CharSequence mChargeIndicator =
                mCharging ? (bolt + " ") : "";

        String text = NumberFormat.getPercentInstance().format(mLevel / 100f);

        if (mShowBatteryEstimate != 0 && !mCharging) {
            mBatteryController.getEstimatedTimeRemainingString((String estimate) -> {
                if (estimate != null) {
                    if (mShowPercentMode == MODE_ON || mShowBatteryPercent == 2) {
                        mBatteryPercentView.setText(mChargeIndicator + text + " · " + estimate);
                    } else {
                        mBatteryPercentView.setText(mChargeIndicator + estimate);
                    }
                } else if (mShowPercentMode == MODE_ON || mShowBatteryPercent == 2) {
                    mBatteryPercentView.setText(text);
                } else {
                    mBatteryPercentView.setText("");
                }
                setContentDescription(getContext().getString(
                        R.string.accessibility_battery_level_with_estimate,
                        mLevel, estimate));
            });
        } else {
            mBatteryPercentView.setText(mChargeIndicator + text);
            setContentDescription(
                    getContext().getString(mCharging ? R.string.accessibility_battery_level_charging
                    : R.string.accessibility_battery_level, mLevel));
        }
    }

    private void removeBatteryPercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
    }

    private void updateShowPercent() {
        final boolean showing = mBatteryPercentView != null;
        final boolean drawPercentInside = mShowBatteryPercent == 1
                                    && !mCharging;
        final boolean addPecentView = mShowBatteryPercent == 2
                                    || mBatteryStyle == BATTERY_STYLE_TEXT
                                    || (mBatteryPercentCharging && mCharging)
                                    || mShowPercentMode == MODE_ON
                                    || mShowBatteryEstimate != 0;

        mDrawable.setShowPercent(drawPercentInside);
        mXDrawable.setShowPercent(drawPercentInside);
        updatePercentText();

        if (addPecentView) {
            if (!showing) {
                mBatteryPercentView = loadPercentView();
                if (mPercentageStyleId != 0) { // Only set if specified as attribute
                    mBatteryPercentView.setTextAppearance(mPercentageStyleId);
                }
                if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
                addView(mBatteryPercentView,
                        new ViewGroup.LayoutParams(
                                LayoutParams.WRAP_CONTENT,
                                LayoutParams.MATCH_PARENT));
            }
            updatePercentText();
            if (mBatteryStyle == BATTERY_STYLE_HIDDEN) {
                mBatteryPercentView.setPaddingRelative(0, 0, 0, 0);
            } else {
                Resources res = getContext().getResources();
                mBatteryPercentView.setPaddingRelative(
                        res.getDimensionPixelSize(R.dimen.battery_level_padding_start), 0, 0, 0);
            }
        } else {
            removeBatteryPercentView();
        }
    }

    public void updateVisibility() {
        if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT) {
            mBatteryIconView.setVisibility(View.GONE);
            mBatteryIconView.setImageDrawable(null);
            //setVisibility(View.GONE);
        } else {
            mBatteryIconView.setVisibility(View.VISIBLE);
            //setVisibility(View.VISIBLE);
            scaleBatteryMeterViews();
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        scaleBatteryMeterViews();
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    private void scaleBatteryMeterViews() {
        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = mBatteryStyle == BATTERY_STYLE_CIRCLE || 
                           mBatteryStyle == BATTERY_STYLE_DOTTED_CIRCLE ||
                           mBatteryStyle == BATTERY_STYLE_SOLID ?
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width) :
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));
        scaledLayoutParams.setMargins(0, 0, 0, marginBottom);

        mBatteryIconView.setLayoutParams(scaledLayoutParams);
    }

    public void updateBatteryStyle() {
        if (mBatteryStyle == BATTERY_STYLE_HIDDEN) return;

        if (mBatteryStyle == BATTERY_STYLE_PORTRAIT) {
            mBatteryIconView.setImageDrawable(mDrawable);
        } else {
            mXDrawable.setMeterStyle(mBatteryStyle);
            mBatteryIconView.setImageDrawable(mXDrawable);
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        float intensity = DarkIconDispatcher.isInArea(area, this) ? darkIntensity : 0;
        mNonAdaptedSingleToneColor = mDualToneHandler.getSingleColor(intensity);
        mNonAdaptedForegroundColor = mDualToneHandler.getFillColor(intensity);
        mNonAdaptedBackgroundColor = mDualToneHandler.getBackgroundColor(intensity);

        if (!mUseWallpaperTextColors) {
            updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor,
                    mNonAdaptedSingleToneColor);
        }
    }

    private void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mXDrawable.setColors(foregroundColor, backgroundColor);
        mTextColor = singleToneColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(singleToneColor);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        String powerSave = mDrawable == null ? null : mDrawable.getPowerSaveEnabled() + "";
        CharSequence percent = mBatteryPercentView == null ? null : mBatteryPercentView.getText();
        pw.println("  BatteryMeterView:");
        pw.println("    mDrawable.getPowerSave: " + powerSave);
        pw.println("    mBatteryPercentView.getText(): " + percent);
        pw.println("    mTextColor: #" + Integer.toHexString(mTextColor));
        pw.println("    mLevel: " + mLevel);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateShowPercent();
            if (TextUtils.equals(uri.getLastPathSegment(),
                    Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME)) {
                // update the text for sure if the estimate in the cache was updated
                updatePercentText();
            }
        }
    }
}
