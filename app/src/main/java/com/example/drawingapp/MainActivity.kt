package com.example.drawingapp

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var drawingView: DrawingView? = null
    private var currentPaintButton: ImageButton? = null
    private var progressDialog: Dialog? = null
    private var fillMode = false

    // Launcher to open the gallery and select an image
    private val openGalleryLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val backgroundImageView: ImageView = findViewById(R.id.iv_background)
                backgroundImageView.setImageURI(result.data?.data)
            }
        }

    // Permission launcher for camera and storage access
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach { (permission, isGranted) ->
                when {
                    isGranted -> handleGrantedPermission(permission)
                    else -> handleDeniedPermission(permission)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        setupDrawingView()
        setupUIControls()
    }

    private fun setupDrawingView() {
        drawingView = findViewById(R.id.drawing_view)
        drawingView?.setSizeForBrush(20f)
    }

    private fun setupUIControls() {
        val paintColorsLayout = findViewById<LinearLayout>(R.id.ll_paint_colors)
        currentPaintButton = paintColorsLayout[1] as ImageButton

        findViewById<ImageButton>(R.id.ib_brush).setOnClickListener { showBrushSizeSelectorDialog() }
        findViewById<ImageButton>(R.id.ib_gallery).setOnClickListener { requestPermissions() }
        findViewById<ImageButton>(R.id.ib_undo).setOnClickListener { drawingView?.onClickUndo() }
        findViewById<ImageButton>(R.id.ib_redo).setOnClickListener { drawingView?.onClickRedo() }
        findViewById<ImageButton>(R.id.ib_save).setOnClickListener { saveDrawing() }
        val bucket = findViewById<ImageButton>(R.id.ib_bucket)
        bucket.setOnClickListener {
            fillMode = !fillMode
            drawingView?.setFloodFillMode(fillMode)
            val color = if(fillMode) Color.BLACK else Color.GRAY
            bucket.setColorFilter(color)
        }

        applyWindowInsets()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun isReadStorageAllowed(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        if (isReadStorageAllowed()) {
            launchGalleryPicker()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions)
        }
    }

    private fun launchGalleryPicker() {
        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        openGalleryLauncher.launch(pickIntent)
    }

    private fun handleGrantedPermission(permission: String) {
        when (permission) {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES -> launchGalleryPicker()
            Manifest.permission.CAMERA -> Toast.makeText(this, "Camera permission granted", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleDeniedPermission(permission: String) {
        val message = when (permission) {
            Manifest.permission.READ_EXTERNAL_STORAGE -> "Storage permission denied"
            else -> "Camera permission denied"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showBrushSizeSelectorDialog() {
        val dialog = Dialog(this).apply {
            setContentView(R.layout.dialog_brush_size)
            setTitle("Brush Size")
        }

        dialog.findViewById<ImageButton>(R.id.ib_small_brush).setOnClickListener {
            drawingView?.setSizeForBrush(10f)
            dialog.dismiss()
        }
        dialog.findViewById<ImageButton>(R.id.ib_medium_brush).setOnClickListener {
            drawingView?.setSizeForBrush(20f)
            dialog.dismiss()
        }
        dialog.findViewById<ImageButton>(R.id.ib_large_brush).setOnClickListener {
            drawingView?.setSizeForBrush(30f)
            dialog.dismiss()
        }

        dialog.show()
    }

    fun paintClicked(view: View) {
        if (view != currentPaintButton) {
            val selectedButton = view as ImageButton
            val colorTag = selectedButton.tag.toString()

            drawingView?.setColor(colorTag)

            selectedButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.pallet_pressed))
            currentPaintButton?.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.pallet_normal))
            currentPaintButton = selectedButton
        }
    }

    private fun saveDrawing() {
        showProgressDialog()
        lifecycleScope.launch {
            val drawingContainer = findViewById<FrameLayout>(R.id.fl_drawing_container)
            val bitmap = getBitmapFromView(drawingContainer)
            val filePath = saveBitmapFile(bitmap)

            cancelProgressDialog()
            if (filePath.isNotEmpty()) {
                Toast.makeText(this@MainActivity, "Saved Successfully", Toast.LENGTH_LONG).show()
                shareImage(filePath)
            } else {
                Toast.makeText(this@MainActivity, "Something went wrong", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getBitmapFromView(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        view.background?.draw(canvas) ?: canvas.drawColor(Color.WHITE)
        view.draw(canvas)

        return bitmap
    }

    private suspend fun saveBitmapFile(bitmap: Bitmap?): String {
        return withContext(Dispatchers.IO) {
            if (bitmap == null) return@withContext ""

            try {
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, bytes)

                val file = File(
                    externalCacheDir?.absolutePath + File.separator + "DrawingApp_${System.currentTimeMillis() / 1000}.png"
                )
                FileOutputStream(file).use { it.write(bytes.toByteArray()) }

                file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }

    private fun showProgressDialog() {
        progressDialog = Dialog(this).apply {
            setContentView(R.layout.dialog_custom_progress)
            show()
        }
    }

    private fun cancelProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun shareImage(filePath: String) {
        MediaScannerConnection.scanFile(this, arrayOf(filePath), null) { _, uri ->
            Intent(Intent.ACTION_SEND).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "image/png"
            }.also {
                startActivity(Intent.createChooser(it, "Share"))
            }
        }
    }
}
