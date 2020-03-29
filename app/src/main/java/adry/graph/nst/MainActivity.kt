/*
 * Copyright 2019 Google LLC
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

package adry.graph.nst

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.bumptech.glide.GenericTransitionOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import adry.graph.nst.camera.CameraFragment
import adry.graph.nst.R
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.Executors

// This is an arbitrary number we are using to keep tab of the permission
// request. Where an app has multiple context for requesting permission,
// this can help differentiate the different contexts
private const val REQUEST_CODE_PERMISSIONS = 10

// This is an array of all the permission specified in the manifest
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

private const val TAG = "NST_MainActivity"

class MainActivity :
  AppCompatActivity(),
  StyleFragment.OnListFragmentInteractionListener,
  CameraFragment.OnCaptureFinished {

  private var isRunningModel = false
  private val stylesFragment: StyleFragment = StyleFragment()
  private var selectedStyle: String = ""

  private lateinit var cameraFragment: CameraFragment
  private lateinit var viewModel: MLExecutionViewModel
  private lateinit var viewFinder: FrameLayout
  private lateinit var resultImageSwitcher: ImageSwitcher
  private lateinit var styleImageView: ImageView
  private lateinit var progressBar: ProgressBar

  private var lastSavedFile = ""
  private var useGPU = false
  private lateinit var styleTransferModelExecutor: StyleTransferModelExecutor
  private val inferenceThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val mainScope = MainScope()

  private var lensFacing = CameraCharacteristics.LENS_FACING_FRONT

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val toolbar: Toolbar = findViewById(R.id.toolbar)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayShowTitleEnabled(false)

    viewFinder = findViewById(R.id.view_finder)
    resultImageSwitcher = findViewById(R.id.result_imageswitcher)
    resultImageSwitcher.setInAnimation(this, android.R.anim.fade_in)
    resultImageSwitcher.setOutAnimation(this, android.R.anim.fade_out)
    resultImageSwitcher.setFactory(object : ViewSwitcher.ViewFactory {
      override fun   makeView() : View{
        val imageView  = ImageView(applicationContext)
        imageView.setBackgroundColor(Color.LTGRAY)
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
        val params=  FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        imageView.setLayoutParams(params)
        return imageView
      }
    })
    styleImageView = findViewById(R.id.style_imageview)
    progressBar = findViewById(R.id.progress_circular)
    val useGpuSwitch: Switch = findViewById(R.id.switch_use_gpu)

    // Request camera permissions
    if (allPermissionsGranted()) {
      addCameraFragment()
    } else {
      ActivityCompat.requestPermissions(
        this,
        REQUIRED_PERMISSIONS,
        REQUEST_CODE_PERMISSIONS
      )
    }

    viewModel = ViewModelProviders.of(this)
      .get(MLExecutionViewModel::class.java)

    viewModel.styledBitmap.observe(
      this,
      Observer { resultImage ->
        if (resultImage != null) {
          updateUIWithResults(resultImage)
        }
      }
    )

    mainScope.async(inferenceThread) {
      styleTransferModelExecutor =
        StyleTransferModelExecutor(this@MainActivity, useGPU)
      Log.d(TAG, "Executor created")
    }

    useGpuSwitch.setOnCheckedChangeListener { _, isChecked ->
      useGPU = isChecked
      // Disable control buttons to avoid running model before initialization
      enableControls(false)

      // Reinitialize TF Lite models with new GPU setting
      mainScope.async(inferenceThread) {
        styleTransferModelExecutor.close()
        styleTransferModelExecutor =
          StyleTransferModelExecutor(this@MainActivity, useGPU)

        // Re-enable control buttons
        runOnUiThread { enableControls(true) }
      }
    }


    styleImageView.setOnClickListener {
       stylesFragment.show(supportFragmentManager, "StylesFragment")
    }

    progressBar.visibility = View.INVISIBLE
    lastSavedFile = getLastTakenPicture()

    setupControls()
    enableControls(true)

    Log.d(TAG, "finished onCreate!!")
  }


  private fun updateUIWithResults(modelExecutionResult: ModelExecutionResult) {
    progressBar.visibility = View.INVISIBLE
    resultImageSwitcher.setImageDrawable(BitmapDrawable(resources, modelExecutionResult.styledImage))
    val logText: TextView = findViewById(R.id.log_view)
    logText.text = modelExecutionResult.executionLog
    enableControls(true)
  }


  private fun setImageView(imageView: ImageView, imagePath: String) {
    Glide.with(baseContext)
      .asBitmap()
      .load(imagePath)
      .transition(GenericTransitionOptions.with(android.R.anim.fade_in))
      .thumbnail(Glide
        .with(baseContext)
        .asBitmap()
        .load(lastSavedFile)
        .override(512, 512)
        .apply(RequestOptions().transform(CropTop())))
      .listener(object : RequestListener<Bitmap> {
        override fun onLoadFailed(
          e: GlideException?,
          model: Any?,
          target: Target<Bitmap>?,
          isFirstResource: Boolean
        ): Boolean {
          Log.e(TAG, "onLoadFailed", e)
          return false
        }

        override fun onResourceReady(
          resource: Bitmap?,
          model: Any?,
          target: Target<Bitmap>?,
          dataSource: DataSource?,
          isFirstResource: Boolean
        ): Boolean {
          return false

        }
      })
      .override(512, 512)
      .apply(RequestOptions().transform(CropTop()))
      .into(imageView)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    enableControls(false)

    // Reinitialize TF Lite models
    mainScope.async(inferenceThread) {
      styleTransferModelExecutor.close()
      styleTransferModelExecutor =
        StyleTransferModelExecutor(this@MainActivity, useGPU)
      runOnUiThread { enableControls(true) }
    }
  }


  private fun enableControls(enable: Boolean) {
    isRunningModel = !enable
  }

  private fun setupControls() {
    findViewById<ImageButton>(R.id.toggle_button).setOnClickListener {
      lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
        CameraCharacteristics.LENS_FACING_FRONT
      } else {
        CameraCharacteristics.LENS_FACING_BACK
      }
      cameraFragment.setFacingCamera(lensFacing)
      addCameraFragment()
    }
  }

  private fun addCameraFragment() {
    cameraFragment = CameraFragment.newInstance()
    cameraFragment.setFacingCamera(lensFacing)
    supportFragmentManager.popBackStack()
    supportFragmentManager.beginTransaction()
      .replace(R.id.view_finder, cameraFragment)
      .commit()
  }

  /**
   * Process result from permission request dialog box, has the request
   * been granted? If yes, start Camera. Otherwise display a toast
   */
  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_CODE_PERMISSIONS) {
      if (allPermissionsGranted()) {
        addCameraFragment()
        viewFinder.post { setupControls() }
      } else {
        Toast.makeText(
          this,
          "Permissions not granted by the user.",
          Toast.LENGTH_SHORT
        ).show()
        finish()
      }
    }
  }

  /**
   * Check if all permission specified in the manifest have been granted
   */
  private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(
      baseContext, it
    ) == PackageManager.PERMISSION_GRANTED
  }


  override fun onBitmapReceived(bitmap: Bitmap) {
    if (selectedStyle.isEmpty() || isRunningModel) {
      return
    }
    enableControls(false)
    progressBar.visibility = View.VISIBLE
    viewModel.onApplyStyle(
      baseContext, bitmap, selectedStyle, styleTransferModelExecutor,
      inferenceThread
    )
  }

  private fun updateSelectedStyleUI() {
    val selectedStyleLabel: TextView = findViewById(R.id.selected_style_text_view)
    selectedStyleLabel.text = getSelectedStyleName()
    setImageView(styleImageView, getUriFromAssetThumb(selectedStyle))
  }

  // And update once new picture is taken?
  // Alternatively we can provide user an ability to select any of taken photos
  private fun getLastTakenPicture(): String {
    val directory = baseContext.filesDir // externalMediaDirs.first()
    val files =
      directory.listFiles()?.filter { file -> file.absolutePath.endsWith(".jpg") }?.sorted()
    if (files == null || files.isEmpty()) {
      Log.d(TAG, "there is no previous saved file")
      return ""
    }

    val file = files.last()
    Log.d(TAG, "lastsavedfile: " + file.absolutePath)
    return file.absolutePath
  }

  override fun onListFragmentInteraction(item: String) {
    Log.d(TAG, item)
    selectedStyle = item
    stylesFragment.dismiss()

    updateSelectedStyleUI()
  }

  private fun getSelectedStyleName(): String {
    if (selectedStyle.isEmpty()) {
      return getString(R.string.no_style_selected)
    }
    var styleIndexStr : String = selectedStyle.replace("style", "")
    styleIndexStr = styleIndexStr.replace(".jpg", "")
    val styleIndex = styleIndexStr.toInt()
    return resources.getStringArray(R.array.style_name_array)[styleIndex]

  }

  private fun getUriFromAssetThumb(thumb: String): String {
    return "file:///android_asset/thumbnails/$thumb"
  }



  // this transformation is necessary to show the top square of the image as the model
  // will work on this part only, making the preview and the result show the same base
  class CropTop : BitmapTransformation() {
    override fun transform(
      pool: BitmapPool,
      toTransform: Bitmap,
      outWidth: Int,
      outHeight: Int
    ): Bitmap {
      Log.d(TAG, "BitmapTransformation transform : " + toTransform.width + "  " + toTransform.height)
      return if (toTransform.width == outWidth && toTransform.height == outHeight) {
        toTransform
      } else ImageUtils.scaleBitmapAndKeepRatio(
        toTransform,
        outWidth,
        outHeight
      )
    }

    override fun equals(other: Any?): Boolean {
      return other is CropTop
    }

    override fun hashCode(): Int {
      return ID.hashCode()
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
      messageDigest.update(ID_BYTES)
    }

    companion object {
      private const val ID = "adry.graph.nst.CropTop"
      private val ID_BYTES = ID.toByteArray(Charset.forName("UTF-8"))
    }
  }
}
