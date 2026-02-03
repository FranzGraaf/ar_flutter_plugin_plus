package tech.graaf.franz.ar_flutter_plugin_plus

import android.graphics.BitmapFactory
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan

internal class ModelRenderer {
    private val tag = "ModelRenderer"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var view: View? = null
    private var scene: Scene? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null
    private var swapChainSurface: Surface? = null

    private var uiHelper: UiHelper? = null
    private var textureView: TextureView? = null
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    private var lightEntity: Int = 0
    private var fillLightEntity: Int = 0
    private var lightIntensityMultiplier: Float = 1.0f

    private val cameraLock = Any()
    private val cameraViewMatrix = FloatArray(16)
    private val cameraProjectionMatrix = FloatArray(16)
    private var hasCamera = false

    private var materialProvider: MaterialProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null

    private data class ModelAsset(
        val filamentAsset: FilamentAsset?,
        val root: Int,
        val entities: IntArray,
        val material: Material? = null,
        val materialInstance: MaterialInstance? = null,
        val texture: Texture? = null,
        val vertexBuffer: VertexBuffer? = null,
        val indexBuffer: IndexBuffer? = null,
    )

    private val modelAssets: MutableMap<String, ModelAsset> = mutableMapOf()
    private val pendingTransforms: MutableMap<String, FloatArray> = mutableMapOf()

    fun attachTextureView(textureView: TextureView) {
        if (this.textureView === textureView) return
        this.textureView = textureView
        mainHandler.post {
            ensureUiHelper()
            uiHelper?.attachTo(textureView)
            startRenderLoop()
        }
    }

    fun updateCamera(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
    ) {
        synchronized(cameraLock) {
            System.arraycopy(viewMatrix, 0, cameraViewMatrix, 0, 16)
            System.arraycopy(projectionMatrix, 0, cameraProjectionMatrix, 0, 16)
            hasCamera = true
        }
    }

