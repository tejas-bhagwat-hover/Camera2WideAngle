/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.camera2sample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import kotlin.math.max


class ImageViewerFragment : Fragment() {

    private val args: ImageViewerFragmentArgs by navArgs()

    private val bitmapOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        if (max(outHeight, outWidth) > DOWNSAMPLE_SIZE) {
            val scaleFactorX = outWidth / DOWNSAMPLE_SIZE + 1
            val scaleFactorY = outHeight / DOWNSAMPLE_SIZE + 1
            inSampleSize = max(scaleFactorX, scaleFactorY)
        }
    }

    private val bitmapTransformation: Matrix by lazy { decodeExifOrientation(args.orientation) }

    private val isDepth: Boolean by lazy { args.depth }

    private val bitmapList: MutableList<Bitmap> = mutableListOf()

    private fun imageViewFactory() = ImageView(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View = ViewPager2(requireContext()).apply {
        offscreenPageLimit = 2
        adapter = GenericListAdapter(
                bitmapList,
                itemViewFactory = { imageViewFactory() }) { view, item, _ ->
            view as ImageView

            Glide.with(view).load(item).fitCenter().into(view)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view as ViewPager2
        lifecycleScope.launch(Dispatchers.IO) {
            val inputBuffer = loadInputBuffer()
            addItemToViewPager(view, decodeBitmap(inputBuffer, 0, inputBuffer.size))
            if (isDepth) {
                try {
                    val depthStart = findNextJpegEndMarker(inputBuffer, 2)
                    addItemToViewPager(view, decodeBitmap(
                            inputBuffer, depthStart, inputBuffer.size - depthStart))

                    val confidenceStart = findNextJpegEndMarker(inputBuffer, depthStart)
                    addItemToViewPager(view, decodeBitmap(
                            inputBuffer, confidenceStart, inputBuffer.size - confidenceStart))

                } catch (exc: RuntimeException) {
                    Log.e(TAG, "Invalid start marker for depth or confidence data")
                }
            }
        }
    }

    private fun loadInputBuffer(): ByteArray {
        val inputFile = File(args.filePath)
        return BufferedInputStream(inputFile.inputStream()).let { stream ->
            ByteArray(stream.available()).also {
                stream.read(it)
                stream.close()
            }
        }
    }

    private fun addItemToViewPager(view: ViewPager2, item: Bitmap) = view.post {
        bitmapList.add(item)
        view.adapter?.notifyDataSetChanged()
    }

    private fun decodeBitmap(buffer: ByteArray, start: Int, length: Int): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(buffer, start, length, bitmapOptions)
        return Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, bitmapTransformation, true)
    }

    companion object {
        private val TAG = ImageViewerFragment::class.java.simpleName
        private const val DOWNSAMPLE_SIZE: Int = 1024  // 1MP
        private val JPEG_DELIMITER_BYTES = arrayOf(-1, -39)

        private fun findNextJpegEndMarker(jpegBuffer: ByteArray, start: Int): Int {
            assert(start >= 0) { "Invalid start marker: $start" }
            assert(jpegBuffer.size > start) {
                "Buffer size (${jpegBuffer.size}) smaller than start marker ($start)" }
            for (i in start until jpegBuffer.size - 1) {
                if (jpegBuffer[i].toInt() == JPEG_DELIMITER_BYTES[0] &&
                        jpegBuffer[i + 1].toInt() == JPEG_DELIMITER_BYTES[1]) {
                    return i + 2
                }
            }
            throw RuntimeException("Separator marker not found in buffer (${jpegBuffer.size})")
        }
    }

    private fun decodeExifOrientation(exifOrientation: Int): Matrix {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_NORMAL -> Unit
            ExifInterface.ORIENTATION_UNDEFINED -> Unit
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90F)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180F)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270F)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1F, 1F)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1F, -1F)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1F, 1F)
                matrix.postRotate(270F)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1F, 1F)
                matrix.postRotate(90F)
            }
            else -> Log.e(TAG, "Invalid orientation: $exifOrientation")
        }
        return matrix
    }
}
