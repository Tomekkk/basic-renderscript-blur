/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.basicrenderscript;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.support.v8.renderscript.*;

public class MainActivity extends Activity {
    /* Number of bitmaps that is used for renderScript thread and UI thread synchronization.
       Ideally, this can be reduced to 2, however in some devices, 2 buffers still showing tierings on UI.
       Investigating a root cause.
     */
    private final int NUM_BITMAPS = 3;
    private int mCurrentBitmap = 0;
    private Bitmap mBitmapIn;
    private Bitmap[] mBitmapsOut;
    private ImageView mImageView;

    private RenderScript mRS;
    private Allocation mInAllocation;
    private Allocation[] mOutAllocations;
    private ScriptIntrinsicBlur intrinsicBlur;
    private ScriptC_saturation mScript;

    private float blurValue = 0.0001f;
    private float saturationValue = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main_layout);

        /*
         * Initialize UI
         */
        mBitmapIn = loadBitmap(R.drawable.data);
        mBitmapsOut = new Bitmap[NUM_BITMAPS];
        for (int i = 0; i < NUM_BITMAPS; ++i) {
            mBitmapsOut[i] = mBitmapIn.copy(Bitmap.Config.ARGB_8888, false);
        }

        mImageView = (ImageView) findViewById(R.id.imageView);
        mImageView.setImageBitmap(mBitmapsOut[mCurrentBitmap]);
        mCurrentBitmap = 1;

        SeekBar seekbarBlur = (SeekBar) findViewById(R.id.seekBarBlur);
        seekbarBlur.setProgress(0);
        seekbarBlur.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                float max = 25.0f;
                float min = 0.0001f;
                blurValue = (float) ((max - min) * (progress / 100.0) + min);
                updateImage();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });


        SeekBar seekbarSaturation = (SeekBar) findViewById(R.id.seekBarSaturation);
        seekbarSaturation.setProgress(50);
        seekbarSaturation.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                float max = 2f;
                float min = 0.0f;
                saturationValue = (float) ((max - min) * (progress / 100.0) + min);
                updateImage();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        /*
         * Create renderScript
         */
        createScript();
        


    }

    /*
     * Initialize RenderScript
     * In the sample, it creates RenderScript kernel that performs saturation manipulation.
     */
    private void createScript() {
        //Initialize RS
        mRS = RenderScript.create(this);

        //Allocate buffers
        mInAllocation = Allocation.createFromBitmap(mRS, mBitmapIn);

        intrinsicBlur = ScriptIntrinsicBlur.create(mRS, mInAllocation.getElement());

        intrinsicBlur.setInput(mInAllocation);
        mScript = new ScriptC_saturation(mRS);


        mOutAllocations = new Allocation[NUM_BITMAPS];
        for (int i = 0; i < NUM_BITMAPS; ++i) {
            mOutAllocations[i] = Allocation.createFromBitmap(mRS, mBitmapIn);
        }


    }

    /*
     * In the AsyncTask, it invokes RenderScript intrinsics to do a filtering.
     * After the filtering is done, an operation blocks at Allocation.copyTo() in AsyncTask thread.
     * Once all operation is finished at onPostExecute() in UI thread, it can invalidate and update ImageView UI.
     */
    private class RenderScriptTask extends AsyncTask<Float, Integer, Integer> {
        Boolean issued = false;

        protected Integer doInBackground(Float... values) {
            int index = -1;
            if (isCancelled() == false) {
                issued = true;
                index = mCurrentBitmap;

                /*
                 * Set global variable in RS
                 */
                mScript.set_saturationValue(values[0]);

                /*
                 * Invoke saturation filter kernel
                 */
                mScript.forEach_saturation(mInAllocation, mOutAllocations[index]);

                intrinsicBlur.setRadius(values[1]);
                intrinsicBlur.forEach(mOutAllocations[index]);


                /*
                 * Copy to bitmap and invalidate image view
                 */
                mOutAllocations[index].copyTo(mBitmapsOut[index]);
                mCurrentBitmap = (mCurrentBitmap + 1) % NUM_BITMAPS;
            }
            return index;
        }

        void updateView(Integer result) {
            if (result != -1) {
                // Request UI update
                mImageView.setImageBitmap(mBitmapsOut[result]);
                mImageView.invalidate();
            }
        }

        protected void onPostExecute(Integer result) {
            updateView(result);
        }

        protected void onCancelled(Integer result) {
            if (issued) {
                updateView(result);
            }
        }
    }

    RenderScriptTask currentTask = null;

    /*
    Invoke AsynchTask and cancel previous task.
    When AsyncTasks are piled up (typically in slow device with heavy kernel),
    Only the latest (and already started) task invokes RenderScript operation.
     */
    private void updateImage() {
        if (currentTask != null)
            currentTask.cancel(false);
        currentTask = new RenderScriptTask();
        currentTask.execute(saturationValue, blurValue);
    }

    /*
    Helper to load Bitmap from resource
     */
    private Bitmap loadBitmap(int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(getResources(), resource, options);
    }

}