    fun updateLightEstimate(lightEstimate: com.google.ar.core.LightEstimate) {
        mainHandler.post {
            val engine = engine ?: return@post
            if (lightEntity == 0) return@post
            val lightManager = engine.lightManager
            val instance = lightManager.getInstance(lightEntity)
            if (instance == 0) return@post
            val fillInstance = if (fillLightEntity != 0) lightManager.getInstance(fillLightEntity) else 0

            val primaryBaseIntensity = 100000.0f * lightIntensityMultiplier
            val fillBaseIntensity = 20000.0f * lightIntensityMultiplier

            if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                val colorCorrection = FloatArray(4)
                lightEstimate.getColorCorrection(colorCorrection, 0)
                val pixelIntensity = lightEstimate.pixelIntensity
                lightManager.setColor(instance, colorCorrection[0], colorCorrection[1], colorCorrection[2])
                lightManager.setIntensity(instance, primaryBaseIntensity * pixelIntensity)
                if (fillInstance != 0) {
                    lightManager.setColor(fillInstance, 1.0f, 1.0f, 1.0f)
                    lightManager.setIntensity(fillInstance, fillBaseIntensity * pixelIntensity)
                }
            } else {
                lightManager.setColor(instance, 1.0f, 1.0f, 1.0f)
                lightManager.setIntensity(instance, primaryBaseIntensity)
                if (fillInstance != 0) {
                    lightManager.setColor(fillInstance, 1.0f, 1.0f, 1.0f)
                    lightManager.setIntensity(fillInstance, fillBaseIntensity)
                }
            }
        }
    }

    fun setLightIntensityMultiplier(multiplier: Float) {
        val clamped = if (multiplier.isFinite()) multiplier else 1.0f
        lightIntensityMultiplier = if (clamped <= 0f) 0.01f else clamped
        mainHandler.post {
            val engine = engine ?: return@post
            if (lightEntity == 0) return@post
            val lightManager = engine.lightManager
            val instance = lightManager.getInstance(lightEntity)
            if (instance != 0) {
                lightManager.setIntensity(instance, 100000.0f * lightIntensityMultiplier)
            }
            if (fillLightEntity != 0) {
                val fillInstance = lightManager.getInstance(fillLightEntity)
                if (fillInstance != 0) {
                    lightManager.setIntensity(fillInstance, 20000.0f * lightIntensityMultiplier)
                }
            }
        }
    }

    fun loadGlb(
        name: String,
        data: ByteArray,
    ) {
        mainHandler.post {
            ensureEngine()
            val assetLoader = assetLoader ?: return@post
            val resourceLoader = resourceLoader ?: return@post
            val scene = scene ?: return@post

            val buffer = ByteBuffer.allocateDirect(data.size)
            buffer.put(data)
            buffer.flip()

            val asset = assetLoader.createAsset(buffer)
            if (asset == null) {
                Log.e(tag, "Failed to create GLB asset for $name")
                return@post
            }
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
            val modelAsset = ModelAsset(asset, asset.root, asset.entities)
            modelAssets[name] = modelAsset
            pendingTransforms[name]?.let { transform ->
                applyTransform(modelAsset, transform)
            }
        }
    }

    fun loadGltf(
        name: String,
        json: ByteArray,
        resources: Map<String, ByteArray>,
    ) {
        mainHandler.post {
            ensureEngine()
            val assetLoader = assetLoader ?: return@post
            val resourceLoader = resourceLoader ?: return@post
            val scene = scene ?: return@post

            val jsonBuffer = ByteBuffer.allocateDirect(json.size)
            jsonBuffer.put(json)
            jsonBuffer.flip()

            val asset = assetLoader.createAsset(jsonBuffer)
            if (asset == null) {
                Log.e(tag, "Failed to create glTF asset for $name")
                return@post
            }

            for ((uri, bytes) in resources) {
                val resBuffer = ByteBuffer.allocateDirect(bytes.size)
                resBuffer.put(bytes)
                resBuffer.flip()
                resourceLoader.addResourceData(uri, resBuffer)
            }

            resourceLoader.loadResources(asset)
            resourceLoader.evictResourceData()
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
            val modelAsset = ModelAsset(asset, asset.root, asset.entities)
            modelAssets[name] = modelAsset
            pendingTransforms[name]?.let { transform ->
                applyTransform(modelAsset, transform)
            }
        }
    }

    fun loadImage(
        name: String,
        pngBytes: ByteArray,
        unlitImageMaterialBytes: ByteArray,
    ) {
        mainHandler.post {
            ensureEngine()
            val engine = engine ?: return@post
            val scene = scene ?: return@post

            // Validate material bytes
            if (unlitImageMaterialBytes.isEmpty()) {
                Log.e(tag, "loadImage failed for $name: unlitImageMaterialBytes is empty")
                return@post
            }

            // --- Decode PNG ---
            val bitmap =
                BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                    ?: run {
                        Log.e(tag, "Failed to decode PNG for $name")
                        return@post
                    }

            // --- Create texture ---
            val texture =
                Texture
                    .Builder()
                    .width(bitmap.width)
                    .height(bitmap.height)
                    .levels(1)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.RGBA8)
                    .build(engine)

            val pixelBuffer = ByteBuffer.allocateDirect(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixelBuffer)
            pixelBuffer.flip()

            texture.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(
                    pixelBuffer,
                    Texture.Format.RGBA,
                    Texture.Type.UBYTE
                )
            )

            bitmap.recycle()

            // --- Create entity ---
            val entity = EntityManager.get().create()

            // --- Quad geometry ---
            val vertices =
                floatArrayOf(
                    -0.5f,
                    -0.5f,
                    0f,
                    0.5f,
                    -0.5f,
                    0f,
                    -0.5f,
                    0.5f,
                    0f,
                    0.5f,
                    0.5f,
                    0f,
                )

            val uvs =
                floatArrayOf(
                    0f,
                    1f,
                    1f,
                    1f,
                    0f,
                    0f,
                    1f,
                    0f,
                )

            val vertexBuffer =
                VertexBuffer
                    .Builder()
                    .vertexCount(4)
                    .bufferCount(2)
                    .attribute(
                        VertexBuffer.VertexAttribute.POSITION,
                        0,
                        VertexBuffer.AttributeType.FLOAT3,
                        0,
                        12,
                    ).attribute(
                        VertexBuffer.VertexAttribute.UV0,
                        1,
                        VertexBuffer.AttributeType.FLOAT2,
                        0,
                        8,
                    ).build(engine)

            val vertexData =
                ByteBuffer
                    .allocateDirect(vertices.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(vertices)
            vertexData.rewind()

            val uvData =
                ByteBuffer
                    .allocateDirect(uvs.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(uvs)
            uvData.rewind()

            vertexBuffer.setBufferAt(engine, 0, vertexData)
            vertexBuffer.setBufferAt(engine, 1, uvData)

            // --- Index buffer ---
            val indices = shortArrayOf(0, 1, 2, 2, 1, 3)

            val indexBuffer =
                IndexBuffer
                    .Builder()
                    .indexCount(6)
                    .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                    .build(engine)

            val indexData =
                ByteBuffer
                    .allocateDirect(indices.size * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer()
                    .put(indices)
            indexData.rewind()

            indexBuffer.setBuffer(engine, indexData)

            // --- Material ---
            // Material must be loaded from a DIRECT ByteBuffer
            val materialBuffer = ByteBuffer.allocateDirect(unlitImageMaterialBytes.size)
            materialBuffer.put(unlitImageMaterialBytes)
            materialBuffer.flip()
            
            val material = Material
                .Builder()
                .payload(materialBuffer, materialBuffer.remaining())
                .build(engine)

            val materialInstance = material.createInstance()
            
            // Set texture parameter
            val sampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE
            )
            
            materialInstance.setParameter("baseColor", texture, sampler)

            // --- Renderable ---
            RenderableManager
                .Builder(1)
                .boundingBox(Box(-0.5f, -0.5f, -0.01f, 0.5f, 0.5f, 0.01f))
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer,
                    indexBuffer,
                    0,
                    6,
                ).material(0, materialInstance)
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, entity)

            // --- Register asset ---
            val modelAsset = ModelAsset(
                filamentAsset = null,
                root = entity,
                entities = intArrayOf(entity),
                material = material,
                materialInstance = materialInstance,
                texture = texture,
                vertexBuffer = vertexBuffer,
                indexBuffer = indexBuffer
            )
            modelAssets[name] = modelAsset

            scene.addEntity(entity)

            pendingTransforms[name]?.let {
                applyTransform(modelAsset, it)
            }
        }
    }

    fun updateTransform(
        name: String,
        modelMatrix: FloatArray,
    ) {
        val matrixCopy = modelMatrix.clone()
        mainHandler.post {
            pendingTransforms[name] = matrixCopy
            val asset = modelAssets[name] ?: return@post
            applyTransform(asset, matrixCopy)
        }
    }

    fun removeModel(name: String) {
        mainHandler.post {
            val scene = scene ?: return@post
            val engine = engine ?: return@post
            val asset = modelAssets.remove(name) ?: return@post
            scene.removeEntities(asset.entities)
            asset.filamentAsset?.let { assetLoader?.destroyAsset(it) }
            
            // Destroy image resources if present (in correct order)
            // 1. Destroy Renderable components first
            val renderableManager = engine.renderableManager
            asset.entities.forEach { entity ->
                if (renderableManager.hasComponent(entity)) {
                    renderableManager.destroy(entity)
                }
            }
            
            // 2. Then destroy material resources
            asset.materialInstance?.let { engine.destroyMaterialInstance(it) }
            asset.material?.let { engine.destroyMaterial(it) }
            asset.texture?.let { engine.destroyTexture(it) }
            asset.vertexBuffer?.let { engine.destroyVertexBuffer(it) }
            asset.indexBuffer?.let { engine.destroyIndexBuffer(it) }
            
            // 3. Finally destroy entities
            EntityManager.get().destroy(asset.entities)
            
            pendingTransforms.remove(name)
        }
    }

    fun destroy() {
        mainHandler.post {
            stopRenderLoop()
            uiHelper?.detach()
            uiHelper = null

            val engine = engine ?: return@post

            val renderableManager = engine.renderableManager
            modelAssets.values.forEach { asset ->
                scene?.removeEntities(asset.entities)
                asset.filamentAsset?.let { assetLoader?.destroyAsset(it) }
                
                // Destroy image resources if present (in correct order)
                // 1. Destroy Renderable components first
                asset.entities.forEach { entity ->
                    if (renderableManager.hasComponent(entity)) {
                        renderableManager.destroy(entity)
                    }
                }
                
                // 2. Then destroy material resources
                asset.materialInstance?.let { engine.destroyMaterialInstance(it) }
                asset.material?.let { engine.destroyMaterial(it) }
                asset.texture?.let { engine.destroyTexture(it) }
                asset.vertexBuffer?.let { engine.destroyVertexBuffer(it) }
                asset.indexBuffer?.let { engine.destroyIndexBuffer(it) }
                
                // 3. Destroy entities
                EntityManager.get().destroy(asset.entities)
            }
            modelAssets.clear()

            resourceLoader?.destroy()
            assetLoader?.destroy()
            materialProvider?.destroy()

            renderer?.let { engine.destroyRenderer(it) }
            view?.let { engine.destroyView(it) }
            scene?.let { engine.destroyScene(it) }
            camera?.let { engine.destroyCameraComponent(camera!!.entity) }
            if (lightEntity != 0) {
                engine.destroyEntity(lightEntity)
                lightEntity = 0
            }
            if (fillLightEntity != 0) {
                engine.destroyEntity(fillLightEntity)
                fillLightEntity = 0
            }
            destroySwapChain()

            engine.destroy()

            this.engine = null
            renderer = null
            view = null
            scene = null
            camera = null
            materialProvider = null
            assetLoader = null
            resourceLoader = null
        }
    }

    private fun createSwapChainIfNeeded(surface: Surface) {
        val engine = engine ?: return
        if (swapChain != null && swapChainSurface === surface) return
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(surface)
        swapChainSurface = surface
    }

    private fun destroySwapChain() {
        val engine = engine ?: return
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        swapChainSurface = null
    }

    private fun ensureEngine() {
        if (engine != null) return
        Filament.init()
        Gltfio.init()
        engine = Engine.create()
        renderer = engine!!.createRenderer()
        renderer!!.clearOptions =
            Renderer.ClearOptions().apply {
                clear = true
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
            }
        scene = engine!!.createScene()
        view = engine!!.createView()
        camera = engine!!.createCamera(EntityManager.get().create())

        materialProvider = UbershaderProvider(engine!!)
        assetLoader = AssetLoader(engine!!, materialProvider!!, EntityManager.get())
        resourceLoader = ResourceLoader(engine!!)

        view!!.scene = scene
        view!!.camera = camera
        view!!.blendMode = View.BlendMode.TRANSLUCENT
        view!!.isPostProcessingEnabled = true

        lightEntity = EntityManager.get().create()
        LightManager
            .Builder(LightManager.Type.DIRECTIONAL)
            .direction(0.0f, -1.0f, -0.5f)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(100000.0f)
            .build(engine!!, lightEntity)
        scene!!.addEntity(lightEntity)

        fillLightEntity = EntityManager.get().create()
        LightManager
            .Builder(LightManager.Type.DIRECTIONAL)
            .direction(0.2f, -0.3f, 0.9f)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(20000.0f)
            .build(engine!!, fillLightEntity)
        scene!!.addEntity(fillLightEntity)
    }

    private fun ensureUiHelper() {
        if (uiHelper != null) return
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper?.setRenderCallback(
            object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    ensureEngine()
                    createSwapChainIfNeeded(surface)
                }

                override fun onDetachedFromSurface() {
                    destroySwapChain()
                }

                override fun onResized(
                    width: Int,
                    height: Int,
                ) {
                    viewportWidth = width
                    viewportHeight = height
                    view?.viewport = Viewport(0, 0, width, height)
                }
            },
        )
    }

    private fun startRenderLoop() {
        if (choreographer != null) return
        choreographer = Choreographer.getInstance()
        frameCallback =
            Choreographer.FrameCallback {
                renderFrame()
                choreographer?.postFrameCallback(frameCallback)
            }
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        frameCallback = null
        choreographer = null
    }

    private fun renderFrame() {
        val renderer = renderer ?: return
        val view = this.view ?: return
        val swapChain = this.swapChain ?: return
        val camera = this.camera ?: return
        if (!hasCamera) return

        if (viewportWidth == 0 || viewportHeight == 0) {
            val tv = textureView
            val w = tv?.width ?: 0
            val h = tv?.height ?: 0
            if (w > 0 && h > 0) {
                viewportWidth = w
                viewportHeight = h
                view.viewport = Viewport(0, 0, w, h)
            }
        }

        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        synchronized(cameraLock) {
            System.arraycopy(cameraViewMatrix, 0, viewMatrix, 0, 16)
            System.arraycopy(cameraProjectionMatrix, 0, projectionMatrix, 0, 16)
        }

        val inverseView = FloatArray(16)
        Matrix.invertM(inverseView, 0, viewMatrix, 0)
        camera.setModelMatrix(inverseView)

        val m00 = projectionMatrix[0]
        val m11 = projectionMatrix[5]
        val aspect = if (m00 != 0f) (m11 / m00).toDouble() else 1.0
        val fovY = 2.0 * atan(1.0 / m11)
        val fovDegrees = Math.toDegrees(fovY)
        camera.setProjection(fovDegrees, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)

        if (renderer.beginFrame(swapChain, 0L)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun applyTransform(
        asset: ModelAsset,
        modelMatrix: FloatArray,
    ) {
        val engine = engine ?: return
        val transformManager = engine.transformManager
        val instance = transformManager.getInstance(asset.root)
        if (instance != 0) {
            transformManager.setTransform(instance, modelMatrix)
        }
    }
}
