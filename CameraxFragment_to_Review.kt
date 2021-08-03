package com.trustbraces.projecttwo

import android.R.attr
import android.R.attr.rotation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


@RequiresApi(Build.VERSION_CODES.R)

class CameraFragment : Fragment() {
    private lateinit var cameraContainer: ConstraintLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var outputDirectory: File

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager
    private val displayManager by lazy { requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager}
    private lateinit var cameraExecutor: ExecutorService

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let{ view ->
            val rot = view.display.rotation
            imageCapture?.targetRotation = rot
            Toast.makeText(requireContext(), " rotation: $rot", Toast.LENGTH_SHORT).show()

        } ?: Unit
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        requireActivity().findViewById<ConstraintLayout>(R.id.bottom_navigation).isVisible = false
        if (!isAllPermissionsGranted()){
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUESTED_CODE_PERMISSIONS)
        }

    }

    override fun onCreateView( inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraContainer = view as ConstraintLayout
        viewFinder = cameraContainer.findViewById(R.id.view_finder)
        cameraExecutor = Executors.newSingleThreadExecutor()
        //displayManager.registerDisplayListener(displayListener, null)
        windowManager = requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
        outputDirectory = MainActivity.getOutputDirectory(requireContext())
        viewFinder.post {
            displayId = viewFinder.display.displayId
            updateCameraUi()
            setUpCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(displayListener, null)
        if (!isAllPermissionsGranted()){
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUESTED_CODE_PERMISSIONS)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if(cameraExecutor != null) {
            cameraExecutor.shutdown()
        }
        //displayManager.unregisterDisplayListener(displayListener)
        //requireActivity().findViewById<ConstraintLayout>(R.id.bottom_navigation).isVisible = true
    }

    override fun onRequestPermissionsResult( requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if( requestCode != REQUESTED_CODE_PERMISSIONS) {
            Toast.makeText(requireContext(), "Permissions denied by user!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCameraUi() {
        cameraContainer.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            cameraContainer.removeView(it)
        }
        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, cameraContainer)
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {
            imageCapture?.let {
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
                val metadata = ImageCapture.Metadata().apply {
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()
                imageCapture!!.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                            if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                                requireActivity().sendBroadcast( Intent(android.hardware.Camera.ACTION_NEW_PICTURE))
                            }
                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                            MediaScannerConnection.scanFile(context, arrayOf(savedUri.toFile().absolutePath), arrayOf(mimeType)) { _, uri ->
                                Toast.makeText( requireContext(),"Image scanned $uri", Toast.LENGTH_SHORT).show()
                            }
                            //correctOrientaion(photoFile.absolutePath)
                            openResultFragment(savedUri)

                        }
                        override fun onError(exception: ImageCaptureException) {
                            Toast.makeText(requireActivity(), "Image Capture Failed!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
        controls.findViewById<ImageButton>(R.id.camera_switch_button).let {
            it.isEnabled = false
            it.setOnClickListener {
                lensFacing = if(CameraSelector.LENS_FACING_BACK == lensFacing) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                bindCameraUseCases()
            }
        }
    }
    private fun bindCameraUseCases() {
        val metrics = windowManager.currentWindowMetrics.bounds
        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        val rotation = cameraContainer.rotation.toInt()
        Log.i("rotation" , rotation.toString())
        cameraProvider = cameraProvider ?: throw IllegalStateException("Camera Initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
        Log.i("CameraX preview", rotation.toString())
        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
        Log.i("CameraX capture", rotation.toString())
        cameraProvider!!.unbindAll()
        try {
            camera = cameraProvider!!.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Log.e(TAG, "binding failed", e)
        }
    }
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider= cameraProviderFuture.get()
            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Both cameras are unavailable!")
            }
            updateCameraSwitchButton()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onDestroy() {
        super.onDestroy()
        if(cameraExecutor != null) {
            cameraExecutor.shutdown()
        }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if(abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun updateCameraSwitchButton() {
        val switchCameraButton = cameraContainer.findViewById<ImageButton>(R.id.camera_switch_button)
        try {
            switchCameraButton.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (e: CameraInfoUnavailableException) {
            switchCameraButton.isEnabled = false
        }
    }

    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    private fun isAllPermissionsGranted() = REQUIRED_PERMISSIONS.all{
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun openResultFragment(uri: Uri) {
        val resultFragment = ResultFragment()
        val args: Bundle = Bundle()
        args.putString("uri", uri.toString())
        args.putString("callingFragment", "cameraFragment")
        resultFragment.arguments = args
        parentFragmentManager.beginTransaction().replace(R.id.camera_container, resultFragment).commit()
    }

    fun correctOrientaion(fileName: String) {
        var rotation: Int = 0
        try {

            val exifInterface = ExifInterface(fileName)
            var orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exifInterface.saveAttributes()
            orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            Log.i(TAG, "Rotaion $rotation: Orientation: $orientation")
        } catch (e: IOException) {
            e.message?.let { Log.e(TAG, it) }
        }
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val REQUESTED_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)

        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.ENGLISH).format(System.currentTimeMillis()) + extension)
    }
}