package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class WatchFace extends CanvasWatchFaceService {
    private static String TAG = WatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";

        private static final String KEY_HIGH = "KEY_MAX_TEMP";
        private static final String KEY_LOW = "KEY_MIN_TEMP";
        private static final String KEY_WEATHER_ID = "KEY_WEATHER_ID";

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextTimePaint;
        Paint mTextTimeSecondsPaint;
        Paint mTextDatePaint;
        Paint mTextDateAmbientPaint;
        Paint mTextTempHighPaint;
        Paint mTextTempLowPaint;
        Paint mTextTempLowAmbientPaint;

        Bitmap mWeatherIcon;
        String mWeatherHigh;
        String mWeatherLow;

        boolean mAmbient;

        private Calendar mCalendar;

        float mTimeYOffset;
        float mDateYOffset;
        float mDividerYOffset;
        float mWeatherYOffset;

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);

            mBackgroundPaint = new Paint();

            mTextTimePaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);
            mTextTimeSecondsPaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);
            mTextDatePaint = createTextPaint(resources.getColor(R.color.primary_light), NORMAL_TYPEFACE);
            mTextDateAmbientPaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);
            mTextTempHighPaint = createTextPaint(Color.WHITE, BOLD_TYPEFACE);
            mTextTempLowPaint = createTextPaint(resources.getColor(R.color.primary_light), NORMAL_TYPEFACE);
            mTextTempLowAmbientPaint = createTextPaint(Color.WHITE, NORMAL_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFace.this.getResources();
            boolean isRound = insets.isRound();

            mDateYOffset = resources.getDimension(isRound
                    ? R.dimen.date_y_offset_round : R.dimen.date_y_offset);
            mDividerYOffset = resources.getDimension(isRound
                    ? R.dimen.divider_y_offset_round : R.dimen.divider_y_offset);
            mWeatherYOffset = resources.getDimension(isRound
                    ? R.dimen.weather_y_offset_round : R.dimen.weather_y_offset);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_size_round : R.dimen.time_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_size_round : R.dimen.date_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_size_round : R.dimen.temp_size);

            mTextTimePaint.setTextSize(timeTextSize);
            mTextTimeSecondsPaint.setTextSize((float) (tempTextSize * 0.80));
            mTextDatePaint.setTextSize(dateTextSize);
            mTextDateAmbientPaint.setTextSize(dateTextSize);
            mTextTempHighPaint.setTextSize(tempTextSize);
            mTextTempLowAmbientPaint.setTextSize(tempTextSize);
            mTextTempLowPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
                    mTextDatePaint.setAntiAlias(!inAmbientMode);
                    mTextDateAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempHighPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (mAmbient) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);


            boolean is24Hour = DateFormat.is24HourFormat(WatchFace.this);
            int minute = mCalendar.get(Calendar.MINUTE);
            int am_pm  = mCalendar.get(Calendar.AM_PM);

            String time;
            if (is24Hour) {
                int hour = mCalendar.get(Calendar.HOUR_OF_DAY);
                time = String.format("%02d:%02d", hour, minute);
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                time = String.format("%d:%02d", hour, minute);
            }
            String amPmText = Utility.getAmPmString(getResources(), am_pm);
            float timeLen = mTextTimePaint.measureText(time);
            float xOffsetTime = timeLen / 2;
            if (!is24Hour) {
                xOffsetTime = xOffsetTime + (mTextTimeSecondsPaint.measureText(amPmText) / 2);
            }
                float xOffsetTimeFromCenter = bounds.centerX() - xOffsetTime;
                canvas.drawText(time, xOffsetTimeFromCenter, mTimeYOffset, mTextTimePaint);

            if (!is24Hour) {
                canvas.drawText(amPmText, xOffsetTimeFromCenter + timeLen + 5, mTimeYOffset, mTextTimeSecondsPaint);
            }
            Paint datePaint = mAmbient ? mTextDateAmbientPaint : mTextDatePaint;

                Resources resources = getResources();

                String dayOfWeekString = Utility.getDayOfWeekString(resources, mCalendar.get(Calendar.DAY_OF_WEEK));
                String monthOfYearString = Utility.getMonthOfYearString(resources, mCalendar.get(Calendar.MONTH));

                int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
                int year = mCalendar.get(Calendar.YEAR);
           //* changes the background color of the watchface everyday ---------------------------------------
           switch(dayOfWeekString)
            {
                case "SUN":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background0));
                    break;
                case "MON":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background1));
                    break;
                case "TUE":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background2));
                break;
                case "WED":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background3));
                break;
                case "THU":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background4));
                break;
                case "FRI":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background5));
                break;
                case "SAT":
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background6));
                    break;

            }
                String dateText = String.format("%s, %s %d %d", dayOfWeekString, monthOfYearString, dayOfMonth, year);
                float xOffsetDate = datePaint.measureText(dateText) / 2;
                canvas.drawText(dateText, bounds.centerX() - xOffsetDate, mDateYOffset, datePaint);

                if (mWeatherHigh != null && mWeatherLow != null && mWeatherIcon != null) {
                    canvas.drawLine(bounds.centerX() - 20, mDividerYOffset, bounds.centerX() + 20, mDividerYOffset, datePaint);

                    float highTextLen = mTextTempHighPaint.measureText(mWeatherHigh);

                    if (mAmbient) {
                        float lowTextLen = mTextTempLowAmbientPaint.measureText(mWeatherLow);
                        float xOffset = bounds.centerX() - ((highTextLen + lowTextLen + 20) / 2);
                        canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
                        canvas.drawText(mWeatherLow, xOffset + highTextLen + 20, mWeatherYOffset, mTextTempLowAmbientPaint);
                    } else {
                        float xOffset = bounds.centerX() - (highTextLen / 2);
                        canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
                        canvas.drawText(mWeatherLow, bounds.centerX() + (highTextLen / 2) + 20, mWeatherYOffset, mTextTempLowPaint);
                        float iconXOffset = bounds.centerX() - ((highTextLen / 2) + mWeatherIcon.getWidth() + 30);
                        canvas.drawBitmap(mWeatherIcon, iconXOffset, mWeatherYOffset - mWeatherIcon.getHeight(), null);
                }
            }
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            trigger();
            Log.d(TAG, "connected to Google Playservice API client");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "suspended");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d("Data Changed","Data Changed");
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(TAG, path);
                    if (path.equals(WEATHER_PATH)) {
                        if (dataMap.containsKey(KEY_HIGH)) {
                            mWeatherHigh = dataMap.getString(KEY_HIGH);
                            Log.d(TAG, "High = " + mWeatherHigh);
                        } else {
                            Log.d(TAG, "Data UnAvailable - High temp");
                        }

                        if (dataMap.containsKey(KEY_LOW)) {
                            mWeatherLow = dataMap.getString(KEY_LOW);
                            Log.d(TAG, "Low = " + mWeatherLow);
                        } else {
                            Log.d(TAG, "Data UnAvailable - Low temp");
                        }

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            Drawable b = getResources().getDrawable(Utility.getIconResourceForWeatherCondition(weatherId));
                            Bitmap icon = null;
                            if (((BitmapDrawable) b) != null) {
                                icon = ((BitmapDrawable) b).getBitmap();
                            }
                            float scaledWidth = (mTextTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                            mWeatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mTextTempHighPaint.getTextSize(), true);

                        } else {
                            Log.d(TAG, "Data UnAvailable Weather ID ");
                        }

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "connection failed !!!!");
        }

        public void trigger() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString("DATA", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "weather data unsuccessful");
                            } else {
                                Log.d(TAG, "weather data success");
                            }
                        }
                    });
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFace.Engine> mWeakReference;

        public EngineHandler(WatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}