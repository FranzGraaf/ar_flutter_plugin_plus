package tech.graaf.franz.ar_flutter_plugin_plus

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.deserializeMatrix4
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.serializeAnchor
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.serializeHitResult
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.serializePose
import io.flutter.FlutterInjector
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import android.R
import android.view.ViewGroup
import java.io.FileInputStream


internal class AndroidARView(
        val activity: Activity,
        context: Context,
        messenger: BinaryMessenger,
        id: Int,
        creationParams: Map<String?, Any?>?
) : PlatformView {
    companion object {
        private val cachedImageDatabaseBytes: MutableMap<String, ByteArray> = mutableMapOf()
    }
    private val TAG = "AndroidARView"

    private lateinit var viewContext: Context
    private lateinit var rootView: FrameLayout
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ArCoreRenderer
    private lateinit var modelRenderer: ModelRenderer
    private var filamentTextureView: TextureView? = null

    private val sessionManagerChannel = MethodChannel(messenger, "arsession_$id")
    private val objectManagerChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorManagerChannel = MethodChannel(messenger, "aranchors_$id")

    private var session: Session? = null
    private var currentFrame: Frame? = null

    private val backgroundRenderer = BackgroundRenderer()
    private val axisRenderer = AxisRenderer()
    private val planeRenderer = SimplePlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()

    private var showFeaturePoints = false
    private var showPlanes = false
    private var showWorldOrigin = false
    private var showAnimatedGuide = false
    private var animatedGuide: View? = null

    private var isARInitialized = false
    private var mUserRequestedInstall = true

    private val tapLock = Any()
    private var queuedTap: MotionEvent? = null

    private var activeGestureNodeName: String? = null
    private var isPanning = false
    private var isRotating = false
    private var lastRotationAngle = 0f

    private var lastTrackingState: TrackingState? = null
    private var lastTrackingFailureReason: TrackingFailureReason? = null

    private var worldOriginAnchor: Anchor? = null
    private var lastWorldOriginMatrix: FloatArray? = null
    private var stableTrackingFrames = 0
    private var nonTrackingFrames = 0
    private val requiredStableTrackingFrames = 10
    private val activeAugmentedImages: MutableSet<String> = mutableSetOf()
    private val lastAugmentedImageUpdateMs: MutableMap<String, Long> = mutableMapOf()
    private var continuousImageTracking = false
    private var imageTrackingUpdateIntervalMs: Long = 100

    private val nonTrackingResetThreshold = 30
    private val modelIoExecutor = Executors.newFixedThreadPool(2)
    private val imageTrackingExecutor = Executors.newSingleThreadExecutor()
    private var androidModelScaleFactor: Float = 0.33f
    // Setting defaults
    private var enableRotation = false
    private var enablePans = false

    // Logical scene state (ARCore-only, no rendering)
    private val nodesByName: MutableMap<String, SimpleNode> = mutableMapOf()
    private val anchorsByName: MutableMap<String, Anchor> = mutableMapOf()
    private val anchorChildren: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val anchorTransformsByName: MutableMap<String, FloatArray> = mutableMapOf()
    // Cloud anchor handler
    private lateinit var cloudAnchorHandler: CloudAnchorHandler

    private var onFrameUpdateListener: ((Long) -> Unit)? = null
    private var isSessionResumed = false
    private var pendingSessionResume = false
    private lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks

    // Method channel handlers
    private val onSessionMethodCall =
            object : MethodChannel.MethodCallHandler {
                override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                    when (call.method) {
                        "init" -> {
                            initializeARView(call, result)
                        }
                        "setLightIntensityMultiplier" -> {
                            val multiplier = call.argument<Number>("multiplier")?.toFloat() ?: 1.0f
                            modelRenderer.setLightIntensityMultiplier(multiplier)
                            result.success(null)
                        }
                        "updateImageTrackingSettings" -> {
                            val argTrackingImagePaths: List<String>? = call.argument<List<String>>("trackingImagePaths")
                            val argContinuousImageTracking: Boolean? = call.argument<Boolean>("continuousImageTracking")
                            val argImageTrackingUpdateIntervalMs: Number? = call.argument<Number>("imageTrackingUpdateIntervalMs")

                            applyImageTrackingSettings(
                                imagePaths = argTrackingImagePaths,
                                imageByteMap = null,
                                imageDbPath = null,
                                continuous = argContinuousImageTracking,
                                intervalMs = argImageTrackingUpdateIntervalMs
                            )
                            result.success(null)
                        }
                        "precompileImageTrackingDatabase" -> {
                            val argTrackingImagePaths: List<String>? = call.argument<List<String>>("trackingImagePaths")
                            val imagePaths = argTrackingImagePaths ?: emptyList()
                            val session = session
                            if (session == null) {
                                result.error("Error", "Session not initialized", null)
                                return
                            }

                            imageTrackingExecutor.execute {
                                var success = true
                                try {
                                    val cacheKey = imageCacheKey(imagePaths)
                                    if (!cachedImageDatabaseBytes.containsKey(cacheKey)) {
                                        val (imageDatabase, buildSuccess) = buildImageDatabase(session, imagePaths)
                                        success = buildSuccess
                                        val bytes = serializeImageDatabase(imageDatabase)
                                        cachedImageDatabaseBytes[cacheKey] = bytes
                                    }
                                } catch (e: Exception) {
                                    success = false
                                    Log.e(TAG, "Error precompiling image database: ${e.message}")
                                }

                                activity.runOnUiThread {
                                    result.success(success)
                                }
                            }
                        }
                        "getAnchorPose" -> {
                            val anchorName = call.argument<String>("anchorId")
                            val anchor = if (anchorName != null) anchorsByName[anchorName] else null
                            if (anchor != null) {
                                result.success(serializePose(anchor.pose))
                            } else {
                                result.error("Error", "could not get anchor pose", null)
                            }
                        }
                        "getCameraPose" -> {
                            val cameraPose = currentFrame?.camera?.displayOrientedPose
                            if (cameraPose != null) {
                                result.success(serializePose(cameraPose))
                            } else {
                                result.error("Error", "could not get camera pose", null)
                            }
                        }
                        "snapshot" -> {
                            val width = glSurfaceView.width
                            val height = glSurfaceView.height
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                            // Create a handler thread to offload the processing of the image.
                            val handlerThread = HandlerThread("PixelCopier")
                            handlerThread.start()
                            // Copy the GLSurfaceView (camera + ARCore draws).
                            PixelCopy.request(glSurfaceView, bitmap, { copyResult: Int ->
                                if (copyResult == PixelCopy.SUCCESS) {
                                    try {
                                        val mainHandler = Handler(context.mainLooper)
                                        val runnable = Runnable {
                                            // Composite Filament TextureView on top if available.
                                            filamentTextureView?.let { overlay ->
                                                if (overlay.isAvailable) {
                                                    val overlayBitmap = overlay.getBitmap(width, height)
                                                    if (overlayBitmap != null) {
                                                        val canvas = Canvas(bitmap)
                                                        canvas.drawBitmap(overlayBitmap, 0f, 0f, null)
                                                        overlayBitmap.recycle()
                                                    }
                                                }
                                            }

                                            val stream = ByteArrayOutputStream()
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                                            val data = stream.toByteArray()
                                            result.success(data)
                                        }
                                        mainHandler.post(runnable)
                                    } catch (e: IOException) {
                                        result.error("e", e.message, e.stackTrace)
                                    }
                                } else {
                                    result.error("e", "failed to take screenshot", null)
                                }
                                handlerThread.quitSafely()
                            }, Handler(handlerThread.looper))
                        }
                        "dispose" -> {
                            dispose()
                        }
                        else -> {}
                    }
                }
            }
    private val onObjectMethodCall =
            object : MethodChannel.MethodCallHandler {
                override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                    when (call.method) {
                        "init" -> {
                            // objectManagerChannel.invokeMethod("onError", listOf("ObjectTEST from
                            // Android"))
                            val scaleFactor = call.argument<Double>("androidScaleFactor")
                            if (scaleFactor != null) {
                                androidModelScaleFactor = scaleFactor.toFloat()
                            }
                        }
                        "addNode" -> {
                            val dict_node: HashMap<String, Any>? = call.arguments as? HashMap<String, Any>
                            dict_node?.let{
                                addNode(it).thenAccept{status: Boolean ->
                                    result.success(status)
                                }.exceptionally { throwable ->
                                    result.error("e", throwable.message, throwable.stackTrace)
                                    null
                                }
                            }
                        }
                        "addNodeToPlaneAnchor" -> {
                            val dict_node: HashMap<String, Any>? = call.argument<HashMap<String, Any>>("node")
                            val dict_anchor: HashMap<String, Any>? = call.argument<HashMap<String, Any>>("anchor")
                            if (dict_node != null && dict_anchor != null) {
                                addNode(dict_node, dict_anchor).thenAccept{status: Boolean ->
                                    result.success(status)
                                }.exceptionally { throwable ->
                                    result.error("e", throwable.message, throwable.stackTrace)
                                    null
                                }
                            } else {
                                result.success(false)
                            }

                        }
                        "removeNode" -> {
                            val nodeName: String? = call.argument<String>("name")
                            nodeName?.let{
                                nodesByName.remove(nodeName)
                                anchorChildren.values.forEach { it.remove(nodeName) }
                                modelRenderer.removeModel(nodeName)
                                result.success(null)
                            }
                        }
                        "transformationChanged" -> {
                            val nodeName: String? = call.argument<String>("name")
                            val newTransformation: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")
                            nodeName?.let{ name ->
                                newTransformation?.let{ transform ->
                                    transformNode(name, transform)
                                    result.success(null)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
    private val onAnchorMethodCall =
            object : MethodChannel.MethodCallHandler {
                override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                    when (call.method) {
                        "addAnchor" -> {
                            val anchorType: Int? = call.argument<Int>("type")
                            if (anchorType != null){
                                when(anchorType) {
                                    0 -> { // Plane Anchor
                                        val transform: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")
                                        val name: String? = call.argument<String>("name")
                                        if ( name != null && transform != null){
                                            result.success(addPlaneAnchor(transform, name))
                                        } else {
                                            result.success(false)
                                        }

                                    }
                                    else -> result.success(false)
                                }
                            } else {
                                result.success(false)
                            }
                        }
                        "removeAnchor" -> {
                            val anchorName: String? = call.argument<String>("name")
                            anchorName?.let{ name ->
                                removeAnchor(name)
                            }
                        }
                        "initGoogleCloudAnchorMode" -> {
                            if (session != null) {
                                val config = Config(session)
                                config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                                config.focusMode = Config.FocusMode.AUTO
                                session?.configure(config)

                                cloudAnchorHandler = CloudAnchorHandler(session!!)
                            } else {
                                sessionManagerChannel.invokeMethod("onError", listOf("Error initializing cloud anchor mode: Session is null"))
                            }
                        }
                        "uploadAnchor" ->  {
                            val anchorName: String? = call.argument<String>("name")
                            val ttl: Int? = call.argument<Int>("ttl")
                            anchorName?.let {
                                val anchor = anchorsByName[anchorName]
                                if (ttl != null) {
                                    cloudAnchorHandler.hostCloudAnchorWithTtl(anchorName, anchor, cloudAnchorUploadedListener(), ttl!!)
                                } else {
                                    cloudAnchorHandler.hostCloudAnchor(anchorName, anchor, cloudAnchorUploadedListener())
                                }
                                result.success(true)
                            }

                        }
                        "downloadAnchor" -> {
                            val anchorId: String? = call.argument<String>("cloudanchorid")
                            anchorId?.let {
                                cloudAnchorHandler.resolveCloudAnchor(anchorId, cloudAnchorDownloadedListener())
                            }
                        }
                        else -> {}
                    }
                }
            }

    override fun getView(): View {
        return rootView
    }

    override fun dispose() {
        // Destroy AR session
        try {
            onPause()
            onDestroy()
            // ARCore session cleanup handled in onDestroy
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    init {
        viewContext = context

        rootView = FrameLayout(context)
        rootView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        glSurfaceView = GLSurfaceView(context)
        glSurfaceView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.preserveEGLContextOnPause = true
        renderer = ArCoreRenderer()
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        modelRenderer = ModelRenderer()

        rootView.addView(glSurfaceView)

        // Filament overlay removed for step-by-step testing

        glSurfaceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val width = glSurfaceView.width
            val height = glSurfaceView.height
            if (width > 0 && height > 0) {
                glSurfaceView.queueEvent {
                    renderer.onLayoutChanged(width, height)
                }
            }
        }

        glSurfaceView.setOnTouchListener { _, motionEvent ->
            val handled = handleGestureTouch(motionEvent)
            if (!handled) {
                onTap(null, motionEvent)
            }
            true
        }

        setupLifeCycle(context)

        sessionManagerChannel.setMethodCallHandler(onSessionMethodCall)
        objectManagerChannel.setMethodCallHandler(onObjectMethodCall)
        anchorManagerChannel.setMethodCallHandler(onAnchorMethodCall)

        // Don't call onResume here - wait for initializeARView to configure the session first
    }

    private fun setupLifeCycle(context: Context) {
        activityLifecycleCallbacks =
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(
                            activity: Activity,
                            savedInstanceState: Bundle?
                    ) {
                    }

                    override fun onActivityStarted(activity: Activity) {
                    }

                    override fun onActivityResumed(activity: Activity) {
                        if (isARInitialized) {
                            this@AndroidARView.onResume()
                        }
                    }

                    override fun onActivityPaused(activity: Activity) {
                        try {
                            session?.pause()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in onActivityPaused: ${e.message}")
                        }

                        this@AndroidARView.onPause()
                    }

                    override fun onActivityStopped(activity: Activity) {
                        // onStopped()
                        this@AndroidARView.onPause()
                    }

                    override fun onActivitySaveInstanceState(
                            activity: Activity,
                            outState: Bundle
                    ) {}

                    override fun onActivityDestroyed(activity: Activity) {
//                        onPause()
//                        onDestroy()
                    }
                }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
    }

    fun onResume() {
        glSurfaceView.onResume()
        if (!renderer.isSurfaceCreated) {
            pendingSessionResume = true
            return
        }
        resumeSessionInternal()
    }

    fun onPause() {
        // hide instructions view if no longer required
        if (showAnimatedGuide){
            animatedGuide?.let { guide ->
                val view = activity.findViewById(R.id.content) as ViewGroup
                view.removeView(guide)
            }
            showAnimatedGuide = false
        }
        glSurfaceView.onPause()
        activeAugmentedImages.clear()
        lastAugmentedImageUpdateMs.clear()
        isSessionResumed = false
    }

    private fun resumeSessionInternal() {
        try {
            session?.resume()
            isSessionResumed = true
            pendingSessionResume = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming session: ${e.message}")
        }
    }

    fun onDestroy() {
        try {
            worldOriginAnchor?.detach()
            worldOriginAnchor = null
            modelRenderer.destroy()
            session?.close()
            session = null

            activeAugmentedImages.clear()
            lastAugmentedImageUpdateMs.clear()

            modelIoExecutor.shutdown()
            imageTrackingExecutor.shutdown()

            onFrameUpdateListener = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun initializeARView(call: MethodCall, result: MethodChannel.Result) {
        // Unpack call arguments
        val argShowFeaturePoints: Boolean? = call.argument<Boolean>("showFeaturePoints")
        val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
        val argShowPlanes: Boolean? = call.argument<Boolean>("showPlanes")
        val argCustomPlaneTexturePath: String? = call.argument<String>("customPlaneTexturePath")
        val argShowWorldOrigin: Boolean? = call.argument<Boolean>("showWorldOrigin")
        val argHandleTaps: Boolean? = call.argument<Boolean>("handleTaps")
        val argHandleRotation: Boolean? = call.argument<Boolean>("handleRotation")
        val argHandlePans: Boolean? = call.argument<Boolean>("handlePans")
        val argShowAnimatedGuide: Boolean? = call.argument<Boolean>("showAnimatedGuide")
        val argTrackingImagePaths: List<String>? = call.argument<List<String>>("trackingImagePaths")
        val argContinuousImageTracking: Boolean? = call.argument<Boolean>("continuousImageTracking")
        val argImageTrackingUpdateIntervalMs: Number? = call.argument<Number>("imageTrackingUpdateIntervalMs")
        val argLightIntensityMultiplier: Number? = call.argument<Number>("lightIntensityMultiplier")
        val argTrackingImageByteMap: Map<String, ByteArray>? = call.argument<Map<String, ByteArray>>("trackingImageByteMap")
        val argTrackingImageDbPath: String? = call.argument<String>("trackingImageDbPath")

        // Set up frame update listener
        onFrameUpdateListener = { frameTimeNanos ->
            onFrame(frameTimeNanos)
        }

        // Ensure ARCore is installed
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, !mUserRequestedInstall)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                mUserRequestedInstall = false
                result.success(null)
                return
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            result.error("ARCore", "ARCore not installed", e)
            return
        } catch (e: UnavailableApkTooOldException) {
            result.error("ARCore", "ARCore APK too old", e)
            return
        } catch (e: UnavailableSdkTooOldException) {
            result.error("ARCore", "ARCore SDK too old", e)
            return
        } catch (e: UnavailableDeviceNotCompatibleException) {
            result.error("ARCore", "ARCore not compatible with this device", e)
            return
        }


        // Configure Plane scanning guide (UI handled by Flutter if needed)
        if (argShowAnimatedGuide == true) {
            showAnimatedGuide = true
        }

        // Create and configure ARCore session
        if (session == null) {
            session = Session(activity)
            renderer.session = session
            if (backgroundRenderer.textureId != -1) {
                session?.setCameraTextureName(backgroundRenderer.textureId)
            }
            renderer.onSessionReady()
        }

        val config = session!!.config
        when (argPlaneDetectionConfig) {
            1 -> config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            2 -> config.planeFindingMode = Config.PlaneFindingMode.VERTICAL
            3 -> config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            else -> config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        }
        config.depthMode = Config.DepthMode.DISABLED
        config.updateMode = Config.UpdateMode.BLOCKING
        config.focusMode = Config.FocusMode.AUTO
        session!!.configure(config)

        // Configure image tracking
        applyImageTrackingSettings(
            imagePaths = argTrackingImagePaths,
            imageByteMap = argTrackingImageByteMap,
            imageDbPath = argTrackingImageDbPath,
            continuous = argContinuousImageTracking,
            intervalMs = argImageTrackingUpdateIntervalMs
        )

        // Feature points/planes/world origin rendering is handled by the GL renderer
        showFeaturePoints = argShowFeaturePoints == true
        showPlanes = argShowPlanes == true
        showWorldOrigin = argShowWorldOrigin == true
        if (!showWorldOrigin) {
            worldOriginAnchor?.detach()
            worldOriginAnchor = null
        }

        // Configure gestures
        enableRotation = argHandleRotation == true
        enablePans = argHandlePans == true

        // Now that configuration is complete, start the AR session
        if (!isARInitialized) {
            onResume()
            isARInitialized = true
        }

        // Apply lighting multiplier if provided
        argLightIntensityMultiplier?.toFloat()?.let { multiplier ->
            modelRenderer.setLightIntensityMultiplier(multiplier)
        }

        result.success(null)
    }

    private fun onFrame(frameTimeNanos: Long) {
        val frame = currentFrame ?: return

        // hide instructions view if no longer required
        if (showAnimatedGuide){
            for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
                if (plane.trackingState === TrackingState.TRACKING) {
                    animatedGuide?.let { guide ->
                        val view = activity.findViewById(R.id.content) as ViewGroup
                        view.removeView(guide)
                    }
                    showAnimatedGuide = false
                    break
                }
            }
        }

        // Feature points are rendered in the GL renderer when enabled.

        val updatedAnchors = frame.updatedAnchors
        // Notify the cloudManager of all the updates.
        if (this::cloudAnchorHandler.isInitialized) { cloudAnchorHandler.onUpdate(updatedAnchors) }

        // Check for image tracking
        checkForTrackedImages()


    }

    private fun addNode(dict_node: HashMap<String, Any>, dict_anchor: HashMap<String, Any>? = null): CompletableFuture<Boolean>{
        val completableFutureSuccess: CompletableFuture<Boolean> = CompletableFuture()

        try {
            val nodeName = dict_node["name"] as String
            val transformation = dict_node["transformation"] as ArrayList<Double>
            val nodeType = dict_node["type"] as Int
            val uri = dict_node["uri"] as String

            val anchorName: String? = dict_anchor?.get("name") as? String
            nodesByName[nodeName] = SimpleNode(nodeName, transformation, nodeType, uri, anchorName)

            if (anchorName != null) {
                val children = anchorChildren.getOrPut(anchorName) { mutableListOf() }
                if (!children.contains(nodeName)) {
                    children.add(nodeName)
                }
            }

            loadModelForNode(nodeName)
            completableFutureSuccess.complete(true)
        } catch (e: java.lang.Exception) {
            completableFutureSuccess.completeExceptionally(e)
        }

        return completableFutureSuccess
    }

    private fun loadModelForNode(nodeName: String) {
        val node = nodesByName[nodeName] ?: return

        activity.runOnUiThread {
            ensureFilamentOverlay()
        }

        modelIoExecutor.execute {
            try {
                when (node.type) {
                    0 -> { // localGLTF2
                        val gltfBytes = readFlutterAssetBytes(node.uri)
                        val basePath = node.uri.substringBeforeLast("/", "")
                        val resourceMap = loadGltfResourcesFromAssets(gltfBytes, basePath)
                        // Parsing glTF with external resources is not yet wired; keep step-by-step.
                        glSurfaceView.queueEvent {
                            modelRenderer.loadGltf(node.name, gltfBytes, resourceMap)
                        }
                    }
                    1 -> { // localGLB
                        val glbBytes = readFlutterAssetBytes(node.uri)
                        glSurfaceView.queueEvent {
                            modelRenderer.loadGlb(node.name, glbBytes)
                        }
                    }
                    2 -> { // webGLB
                        val glbBytes = readUrlBytes(node.uri)
                        glSurfaceView.queueEvent {
                            modelRenderer.loadGlb(node.name, glbBytes)
                        }
                    }
                    3 -> { // fileSystemAppFolderGLB
                        val glbBytes = readFileBytes(node.uri)
                        glSurfaceView.queueEvent {
                            modelRenderer.loadGlb(node.name, glbBytes)
                        }
                    }
                    4 -> { // fileSystemAppFolderGLTF2
                        val gltfBytes = readFileBytes(node.uri)
                        val basePath = File(node.uri).parent ?: ""
                        val resourceMap = loadGltfResourcesFromFile(gltfBytes, basePath)
                        // Parsing glTF with external resources is not yet wired; keep step-by-step.
                        glSurfaceView.queueEvent {
                            modelRenderer.loadGltf(node.name, gltfBytes, resourceMap)
                        }
                    }
                    5 -> { // localPNG
                        val pngBytes = readFlutterAssetBytes(node.uri)
                        // Load the unlit material file from Flutter plugin assets
                        val unlitImageMaterialBytes = try {
                            readPluginAssetBytes("assets/image_unlit.filamat")
                        } catch (e: Exception) {
                            Log.e("AndroidARView", "Failed to load image_unlit.filamat: ${e.message}")
                            activity.runOnUiThread {
                                sessionManagerChannel.invokeMethod("onError", listOf("Failed to load material file: ${e.message}"))
                            }
                            return@execute
                        }
                        
                        if (unlitImageMaterialBytes.isEmpty()) {
                            Log.e("AndroidARView", "image_unlit.filamat is empty")
                            activity.runOnUiThread {
                                sessionManagerChannel.invokeMethod("onError", listOf("Material file is empty"))
                            }
                            return@execute
                        }
                        
                        Log.d("AndroidARView", "Loaded image_unlit.filamat: ${unlitImageMaterialBytes.size} bytes")

                        glSurfaceView.queueEvent {
                            modelRenderer.loadImage(node.name, pngBytes, unlitImageMaterialBytes)
                        }
                    }
                    else -> {
                        activity.runOnUiThread {
                            sessionManagerChannel.invokeMethod("onError", listOf("Unsupported node type ${node.type}"))
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error loading model: ${e.message}"))
                }
            }
        }
    }

    private fun readFlutterAssetBytes(path: String): ByteArray {
        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            loader.startInitialization(viewContext)
            loader.ensureInitializationComplete(viewContext, null)
        }
        val key = loader.getLookupKeyForAsset(path)
        return viewContext.assets.open(key).readBytes()
    }

    private fun readPluginAssetBytes(path: String): ByteArray {
        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            loader.startInitialization(viewContext)
            loader.ensureInitializationComplete(viewContext, null)
        }
        val key = loader.getLookupKeyForAsset(path, "ar_flutter_plugin_plus")
        return viewContext.assets.open(key).readBytes()
    }

    private fun readUrlBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        connection.connect()
        connection.inputStream.use { input ->
            return input.readBytes()
        }
    }

    private fun readFileBytes(path: String): ByteArray {
        val file = if (path.startsWith("file://")) File(Uri.parse(path).path ?: path) else File(path)
        return file.readBytes()
    }

    private fun loadGltfResourcesFromAssets(gltfBytes: ByteArray, basePath: String): Map<String, ByteArray> {
        val json = String(gltfBytes)
        val uris = extractGltfUris(json)
        val resources = mutableMapOf<String, ByteArray>()
        for (uri in uris) {
            val data = decodeDataUri(uri) ?: run {
                val assetPath = if (basePath.isNotEmpty()) "$basePath/$uri" else uri
                readFlutterAssetBytes(assetPath)
            }
            resources[uri] = data
        }
        return resources
    }

    private fun loadGltfResourcesFromFile(gltfBytes: ByteArray, basePath: String): Map<String, ByteArray> {
        val json = String(gltfBytes)
        val uris = extractGltfUris(json)
        val resources = mutableMapOf<String, ByteArray>()
        for (uri in uris) {
            val data = decodeDataUri(uri) ?: run {
                val filePath = if (basePath.isNotEmpty()) "$basePath/$uri" else uri
                readFileBytes(filePath)
            }
            resources[uri] = data
        }
        return resources
    }

    private fun extractGltfUris(json: String): List<String> {
        val pattern = Pattern.compile("\\\"uri\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val matcher = pattern.matcher(json)
        val uris = mutableListOf<String>()
        while (matcher.find()) {
            val uri = matcher.group(1)
            if (!uri.isNullOrBlank()) {
                uris.add(uri)
            }
        }
        return uris.distinct()
    }

    private fun decodeDataUri(uri: String): ByteArray? {
        if (!uri.startsWith("data:")) return null
        val parts = uri.split(",", limit = 2)
        if (parts.size != 2) return null
        return try {
            android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    private fun updateModelTransforms() {
        val nodeMatrix = FloatArray(16)
        val anchorMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val scaleMatrix = FloatArray(16)
        val scaledModelMatrix = FloatArray(16)

        nodesByName.values.forEach { node ->
            matrixFromTransform(node.transformation, nodeMatrix)
            val anchorName = node.anchorName
            if (anchorName != null) {
                val anchor = anchorsByName[anchorName]
                if (anchor != null && anchor.trackingState != TrackingState.STOPPED) {
                    anchor.pose.toMatrix(anchorMatrix, 0)
                    Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, nodeMatrix, 0)
                } else {
                    val fallbackAnchor = anchorTransformsByName[anchorName]
                    if (fallbackAnchor != null) {
                        System.arraycopy(fallbackAnchor, 0, anchorMatrix, 0, 16)
                        Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, nodeMatrix, 0)
                    } else {
                        System.arraycopy(nodeMatrix, 0, modelMatrix, 0, 16)
                    }
                }
            } else {
                System.arraycopy(nodeMatrix, 0, modelMatrix, 0, 16)
            }
            val modelScaleFactor = getModelScaleFactor(node.type)
            if (modelScaleFactor != 1.0f) {
                Matrix.setIdentityM(scaleMatrix, 0)
                Matrix.scaleM(scaleMatrix, 0, modelScaleFactor, modelScaleFactor, modelScaleFactor)
                Matrix.multiplyMM(scaledModelMatrix, 0, modelMatrix, 0, scaleMatrix, 0)
                modelRenderer.updateTransform(node.name, scaledModelMatrix)
            } else {
                modelRenderer.updateTransform(node.name, modelMatrix)
            }
        }
    }

    private fun getModelScaleFactor(nodeType: Int): Float {
        return when (nodeType) {
            0, 1, 2, 3, 4, 5 -> androidModelScaleFactor
            else -> 1.0f
        }
    }

    private fun matrixFromTransform(transform: ArrayList<Double>, outMatrix: FloatArray) {
        if (transform.size < 16) {
            Matrix.setIdentityM(outMatrix, 0)
            return
        }
        for (i in 0 until 16) {
            outMatrix[i] = transform[i].toFloat()
        }
    }

    private fun transformNode(name: String, transform: ArrayList<Double>) {
        val node = nodesByName[name]
        if (node != null) {
            node.transformation = transform
        }
    }

    private fun onTap(hitNode: Any?, motionEvent: MotionEvent?): Boolean {
        if (motionEvent != null && motionEvent.action == MotionEvent.ACTION_DOWN) {
            synchronized(tapLock) {
                queuedTap?.recycle()
                queuedTap = MotionEvent.obtain(motionEvent)
            }
            return true
        }
        return false
    }

    private fun handleGestureTouch(motionEvent: MotionEvent?): Boolean {
        if (motionEvent == null) return false
        if (!enablePans && !enableRotation) return false

        val frame = currentFrame ?: return false
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitPose = hitTestPlaneOrPoint(frame, motionEvent)
                val nearest = hitPose?.let { findNearestNode(it) } ?: findNearestNodeToCamera(frame)
                if (nearest == null) return false

                activeGestureNodeName = nearest.name
                isPanning = enablePans
                isRotating = false
                lastRotationAngle = 0f

                if (isPanning) {
                    objectManagerChannel.invokeMethod("onPanStart", nearest.name)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!enableRotation || motionEvent.pointerCount < 2) return isPanning
                if (activeGestureNodeName == null) {
                    val midEvent = motionEventMidpoint(motionEvent) ?: return false
                    val hitPose = hitTestPlaneOrPoint(frame, midEvent)
                    midEvent.recycle()
                    val nearest = hitPose?.let { findNearestNode(it) } ?: findNearestNodeToCamera(frame)
                    if (nearest == null) return false
                    activeGestureNodeName = nearest.name
                }
                isRotating = true
                isPanning = false
                lastRotationAngle = rotationAngle(motionEvent)
                activeGestureNodeName?.let { name ->
                    objectManagerChannel.invokeMethod("onRotationStart", name)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val nodeName = activeGestureNodeName ?: return false
                val node = nodesByName[nodeName] ?: return false

                if (isRotating && enableRotation && motionEvent.pointerCount >= 2) {
                    val currentAngle = rotationAngle(motionEvent)
                    val delta = currentAngle - lastRotationAngle
                    lastRotationAngle = currentAngle

                    rotateNode(node, delta)
                    objectManagerChannel.invokeMethod("onRotationChange", node.name)
                    return true
                }

                if (isPanning && enablePans) {
                    val hitPose = hitTestPlaneOrPoint(frame, motionEvent) ?: return false
                    moveNodeToPose(node, hitPose)
                    objectManagerChannel.invokeMethod("onPanChange", node.name)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val nodeName = activeGestureNodeName
                if (nodeName != null) {
                    val node = nodesByName[nodeName]
                    val transform = node?.transformation
                    if (isPanning && transform != null) {
                        objectManagerChannel.invokeMethod(
                            "onPanEnd",
                            mapOf("name" to nodeName, "transform" to transform)
                        )
                    }
                    if (isRotating && transform != null) {
                        objectManagerChannel.invokeMethod(
                            "onRotationEnd",
                            mapOf("name" to nodeName, "transform" to transform)
                        )
                    }
                }
                activeGestureNodeName = null
                isPanning = false
                isRotating = false
                lastRotationAngle = 0f
                return false
            }
            else -> return false
        }
    }

    private fun hitTestPlaneOrPoint(frame: Frame, motionEvent: MotionEvent): Pose? {
        val hitResults = frame.hitTest(motionEvent)
        val hit = hitResults.firstOrNull { result ->
            when (val trackable = result.trackable) {
                is Plane -> trackable.trackingState == TrackingState.TRACKING &&
                        trackable.isPoseInPolygon(result.hitPose)
                is Point -> trackable.trackingState == TrackingState.TRACKING
                else -> false
            }
        }
        return hit?.hitPose
    }

    private fun findNearestNode(hitPose: Pose): SimpleNode? {
        if (nodesByName.isEmpty()) return null
        var nearest: SimpleNode? = null
        var minDist = Float.MAX_VALUE
        val hitX = hitPose.tx()
        val hitY = hitPose.ty()
        val hitZ = hitPose.tz()

        nodesByName.values.forEach { node ->
            val pos = getNodeWorldPosition(node)
            val dx = pos[0] - hitX
            val dy = pos[1] - hitY
            val dz = pos[2] - hitZ
            val dist = dx * dx + dy * dy + dz * dz
            if (dist < minDist) {
                minDist = dist
                nearest = node
            }
        }
        return nearest
    }

    private fun findNearestNodeToCamera(frame: Frame): SimpleNode? {
        if (nodesByName.isEmpty()) return null
        val cameraPose = frame.camera.pose
        val camX = cameraPose.tx()
        val camY = cameraPose.ty()
        val camZ = cameraPose.tz()

        var nearest: SimpleNode? = null
        var minDist = Float.MAX_VALUE
        nodesByName.values.forEach { node ->
            val pos = getNodeWorldPosition(node)
            val dx = pos[0] - camX
            val dy = pos[1] - camY
            val dz = pos[2] - camZ
            val dist = dx * dx + dy * dy + dz * dz
            if (dist < minDist) {
                minDist = dist
                nearest = node
            }
        }
        return nearest
    }

    private fun getNodeWorldPosition(node: SimpleNode): FloatArray {
        val nodeMatrix = FloatArray(16)
        val anchorMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        matrixFromTransform(node.transformation, nodeMatrix)
        val anchorName = node.anchorName
        if (anchorName != null) {
            val anchor = anchorsByName[anchorName]
            if (anchor != null && anchor.trackingState != TrackingState.STOPPED) {
                anchor.pose.toMatrix(anchorMatrix, 0)
                Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, nodeMatrix, 0)
            } else {
                val fallbackAnchor = anchorTransformsByName[anchorName]
                if (fallbackAnchor != null) {
                    System.arraycopy(fallbackAnchor, 0, anchorMatrix, 0, 16)
                    Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, nodeMatrix, 0)
                } else {
                    System.arraycopy(nodeMatrix, 0, modelMatrix, 0, 16)
                }
            }
        } else {
            System.arraycopy(nodeMatrix, 0, modelMatrix, 0, 16)
        }
        return floatArrayOf(modelMatrix[12], modelMatrix[13], modelMatrix[14])
    }

    private fun moveNodeToPose(node: SimpleNode, hitPose: Pose) {
        val targetPose = if (node.anchorName != null) {
            val anchor = anchorsByName[node.anchorName]
            if (anchor != null && anchor.trackingState != TrackingState.STOPPED) {
                anchor.pose.inverse().compose(hitPose)
            } else {
                hitPose
            }
        } else {
            hitPose
        }

        val transform = node.transformation
        if (transform.size < 16) return
        transform[12] = targetPose.tx().toDouble()
        transform[13] = targetPose.ty().toDouble()
        transform[14] = targetPose.tz().toDouble()
        node.transformation = transform
    }

    private fun rotateNode(node: SimpleNode, deltaRadians: Float) {
        val transform = node.transformation
        if (transform.size < 16) return

        val matrix = FloatArray(16)
        matrixFromTransform(transform, matrix)
        val deltaDegrees = Math.toDegrees(deltaRadians.toDouble()).toFloat()
        Matrix.rotateM(matrix, 0, deltaDegrees, 0f, 1f, 0f)

        val updated = ArrayList<Double>(16)
        for (i in 0 until 16) {
            updated.add(matrix[i].toDouble())
        }
        node.transformation = updated
    }

    private fun rotationAngle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return kotlin.math.atan2(dy, dx)
    }

    private fun motionEventMidpoint(event: MotionEvent): MotionEvent? {
        if (event.pointerCount < 2) return null
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        val downTime = event.downTime
        val eventTime = event.eventTime
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
    }

    private fun addPlaneAnchor(transform: ArrayList<Double>, name: String): Boolean {
        val session = session ?: return false
        val components = try {
            deserializeMatrix4(transform)
        } catch (_: Exception) {
            return false
        }

        val future = CompletableFuture<Boolean>()
        glSurfaceView.queueEvent {
            try {
                val anchor = session.createAnchor(Pose(components.position, components.rotation))
                anchorsByName[name] = anchor
                anchorChildren.putIfAbsent(name, mutableListOf())
                val anchorMatrix = FloatArray(16)
                matrixFromTransform(transform, anchorMatrix)
                anchorTransformsByName[name] = anchorMatrix
                future.complete(true)
            } catch (e: Exception) {
                future.complete(false)
            }
        }

        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    private fun removeAnchor(name: String) {
        val anchor = anchorsByName.remove(name)
        anchor?.detach()
        anchorChildren.remove(name)
        anchorTransformsByName.remove(name)
    }

    private fun handleQueuedTap(frame: Frame) {
        val tap = synchronized(tapLock) {
            val value = queuedTap
            queuedTap = null
            value
        } ?: return

        try {
            val allHitResults = frame.hitTest(tap)
            val planeAndPointHitResults = allHitResults.filter { hit ->
                when (val trackable = hit.trackable) {
                    is Plane -> trackable.trackingState == TrackingState.TRACKING &&
                            trackable.isPoseInPolygon(hit.hitPose)
                    is Point -> trackable.trackingState == TrackingState.TRACKING
                    else -> false
                }
            }

            val serializedPlaneAndPointHitResults: ArrayList<HashMap<String, Any>> =
                ArrayList(planeAndPointHitResults.map { serializeHitResult(it) })

            activity.runOnUiThread {
                sessionManagerChannel.invokeMethod(
                    "onPlaneOrPointTap",
                    serializedPlaneAndPointHitResults
                )
            }
        } finally {
            tap.recycle()
        }
    }

    private inner class cloudAnchorUploadedListener: CloudAnchorHandler.CloudAnchorListener {
        override fun onCloudTaskComplete(anchorName: String?, anchor: Anchor?) {
            val cloudState = anchor!!.cloudAnchorState
            if (cloudState.isError) {
                Log.e(TAG, "Error uploading anchor, state $cloudState")
                sessionManagerChannel.invokeMethod("onError", listOf("Error uploading anchor, state $cloudState"))
                return
            }
            // Swap old and new anchor for the name
            if (anchorName != null) {
                val oldAnchor = anchorsByName[anchorName]
                anchorsByName[anchorName] = anchor!!
                oldAnchor?.detach()
            }

            val args = HashMap<String, String?>()
            args["name"] = anchorName
            args["cloudanchorid"] = anchor.cloudAnchorId
            anchorManagerChannel.invokeMethod("onCloudAnchorUploaded", args)
        }
    }

    private inner class cloudAnchorDownloadedListener: CloudAnchorHandler.CloudAnchorListener {
        override fun onCloudTaskComplete(anchorName: String?, anchor: Anchor?) {
            val cloudState = anchor!!.cloudAnchorState
            if (cloudState.isError) {
                Log.e(TAG, "Error downloading anchor, state $cloudState")
                sessionManagerChannel.invokeMethod("onError", listOf("Error downloading anchor, state $cloudState"))
                return
            }
            val anchorIdName = anchorName ?: ""
            anchorsByName[anchorIdName] = anchor!!
            anchorChildren.putIfAbsent(anchorIdName, mutableListOf())
            // Register new anchor on the Flutter side of the plugin
            anchorManagerChannel.invokeMethod(
                "onAnchorDownloadSuccess",
                serializeAnchor(anchorIdName, anchor, anchorChildren[anchorIdName] ?: emptyList()),
                object: MethodChannel.Result {
                override fun success(result: Any?) {
                    val registeredName = result.toString()
                    if (registeredName.isNotEmpty() && registeredName != anchorIdName) {
                        val existing = anchorsByName.remove(anchorIdName)
                        if (existing != null) {
                            anchorsByName[registeredName] = existing
                        }
                        val children = anchorChildren.remove(anchorIdName)
                        if (children != null) {
                            anchorChildren[registeredName] = children
                        }
                    }
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error while registering downloaded anchor at the AR Flutter plugin: $errorMessage"))
                }

                override fun notImplemented() {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error while registering downloaded anchor at the AR Flutter plugin"))
                }
            })
        }
    }

    private fun checkForTrackedImages() {
        val frame = currentFrame ?: return

        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

        val now = SystemClock.uptimeMillis()
        for (augmentedImage in updatedAugmentedImages) {
            when (augmentedImage.trackingState) {
                TrackingState.TRACKING -> {
                    if (augmentedImage.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                        val imageName = augmentedImage.name ?: "unknown"
                        val centerPose = augmentedImage.centerPose
                        val transformation = serializePose(centerPose)
                        val width = augmentedImage.extentX
                        val height = augmentedImage.extentZ
                        val shouldEmit = if (continuousImageTracking) {
                            val lastUpdate = lastAugmentedImageUpdateMs[imageName] ?: 0L
                            now - lastUpdate >= imageTrackingUpdateIntervalMs
                        } else {
                            !activeAugmentedImages.contains(imageName)
                        }

                        if (shouldEmit) {
                            emitImageDetection(imageName, transformation, width, height)
                            if (continuousImageTracking) {
                                lastAugmentedImageUpdateMs[imageName] = now
                            }
                        }

                        activeAugmentedImages.add(imageName)
                    }
                }
                TrackingState.PAUSED -> Unit
                TrackingState.STOPPED -> {
                    augmentedImage.name?.let { name ->
                        activeAugmentedImages.remove(name)
                        lastAugmentedImageUpdateMs.remove(name)
                    }
                }
            }
        }
    }

    private fun emitImageDetection(
        imageName: String,
        transformation: DoubleArray,
        width: Float,
        height: Float
    ) {
        val arguments = HashMap<String, Any>()
        arguments["imageName"] = imageName
        arguments["transformation"] = transformation
        arguments["width"] = width
        arguments["height"] = height

        activity.runOnUiThread {
            sessionManagerChannel.invokeMethod("onImageDetected", arguments)
        }
    }

    private fun applyImageTrackingSettings(
        imagePaths: List<String>?,
        imageByteMap: Map<String, ByteArray>?,
        imageDbPath: String?,
        continuous: Boolean?,
        intervalMs: Number?
    ) {
        if (continuous != null) {
            continuousImageTracking = continuous
        }
        if (intervalMs != null) {
            imageTrackingUpdateIntervalMs = intervalMs.toLong()
        }
        imagePaths?.let { setupImageTrackingAsync(it) }
        imageByteMap?.let { setupImageTrackingAsync(it) }
        imageDbPath?.let { setupImageTrackingAsync(it) }
    }

    private fun imageCacheKey(imagePaths: List<String>): String {
        return imagePaths.joinToString("|")
    }

    private fun imageCacheKey(imageByteMap: Map<String, ByteArray>): String {
        return imageByteMap.keys.joinToString("|")
    }

    private fun buildImageDatabase(session: Session, imagePaths: List<String>): Pair<AugmentedImageDatabase, Boolean> {
        val imageDatabase = AugmentedImageDatabase(session)
        var success = true


        for (imagePath in imagePaths) {
            try {
                val loader = FlutterInjector.instance().flutterLoader()
                val key = loader.getLookupKeyForAsset(imagePath)

                val inputStream = viewContext.assets.open(key)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap != null) {
                    val imageName = imagePath.substringAfterLast("/").substringBeforeLast(".")
                    val physicalWidth =
                        0.2f // 20cm - adjust based on your actual printed image size
                    val index = imageDatabase.addImage(imageName, bitmap, physicalWidth)

                    if (index == -1) {
                        Log.e(TAG, "Failed to add image to database: $imageName")
                        success = false
                    }
                } else {
                    Log.e(TAG, "Failed to load bitmap for: $imagePath")
                    success = false
                }
            } catch (e: Exception) {
                success = false
                when (e.javaClass.simpleName) {
                    "ImageInsufficientQualityException" -> {
                        activity.runOnUiThread {
                            sessionManagerChannel.invokeMethod(
                                "onError",
                                listOf("Image '$imagePath' has insufficient quality for AR tracking. Use images with more visual features like high contrast, corners, and varied textures.")
                            )
                        }
                    }

                    else -> {
                        Log.e(TAG, "Error loading image $imagePath: ${e.message}")
                    }
                }
                e.printStackTrace()
            }
        }


        return Pair(imageDatabase, success)
    }

    private fun buildImageDatabase(session: Session, imageByteMap: Map<String, ByteArray>): Pair<AugmentedImageDatabase, Boolean> {
        val imageDatabase = AugmentedImageDatabase(session)
        var success = true
        for ((key, value) in imageByteMap) {
            val bitmap = loadImageBitmap(value)
            if (bitmap != null) {
                val imageName = key
                val physicalWidth =
                    0.2f // 20cm - adjust based on your actual printed image size
                val index = imageDatabase.addImage(imageName, bitmap, physicalWidth)
                if (index == -1) {
                    Log.e(TAG, "Failed to add image to database: $imageName")
                    success = false
                }
            } else {
                Log.e(TAG, "Failed to load bitmap for: $key")
                success = false
            }
        }

        return Pair(imageDatabase, success)
    }

    private fun buildImageDatabase(session: Session, imageDbPath: String): Pair<AugmentedImageDatabase, Boolean> {
        var imageDatabase = AugmentedImageDatabase(session)
        var success = true
        try {
            val file = File(imageDbPath)
            imageDatabase = FileInputStream(file).use {
                AugmentedImageDatabase.deserialize(session, it)
            }
            success = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "IO exception loading augmented image file.", e)
            success = false
            false
        }

        return Pair(imageDatabase, success)
    }

    private fun loadImageBitmap(bitmapData: ByteArray): Bitmap? {
        try {
            return  android.graphics.BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.size)
        } catch (e: Exception) {
            Log.e(TAG, "IO exception loading augmented image bitmap.", e)
            return  null
        }
    }

    private fun serializeImageDatabase(imageDatabase: AugmentedImageDatabase): ByteArray {
        val outputStream = ByteArrayOutputStream()
        imageDatabase.serialize(outputStream)
        return outputStream.toByteArray()
    }

    private fun setupImageTrackingAsync(imagePaths: List<String>) {
        val session = session ?: return
        imageTrackingExecutor.execute {
            try {
                val cacheKey = imageCacheKey(imagePaths)
                val cachedBytes = cachedImageDatabaseBytes[cacheKey]
                if (cachedBytes != null) {
                    activity.runOnUiThread {
                        try {
                            val config = session.config
                            val inputStream = ByteArrayInputStream(cachedBytes)
                            val imageDatabase = AugmentedImageDatabase.deserialize(session, inputStream)
                            config.augmentedImageDatabase = imageDatabase
                            session.configure(config)
                            sessionManagerChannel.invokeMethod(
                                "onImageTrackingConfigured",
                                mapOf("success" to true, "cached" to true)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error applying cached image database: ${e.message}")
                            sessionManagerChannel.invokeMethod(
                                "onError",
                                listOf("Error applying cached image database: ${e.message}")
                            )
                            sessionManagerChannel.invokeMethod(
                                "onImageTrackingConfigured",
                                mapOf("success" to false)
                            )
                        }
                    }
                    return@execute
                }

                val (imageDatabase, success) = buildImageDatabase(session, imagePaths)
                val bytes = serializeImageDatabase(imageDatabase)
                cachedImageDatabaseBytes[cacheKey] = bytes

                activity.runOnUiThread {
                    try {
                        val config = session.config
                        config.augmentedImageDatabase = imageDatabase
                        session.configure(config)
                        sessionManagerChannel.invokeMethod(
                            "onImageTrackingConfigured",
                            mapOf("success" to success)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up image tracking: ${e.message}")
                        sessionManagerChannel.invokeMethod(
                            "onError",
                            listOf("Error setting up image tracking: ${e.message}")
                        )
                        sessionManagerChannel.invokeMethod(
                            "onImageTrackingConfigured",
                            mapOf("success" to false)
                        )
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Log.e(TAG, "Error setting up image tracking: ${e.message}")
                    sessionManagerChannel.invokeMethod(
                        "onError",
                        listOf("Error setting up image tracking: ${e.message}")
                    )
                    sessionManagerChannel.invokeMethod(
                        "onImageTrackingConfigured",
                        mapOf("success" to false)
                    )
                }
            }
        }
    }

    private fun setupImageTrackingAsync(imageByteMap: Map<String, ByteArray>) {
        val session = session ?: return
        imageTrackingExecutor.execute {
            try {
                val cacheKey = imageCacheKey(imageByteMap)
                val (imageDatabase, success) = buildImageDatabase(session, imageByteMap)
                val bytes = serializeImageDatabase(imageDatabase)
                cachedImageDatabaseBytes[cacheKey] = bytes

                activity.runOnUiThread {
                    try {
                        val config = session.config
                        config.augmentedImageDatabase = imageDatabase
                        session.configure(config)
                        sessionManagerChannel.invokeMethod(
                            "onImageTrackingConfigured",
                            mapOf("success" to success)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up image tracking: ${e.message}")
                        sessionManagerChannel.invokeMethod(
                            "onError",
                            listOf("Error setting up image tracking: ${e.message}")
                        )
                        sessionManagerChannel.invokeMethod(
                            "onImageTrackingConfigured",
                            mapOf("success" to false)
                        )
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Log.e(TAG, "Error setting up image tracking: ${e.message}")
                    sessionManagerChannel.invokeMethod(
                        "onError",
                        listOf("Error setting up image tracking: ${e.message}")
                    )
                    sessionManagerChannel.invokeMethod(
                        "onImageTrackingConfigured",
                        mapOf("success" to false)
                    )
                }
            }
        }
    }

    private fun setupImageTrackingAsync(imageDbPath: String) {
        val session = session ?: return
        imageTrackingExecutor.execute {
            try {
                val (imageDatabase, success) = buildImageDatabase(session, imageDbPath)

                activity.runOnUiThread {
                    try {
                        val config = session.config
                        config.augmentedImageDatabase = imageDatabase
                        session.configure(config)
                        sessionManagerChannel.invokeMethod(
                            "onImageTrackingConfigured",
                            mapOf("success" to success)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up image tracking: ${e.message}")
                        sessionManagerChannel.invokeMethod(
                            "onError",
                            listOf("Error setting up image tracking: ${e.message}")
                        )
                        sessionManagerChannel.invokeMethod(
                            "onImageTrackingConfigured",
                            mapOf("success" to false)
                        )
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Log.e(TAG, "Error setting up image tracking: ${e.message}")
                    sessionManagerChannel.invokeMethod(
                        "onError",
                        listOf("Error setting up image tracking: ${e.message}")
                    )
                    sessionManagerChannel.invokeMethod(
                        "onImageTrackingConfigured",
                        mapOf("success" to false)
                    )
                }
            }
        }
    }

    private inner class ArCoreRenderer : GLSurfaceView.Renderer {
        var session: Session? = null
        private var viewportWidth: Int = 0
        private var viewportHeight: Int = 0
        private var lastRotation: Int = -1
        var isSurfaceCreated = false
            private set
        private var hasSurface = false
        private var displayGeometryApplied = false

        fun onSessionReady() {
            displayGeometryApplied = false
            applyDisplayGeometryIfAvailable()
            if (backgroundRenderer.textureId != -1) {
                session?.setCameraTextureName(backgroundRenderer.textureId)
            }
        }

        fun onLayoutChanged(width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            displayGeometryApplied = false
            if (hasSurface) {
                GLES20.glViewport(0, 0, width, height)
            }
            applyDisplayGeometryIfAvailable()
        }

        private fun applyDisplayGeometryIfAvailable() {
            if (viewportWidth <= 0 || viewportHeight <= 0) {
                return
            }
            val rotation = activity.windowManager.defaultDisplay.rotation
            if (!displayGeometryApplied || rotation != lastRotation) {
                lastRotation = rotation
                session?.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
                displayGeometryApplied = true
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            backgroundRenderer.initialize()
            axisRenderer.initialize()
            planeRenderer.initialize()
            pointCloudRenderer.initialize()
            session?.setCameraTextureName(backgroundRenderer.textureId)
            isSurfaceCreated = true
            if (pendingSessionResume) {
                activity.runOnUiThread {
                    resumeSessionInternal()
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            hasSurface = true
            displayGeometryApplied = false
            GLES20.glViewport(0, 0, width, height)
            if (width > 0 && height > 0) {
                lastRotation = activity.windowManager.defaultDisplay.rotation
                session?.setDisplayGeometry(lastRotation, width, height)
                displayGeometryApplied = true
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            try {
                if (!isSessionResumed) {
                    return
                }
                if (viewportWidth <= 0 || viewportHeight <= 0) {
                    val width = glSurfaceView.width
                    val height = glSurfaceView.height
                    if (width > 0 && height > 0) {
                        onLayoutChanged(width, height)
                    } else {
                        return
                    }
                }
                applyDisplayGeometryIfAvailable()
                val frame = session?.update() ?: return
                currentFrame = frame
                handleQueuedTap(frame)
                onFrameUpdateListener?.invoke(frame.timestamp)
                val camera = frame.camera
                val cameraTrackingState = camera.trackingState
                val trackingFailureReason = if (cameraTrackingState == TrackingState.PAUSED) {
                    camera.trackingFailureReason
                } else {
                    com.google.ar.core.TrackingFailureReason.NONE
                }

                if (lastTrackingState != cameraTrackingState ||
                    lastTrackingFailureReason != trackingFailureReason) {
                    lastTrackingState = cameraTrackingState
                    lastTrackingFailureReason = trackingFailureReason
                    activity.runOnUiThread {
                        sessionManagerChannel.invokeMethod(
                            "onTrackingState",
                            mapOf(
                                "state" to cameraTrackingState.name,
                                "reason" to trackingFailureReason.name
                            )
                        )
                    }
                }
                backgroundRenderer.draw(frame)

                val viewMatrix = FloatArray(16)
                val projectionMatrix = FloatArray(16)
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

                if (showPlanes) {
                    val planes = session?.getAllTrackables(Plane::class.java) ?: emptyList()
                    planeRenderer.draw(planes, viewMatrix, projectionMatrix)
                }

                if (showFeaturePoints) {
                    val pointCloud = frame.acquirePointCloud()
                    pointCloudRenderer.draw(pointCloud, viewMatrix, projectionMatrix)
                    pointCloud.release()
                }

                if (showWorldOrigin) {
                    val cameraTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (cameraTracking) {
                        stableTrackingFrames++
                        nonTrackingFrames = 0
                    } else {
                        stableTrackingFrames = 0
                        nonTrackingFrames++
                        if (nonTrackingFrames >= nonTrackingResetThreshold) {
                            worldOriginAnchor?.detach()
                            worldOriginAnchor = null
                            lastWorldOriginMatrix = null
                        }
                    }

                    if (worldOriginAnchor == null && stableTrackingFrames >= requiredStableTrackingFrames) {
                        worldOriginAnchor = session?.createAnchor(Pose.IDENTITY)
                    }

                    worldOriginAnchor?.let { anchor ->
                        if (anchor.trackingState == TrackingState.TRACKING) {
                            val modelMatrix = FloatArray(16)
                            anchor.pose.toMatrix(modelMatrix, 0)
                            lastWorldOriginMatrix = modelMatrix
                            axisRenderer.draw(frame, modelMatrix)
                        } else if (anchor.trackingState != TrackingState.STOPPED) {
                            lastWorldOriginMatrix?.let { frozenMatrix ->
                                axisRenderer.draw(frame, frozenMatrix)
                            }
                        }
                    }
                }

                updateModelTransforms()
                modelRenderer.updateCamera(viewMatrix, projectionMatrix)
                modelRenderer.updateLightEstimate(frame.lightEstimate)
            } catch (e: Exception) {
                // Ignore frame update errors when session is paused
            }
        }
    }

    private fun ensureFilamentOverlay() {
        if (filamentTextureView != null) return
        filamentTextureView = TextureView(viewContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isOpaque = false
        }
        rootView.addView(filamentTextureView)
        filamentTextureView?.let { modelRenderer.attachTextureView(it) }
    }

    private data class SimpleNode(
        val name: String,
        var transformation: ArrayList<Double>,
        val type: Int,
        val uri: String,
        var anchorName: String?
    )

}




