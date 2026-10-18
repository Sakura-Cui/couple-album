package com.sakura.romance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** One hardware-accelerated canvas for all splash particles and touch physics. */
internal class SakuraParticleView(context: Context) : View(context), Choreographer.FrameCallback {
    private data class Petal(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var offsetX: Float,
        var offsetY: Float,
        var offsetVx: Float,
        var offsetVy: Float,
        var angle: Float,
        val angularVelocity: Float,
        var flipPhase: Float,
        val flipSpeed: Float,
        val fallSpeed: Float,
        val swaySpeed: Float,
        val swayAmount: Float,
        val phase: Float,
        val radius: Float,
        val alpha: Float,
        val colorIndex: Int,
        val flower: Boolean
    )

    private val density = resources.displayMetrics.density
    private val random = Random(20260915)
    private val choreographer = Choreographer.getInstance()
    private val spriteSize = max(64, dp(32f).toInt())
    private val spriteAtlas = createSpriteAtlas()
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(spriteAtlas, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val petalVertices = FloatArray(PETAL_COUNT * 8)
    private val petalTextureCoordinates = FloatArray(PETAL_COUNT * 8)
    private val petalVertexColors = IntArray(PETAL_COUNT * 4)
    private val petalIndices = ShortArray(PETAL_COUNT * 6).also { indices ->
        repeat(PETAL_COUNT) { index ->
            val vertex = index * 4
            val offset = index * 6
            indices[offset] = vertex.toShort()
            indices[offset + 1] = (vertex + 1).toShort()
            indices[offset + 2] = (vertex + 2).toShort()
            indices[offset + 3] = vertex.toShort()
            indices[offset + 4] = (vertex + 2).toShort()
            indices[offset + 5] = (vertex + 3).toShort()
        }
    }
    private val beamMatrix = Matrix()
    private val heartbeatPath = Path()
    private val beamCoreShader = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(
            Color.TRANSPARENT,
            SakuraPink.copy(alpha = .35f).toArgb(),
            Color.rgb(211, 111, 145),
            Color.TRANSPARENT
        ),
        floatArrayOf(0f, .60f, .82f, 1f), Shader.TileMode.CLAMP
    )
    private val beamCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        shader = beamCoreShader
    }
    private val petals = mutableListOf<Petal>()
    private var lastFrameNanos = 0L
    private var elapsedSeconds = 0f
    private var frameScheduled = false
    private var touchActive = false
    private var touchX = 0f
    private var touchY = 0f
    private var reducedMotion = systemRequestsReducedMotion()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        createPetals(if (reducedMotion) STATIC_PETAL_COUNT else PETAL_COUNT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshMotionPreference()
        startFrames()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            refreshMotionPreference()
            lastFrameNanos = 0L
            startFrames()
        } else {
            stopFrames()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!canAnimate()) return
        if (lastFrameNanos != 0L) {
            val dt = min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 1f / 30f)
            elapsedSeconds += dt
            updatePetals(dt)
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        startFrames()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPetals(canvas)
        if (!reducedMotion) drawBeam(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (reducedMotion) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchActive = true
                touchX = event.x
                touchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                touchActive = false
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun createPetals(count: Int) {
        if (width == 0 || height == 0) return
        petals.clear()
        repeat(count) { index ->
            val size = 4.5f + random.nextFloat() * 6f
            petals += Petal(
                x = random.nextFloat() * width,
                y = random.nextFloat() * height,
                vx = 0f,
                vy = dp(20f + random.nextFloat() * 20f),
                offsetX = 0f,
                offsetY = 0f,
                offsetVx = 0f,
                offsetVy = 0f,
                angle = random.nextFloat() * 360f,
                angularVelocity = (-42f + random.nextFloat() * 84f),
                flipPhase = random.nextFloat() * (2f * PI).toFloat(),
                flipSpeed = .7f + random.nextFloat() * 2.4f,
                fallSpeed = dp(34f + random.nextFloat() * 52f),
                swaySpeed = .45f + random.nextFloat() * 1.35f,
                swayAmount = dp(8f + random.nextFloat() * 28f),
                phase = random.nextFloat() * (2f * PI).toFloat(),
                radius = dp(size),
                alpha = .16f + random.nextFloat() * .34f,
                colorIndex = randomColorIndex(),
                flower = index % 20 < 3
            )
        }
        invalidate()
    }

    private fun updatePetals(dt: Float) {
        val beam = beamState()
        val spring = 7.2f
        val damping = exp(-4.4f * dt)
        petals.forEach { petal ->
            val targetVx = sin(elapsedSeconds * petal.swaySpeed + petal.phase) * petal.swayAmount
            petal.vx += (targetVx - petal.vx) * min(1f, dt * 1.4f)
            petal.vy += (petal.fallSpeed - petal.vy) * min(1f, dt * 1.1f)

            if (touchActive) applyRepulsion(petal, touchX, touchY, dp(112f), dp(1_450f), dt)
            applyBeamForces(petal, beam, dt)

            petal.offsetVx += -petal.offsetX * spring * dt
            petal.offsetVy += -petal.offsetY * spring * dt
            petal.offsetVx *= damping
            petal.offsetVy *= damping
            petal.offsetX += petal.offsetVx * dt
            petal.offsetY += petal.offsetVy * dt
            petal.x += petal.vx * dt
            petal.y += petal.vy * dt
            petal.angle += petal.angularVelocity * dt
            petal.flipPhase += petal.flipSpeed * dt

            val margin = petal.radius * 3f
            if (petal.y - margin > height) {
                petal.y = -margin
                petal.x = random.nextFloat() * width
                petal.offsetX = 0f
                petal.offsetY = 0f
                petal.offsetVx = 0f
                petal.offsetVy = 0f
            }
            if (petal.x < -margin) petal.x += width + margin * 2f
            if (petal.x > width + margin) petal.x -= width + margin * 2f
        }
    }

    private fun applyRepulsion(petal: Petal, sourceX: Float, sourceY: Float, radius: Float, force: Float, dt: Float) {
        val dx = petal.x + petal.offsetX - sourceX
        val dy = petal.y + petal.offsetY - sourceY
        val distance = max(hypot(dx, dy), 1f)
        if (distance >= radius) return
        val strength = (1f - distance / radius) * force * dt
        petal.offsetVx += dx / distance * strength
        petal.offsetVy += dy / distance * strength
    }

    private fun applyBeamForces(petal: Petal, beam: BeamState, dt: Float) {
        if (beam.alpha <= .01f) return
        val px = petal.x + petal.offsetX
        val py = petal.y + petal.offsetY
        val lineStart = width * .08f
        val lineWidth = width * .84f
        val headY = beam.y + heartbeatOffset((beam.headX - lineStart) / lineWidth)
        applyRepulsion(petal, beam.headX, headY, dp(92f), dp(880f) * beam.alpha, dt)
        val behind = beam.headX - px
        val wakeY = beam.y + heartbeatOffset((px - lineStart) / lineWidth)
        if (behind in 0f..beam.trailLength && abs(py - wakeY) < dp(72f)) {
            val wake = (1f - behind / beam.trailLength) * beam.alpha
            petal.offsetVx += dp(115f) * wake * dt
            petal.offsetVy += sin(elapsedSeconds * 7f + petal.phase) * dp(210f) * wake * dt
        }
    }

    private fun drawPetals(canvas: Canvas) {
        petals.forEachIndexed { index, petal ->
            val centerX = petal.x + petal.offsetX
            val centerY = petal.y + petal.offsetY
            val halfHeight = petal.radius * 1.35f
            val flip = if (reducedMotion) 1f else .18f + .82f * abs(cos(petal.flipPhase))
            val halfWidth = halfHeight * flip
            val radians = Math.toRadians(petal.angle.toDouble())
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            val vertexOffset = index * 8
            setVertex(vertexOffset, centerX, centerY, -halfWidth, -halfHeight, cosine, sine)
            setVertex(vertexOffset + 2, centerX, centerY, halfWidth, -halfHeight, cosine, sine)
            setVertex(vertexOffset + 4, centerX, centerY, halfWidth, halfHeight, cosine, sine)
            setVertex(vertexOffset + 6, centerX, centerY, -halfWidth, halfHeight, cosine, sine)

            val spriteIndex = petal.colorIndex + if (petal.flower) 3 else 0
            val left = spriteIndex * spriteSize.toFloat()
            val right = left + spriteSize
            petalTextureCoordinates[vertexOffset] = left
            petalTextureCoordinates[vertexOffset + 1] = 0f
            petalTextureCoordinates[vertexOffset + 2] = right
            petalTextureCoordinates[vertexOffset + 3] = 0f
            petalTextureCoordinates[vertexOffset + 4] = right
            petalTextureCoordinates[vertexOffset + 5] = spriteSize.toFloat()
            petalTextureCoordinates[vertexOffset + 6] = left
            petalTextureCoordinates[vertexOffset + 7] = spriteSize.toFloat()

            val color = Color.argb((petal.alpha * 255).toInt(), 255, 255, 255)
            val colorOffset = index * 4
            repeat(4) { petalVertexColors[colorOffset + it] = color }
        }
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            petals.size * 8,
            petalVertices,
            0,
            petalTextureCoordinates,
            0,
            petalVertexColors,
            0,
            petalIndices,
            0,
            petals.size * 6,
            petalPaint
        )
    }

    private fun setVertex(
        offset: Int,
        centerX: Float,
        centerY: Float,
        localX: Float,
        localY: Float,
        cosine: Float,
        sine: Float
    ) {
        petalVertices[offset] = centerX + localX * cosine - localY * sine
        petalVertices[offset + 1] = centerY + localX * sine + localY * cosine
    }

    private data class BeamState(val headX: Float, val y: Float, val trailLength: Float, val alpha: Float)

    private fun beamState(): BeamState {
        val cycle = ((elapsedSeconds + .35f) % 4.6f) / 4.6f
        val headX = (-.08f + cycle * 1.16f) * width
        val fadeIn = smoothStep(0f, .12f, cycle)
        val fadeOut = 1f - smoothStep(.88f, 1f, cycle)
        val flicker = .72f + .28f * abs(sin(elapsedSeconds * 11f))
        return BeamState(headX, height * .60f, width * .30f, fadeIn * fadeOut * flicker)
    }

    private fun drawBeam(canvas: Canvas) {
        val beam = beamState()
        if (beam.alpha <= .001f) return
        val frontLength = dp(30f)
        val highlightStart = beam.headX - beam.trailLength
        beamMatrix.setScale(beam.trailLength + frontLength, 1f)
        beamMatrix.postTranslate(highlightStart, 0f)
        beamCoreShader.setLocalMatrix(beamMatrix)
        val lineStart = width * .08f
        val lineWidth = width * .84f
        heartbeatPath.rewind()
        HEARTBEAT_POSITIONS.forEachIndexed { index, position ->
            val x = lineStart + position * lineWidth
            val y = beam.y + HEARTBEAT_AMPLITUDE_DP * density * HEARTBEAT_HEIGHTS[index]
            if (index == 0) heartbeatPath.moveTo(x, y) else heartbeatPath.lineTo(x, y)
        }
        beamCorePaint.strokeWidth = dp(1.2f)
        beamCorePaint.alpha = (beam.alpha * 255).toInt()
        canvas.drawPath(heartbeatPath, beamCorePaint)

    }

    private fun heartbeatOffset(position: Float): Float {
        val x = position.coerceIn(0f, 1f)
        for (index in 1 until HEARTBEAT_POSITIONS.size) {
            if (x <= HEARTBEAT_POSITIONS[index]) {
                val fraction = (x - HEARTBEAT_POSITIONS[index - 1]) /
                    (HEARTBEAT_POSITIONS[index] - HEARTBEAT_POSITIONS[index - 1])
                return dp(HEARTBEAT_AMPLITUDE_DP) * (HEARTBEAT_HEIGHTS[index - 1] +
                    (HEARTBEAT_HEIGHTS[index] - HEARTBEAT_HEIGHTS[index - 1]) * fraction)
            }
        }
        return 0f
    }

    private fun createSpriteAtlas(): Bitmap {
        val colors = intArrayOf(SakuraPink.toArgb(), MistPink.toArgb(), Color.rgb(235, 156, 180))
        return Bitmap.createBitmap(spriteSize * 6, spriteSize, Bitmap.Config.ARGB_8888).also { atlas ->
            val atlasCanvas = Canvas(atlas)
            colors.forEachIndexed { index, color ->
                val petal = createPetalSprite(color)
                val flower = createFlowerSprite(color)
                atlasCanvas.drawBitmap(petal, index * spriteSize.toFloat(), 0f, null)
                atlasCanvas.drawBitmap(flower, (index + 3) * spriteSize.toFloat(), 0f, null)
                petal.recycle()
                flower.recycle()
            }
        }
    }

    private fun createPetalSprite(color: Int): Bitmap =
        Bitmap.createBitmap(spriteSize, spriteSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val radius = spriteSize * .32f
            canvas.drawPath(petalPath(spriteSize / 2f, spriteSize / 2f, radius), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }

    private fun createFlowerSprite(color: Int): Bitmap =
        Bitmap.createBitmap(spriteSize, spriteSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            val center = spriteSize / 2f
            val radius = spriteSize * .155f
            repeat(5) { index ->
                val angle = Math.toRadians(index * 72.0 - 90.0)
                canvas.drawCircle(
                    center + cos(angle).toFloat() * radius * 1.15f,
                    center + sin(angle).toFloat() * radius * 1.15f,
                    radius,
                    paint
                )
            }
            paint.color = PureWhite.toArgb()
            canvas.drawCircle(center, center, radius * .26f, paint)
        }

    private fun petalPath(centerX: Float, centerY: Float, radius: Float) = Path().apply {
        moveTo(centerX, centerY + radius * 1.25f)
        cubicTo(centerX - radius * .92f, centerY + radius * .60f,
            centerX - radius, centerY - radius * .38f,
            centerX - radius * .52f, centerY - radius)
        cubicTo(centerX - radius * .30f, centerY - radius * 1.20f,
            centerX - radius * .08f, centerY - radius * .82f,
            centerX, centerY - radius * .68f)
        cubicTo(centerX + radius * .08f, centerY - radius * .82f,
            centerX + radius * .30f, centerY - radius * 1.20f,
            centerX + radius * .52f, centerY - radius)
        cubicTo(centerX + radius, centerY - radius * .38f,
            centerX + radius * .92f, centerY + radius * .60f,
            centerX, centerY + radius * 1.25f)
        close()
    }

    private fun randomColorIndex(): Int {
        val value = random.nextFloat()
        return when {
            value < .56f -> 0
            value < .92f -> 1
            else -> 2
        }
    }

    private fun refreshMotionPreference() {
        val wasReduced = reducedMotion
        reducedMotion = systemRequestsReducedMotion()
        if (wasReduced != reducedMotion) createPetals(if (reducedMotion) STATIC_PETAL_COUNT else PETAL_COUNT)
        if (reducedMotion) stopFrames()
    }

    private fun canAnimate() = isAttachedToWindow && windowVisibility == VISIBLE && !reducedMotion

    private fun systemRequestsReducedMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun startFrames() {
        if (!frameScheduled && canAnimate()) {
            frameScheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    private fun stopFrames() {
        if (frameScheduled) choreographer.removeFrameCallback(this)
        frameScheduled = false
        lastFrameNanos = 0L
    }

    private fun dp(value: Float) = value * density

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private companion object {
        const val HEARTBEAT_AMPLITUDE_DP = 22f
        val HEARTBEAT_POSITIONS = floatArrayOf(0f, .38f, .44f, .48f, .52f, .56f, .60f, .66f, 1f)
        val HEARTBEAT_HEIGHTS = floatArrayOf(0f, 0f, -.10f, 0f, .16f, -.85f, .42f, 0f, 0f)
        const val PETAL_COUNT = 96
        const val STATIC_PETAL_COUNT = 18
    }
}
