// gardendless-android

// Copyright (C) 2026  Caten Hu

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
package com.fct.gardendless

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 游戏画面容器（默认黑底，兼作黑边）。
 *
 * 负责把内容 View（WebView）约束在 16:10 ~ 17:9 的比例区间内，区间外留黑边；
 * 其他子 View 可用作容器上方的原生控件。[fullscreen] 为 true 时内容 View 铺满容器。
 *
 * 左右黑边的装饰由 [sideMode] 决定：
 * - SPRITE：[sideArt] 贴图纵向平铺（草坪）；
 * - AMBIENT：取游戏画面贴边的颜色做环境光。
 * 上下黑边（仅窄屏平板出现）始终保持黑色。
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 左右黑边的装饰方式 */
    enum class SideMode {
        OFF,
        SPRITE,
        AMBIENT;

        /** 是否需要逐帧重绘 */
        val animated: Boolean get() = this == AMBIENT
    }

    /** true：内容 View 铺满容器；false：按 16:10 ~ 17:9 约束 */
    var fullscreen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            ensureAnimation()
            requestLayout()
        }

    /** SPRITE 模式使用的贴图；为 null 时保持黑底 */
    var sideArt: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            dropSideArtCache()
            invalidate()
        }

    /** 左右黑边的装饰方式，默认环境光 */
    var sideMode: SideMode = SideMode.AMBIENT
        set(value) {
            if (field == value) return
            field = value
            ensureAnimation()
            invalidate()
        }

    /**
     * AMBIENT 采样时从画面边缘再向内缩进的距离（dp），用来跳过画面的边框/描边。
     *
     * 修改后立即重新采样，无需重启游戏。
     */
    var ambientEdgeInsetDp: Float = AMBIENT_EDGE_INSET_DP
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            lastAmbientSampleAt = 0L
            if (sideMode == SideMode.AMBIENT) {
                post { requestAmbientSample() }
                invalidate()
            }
        }

    /** AMBIENT 音频波浪效果：开启后两侧环境光随游戏声音脉动扩张 */
    var ambientWavesEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                targetAudioLevel = 0f
                targetAudioBass = 0f
                targetAudioMid = 0f
                targetAudioHigh = 0f
                smoothAudioLevel = 0f
                smoothAudioBass = 0f
                smoothAudioMid = 0f
                smoothAudioHigh = 0f
            }
            invalidate()
        }

    @Volatile
    private var targetAudioLevel = 0f

    @Volatile
    private var targetAudioBass = 0f

    @Volatile
    private var targetAudioMid = 0f

    @Volatile
    private var targetAudioHigh = 0f

    @Volatile
    private var lastAudioUpdateAt = 0L

    private var smoothAudioLevel = 0f
    private var smoothAudioBass = 0f
    private var smoothAudioMid = 0f
    private var smoothAudioHigh = 0f
    private var wavePhase = 0f

    fun updateAmbientAudioSpectrum(level: Float, bass: Float, mid: Float, high: Float) {
        targetAudioLevel = level.coerceIn(0f, 1f)
        targetAudioBass = bass.coerceIn(0f, 1f)
        targetAudioMid = mid.coerceIn(0f, 1f)
        targetAudioHigh = high.coerceIn(0f, 1f)
        lastAudioUpdateAt = SystemClock.uptimeMillis()
    }

    private var animating = false
    private var lastFrameAt = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wavePath = Path()
    private val rectF = RectF()

    // ── AMBIENT：从游戏画面贴边处取色 ──

    /** 每侧沿高度取多少个色块 */
    private val ambientBands = AMBIENT_BANDS

    /** 当前显示颜色（平滑过渡）与目标颜色（最近一次采样） */
    private val leftColors = IntArray(ambientBands) { AMBIENT_FALLBACK_COLOR }
    private val rightColors = IntArray(ambientBands) { AMBIENT_FALLBACK_COLOR }
    private val leftTarget = IntArray(ambientBands) { AMBIENT_FALLBACK_COLOR }
    private val rightTarget = IntArray(ambientBands) { AMBIENT_FALLBACK_COLOR }

    private var ambientLeftGradient: LinearGradient? = null
    private var ambientRightGradient: LinearGradient? = null
    private var ambientFadeLeft: LinearGradient? = null
    private var ambientFadeRight: LinearGradient? = null
    private var ambientGradientHeight = 0
    private var ambientGradientBarWidth = 0
    private var ambientDirty = true

    private var ambientSamplePending = false
    private var lastAmbientSampleAt = 0L
    private var ambientFailureLogged = false
    private var ambientLeftBitmap: Bitmap? = null
    private var ambientRightBitmap: Bitmap? = null
    private val pixelCopyHandler = Handler(Looper.getMainLooper())

    /** SPRITE 模式下按黑边宽度缩放后的分块（纵向重复铺满） */
    private var sideArtTile: Bitmap? = null
    private var sideArtTileWidth = 0

    /** 分块是否由本 View 创建（等于源图时不能回收） */
    private var sideArtTileOwned = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // 没有子 View 时无需自定义测量
        val child = getChildAt(0) ?: return

        val screenWidth = measuredWidth
        val screenHeight = measuredHeight
        var targetWidth = screenWidth
        var targetHeight = screenHeight

        if (!fullscreen) {
            when {
                screenWidth * MAX_ASPECT_H > screenHeight * MAX_ASPECT_W -> {
                    // 宽于 17:9（如 20:9、21:9 手机）：以高度为基准，宽度限制在 17:9，左右留黑边
                    targetWidth = screenHeight * MAX_ASPECT_W / MAX_ASPECT_H
                }
                screenWidth * MIN_ASPECT_H < screenHeight * MIN_ASPECT_W -> {
                    // 窄于 16:10（如 4:3、7:5 平板）：以宽度为基准，高度限制在 16:10，上下留黑边
                    targetHeight = screenWidth * MIN_ASPECT_H / MIN_ASPECT_W
                }
                // 比例处于 16:10 ~ 17:9 之间：铺满容器
            }
        }

        // 以精确尺寸测量子 View
        child.measure(
            MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawSideBars(canvas)
        super.dispatchDraw(canvas)
    }

    /** 左右黑边宽度；无黑边时返回 0 */
    private fun sideBarWidths(): Pair<Int, Int>? {
        if (fullscreen) return null
        val child = getChildAt(0) ?: return null
        if (child.visibility != VISIBLE) return null
        val left = child.left
        val right = width - child.right
        if (left <= 0 && right <= 0) return null
        return left to right
    }

    private fun drawSideBars(canvas: Canvas) {
        val mode = sideMode
        if (mode == SideMode.OFF) return
        val (leftBarWidth, rightBarWidth) = sideBarWidths() ?: return

        when (mode) {
            SideMode.SPRITE -> drawSpriteBars(canvas, leftBarWidth, rightBarWidth)
            SideMode.AMBIENT -> drawAmbientBars(canvas, leftBarWidth, rightBarWidth)
            SideMode.OFF -> Unit
        }
    }

    // ── SPRITE ──

    /**
     * 贴图先按黑边宽度等比缩放，再以 REPEAT 沿纵向重复；横向恰好一块，因此不会出现接缝错位。
     */
    private fun drawSpriteBars(canvas: Canvas, leftBarWidth: Int, rightBarWidth: Int) {
        val art = sideArt
        if (art == null || art.isRecycled) return
        // 两侧黑边宽度可能相差 1px（取整所致），统一按较宽的一侧铺贴，
        // 多出的部分由随后绘制的 WebView 覆盖，避免分块宽度来回变化导致反复重建位图。
        val barWidth = max(leftBarWidth, rightBarWidth)
        val shader = sideArtShader(art, barWidth) ?: return
        paint.shader = shader
        paint.alpha = 255
        if (leftBarWidth > 0) {
            rectF.set(0f, 0f, barWidth.toFloat(), height.toFloat())
            canvas.drawRect(rectF, paint)
        }
        if (rightBarWidth > 0) {
            rectF.set((width - barWidth).toFloat(), 0f, width.toFloat(), height.toFloat())
            canvas.drawRect(rectF, paint)
        }
        paint.shader = null
    }

    /** 取得按 [barWidth] 缩放后的可平铺分块；宽度未变化时复用缓存 */
    private fun sideArtShader(art: Bitmap, barWidth: Int): BitmapShader? {
        val cached = sideArtTile
        if (cached != null && sideArtTileWidth == barWidth && !cached.isRecycled) {
            return BitmapShader(cached, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        val tileHeight = max(1, (art.height.toFloat() * barWidth / art.width).roundToInt())
        val scaled = Bitmap.createScaledBitmap(art, barWidth, tileHeight, true)
        dropSideArtCache()
        sideArtTile = scaled
        sideArtTileWidth = barWidth
        // createScaledBitmap 在尺寸相同时直接返回源图，此时不能回收
        sideArtTileOwned = scaled !== art
        return BitmapShader(scaled, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun dropSideArtCache() {
        val tile = sideArtTile
        val owned = sideArtTileOwned
        sideArtTile = null
        sideArtTileWidth = 0
        sideArtTileOwned = false
        if (owned && tile != null && !tile.isRecycled) tile.recycle()
    }

    // ── AMBIENT：取游戏画面贴边的颜色做环境光 ──

    /**
     * 把两侧黑边涂成游戏画面贴边像素的颜色。
     *
     * 颜色由 [requestAmbientSample] 用 PixelCopy 从游戏画面边缘采样，每侧沿高度分成若干色块，
     * 用竖向渐变连成一片，再从游戏边缘向外淡到黑，看起来就像屏幕把光投射到黑边上。
     */
    private fun drawAmbientBars(canvas: Canvas, leftBarWidth: Int, rightBarWidth: Int) {
        val barWidth = max(leftBarWidth, rightBarWidth)
        if (ambientDirty || ambientGradientHeight != height || ambientGradientBarWidth != barWidth) {
            rebuildAmbientGradients(barWidth)
        }
        if (leftBarWidth > 0) {
            rectF.set(0f, 0f, barWidth.toFloat(), height.toFloat())
            paint.shader = ambientLeftGradient
            paint.alpha = if (ambientWavesEnabled) 135 else 255
            canvas.drawRect(rectF, paint)
            edgePaint.shader = ambientFadeLeft
            edgePaint.alpha = 255
            canvas.drawRect(rectF, edgePaint)
            if (ambientWavesEnabled) {
                drawAmbientAudioWaves(
                    canvas = canvas,
                    isLeft = true,
                    barWidth = leftBarWidth.toFloat(),
                    shader = ambientLeftGradient
                )
            }
        }
        if (rightBarWidth > 0) {
            rectF.set((width - barWidth).toFloat(), 0f, width.toFloat(), height.toFloat())
            paint.shader = ambientRightGradient
            paint.alpha = if (ambientWavesEnabled) 135 else 255
            canvas.drawRect(rectF, paint)
            edgePaint.shader = ambientFadeRight
            edgePaint.alpha = 255
            canvas.drawRect(rectF, edgePaint)
            if (ambientWavesEnabled) {
                drawAmbientAudioWaves(
                    canvas = canvas,
                    isLeft = false,
                    barWidth = rightBarWidth.toFloat(),
                    shader = ambientRightGradient
                )
            }
        }
        paint.shader = null
        edgePaint.shader = null
    }

    /**
     * 绘制真正随游戏音频能量脉动的环境光波浪：
     * 静音时完全收起，无任何假动画；有音乐或音效时按低/中/高频实时向外扩张。
     */
    private fun drawAmbientAudioWaves(
        canvas: Canvas,
        isLeft: Boolean,
        barWidth: Float,
        shader: LinearGradient?
    ) {
        val peakEnergy = max(max(smoothAudioLevel, smoothAudioBass), max(smoothAudioMid, smoothAudioHigh))
        if (shader == null || barWidth <= 2f || height <= 0 || peakEnergy < 0.008f) return

        val h = height.toFloat()
        val anchorX = if (isLeft) barWidth else width - barWidth
        val dir = if (isLeft) -1f else 1f
        val level = smoothAudioLevel
        val bass = smoothAudioBass
        val mid = smoothAudioMid
        val high = smoothAudioHigh

        wavePaint.shader = shader
        waveRimPaint.shader = shader
        waveRimPaint.strokeWidth = resources.displayMetrics.density * (1.2f + bass * 2.8f)

        // Layer 1: Wide outer bloom wave driven by overall loudness + bass
        drawSpectrumWaveLayer(
            canvas = canvas,
            anchorX = anchorX,
            dir = dir,
            barWidth = barWidth,
            heightPx = h,
            reachScale = (level * 0.60f + bass * 0.42f).coerceIn(0f, 1f),
            bass = bass,
            mid = mid,
            high = high,
            phaseOffset = if (isLeft) 0f else 0.9f,
            rippleScale = 0.20f,
            alpha = (peakEnergy * 155f).toInt().coerceIn(0, 165),
            drawRim = false
        )

        // Layer 2: Mid-body wave driven by mid + bass spectrum
        drawSpectrumWaveLayer(
            canvas = canvas,
            anchorX = anchorX,
            dir = dir,
            barWidth = barWidth,
            heightPx = h,
            reachScale = (mid * 0.50f + bass * 0.45f).coerceIn(0f, 0.90f),
            bass = bass,
            mid = mid,
            high = high,
            phaseOffset = if (isLeft) 2.1f else 3.2f,
            rippleScale = 0.24f,
            alpha = (peakEnergy * 210f).toInt().coerceIn(0, 215),
            drawRim = false
        )

        // Layer 3: Sharp inner beat-punch wave + glowing rim
        drawSpectrumWaveLayer(
            canvas = canvas,
            anchorX = anchorX,
            dir = dir,
            barWidth = barWidth,
            heightPx = h,
            reachScale = (bass * 0.68f + high * 0.22f).coerceIn(0f, 0.78f),
            bass = bass,
            mid = mid,
            high = high,
            phaseOffset = if (isLeft) 4.0f else 5.1f,
            rippleScale = 0.22f,
            alpha = (peakEnergy * 255f).toInt().coerceIn(0, 250),
            drawRim = true
        )

        wavePaint.shader = null
        waveRimPaint.shader = null
    }

    private fun drawSpectrumWaveLayer(
        canvas: Canvas,
        anchorX: Float,
        dir: Float,
        barWidth: Float,
        heightPx: Float,
        reachScale: Float,
        bass: Float,
        mid: Float,
        high: Float,
        phaseOffset: Float,
        rippleScale: Float,
        alpha: Int,
        drawRim: Boolean
    ) {
        if (reachScale < 0.005f || alpha <= 2) return
        val steps = 32
        wavePath.reset()
        wavePath.moveTo(anchorX, 0f)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val y = t * heightPx
            // Map vertical zones to live frequency bands: high near top, mid in middle, bass near center/bottom
            val highWeight = (1f - t).coerceIn(0f, 1f)
            val midWeight = sin(t * PI).toFloat()
            val bassWeight = (0.35f + 0.65f * t).coerceIn(0f, 1f)
            val bandEnergy = (bass * bassWeight * 0.55f + mid * midWeight * 0.35f + high * highWeight * 0.25f)
                .coerceIn(0f, 1f)

            val envelope = 0.25f + 0.75f * sin(t * PI).toFloat()
            val w1 = sin((t * 3.0 * PI * 2.0) + wavePhase + phaseOffset).toFloat()
            val w2 = sin((t * 5.5 * PI * 2.0) - wavePhase * 1.4 + phaseOffset).toFloat()
            val ripple = (w1 * 0.65f + w2 * 0.35f) * rippleScale * bandEnergy
            val reach = ((reachScale * 0.65f + bandEnergy * 0.45f) * envelope + ripple)
                .coerceIn(0f, 0.99f) * barWidth
            wavePath.lineTo(anchorX + dir * reach, y)
        }
        wavePath.lineTo(anchorX, heightPx)
        wavePath.close()

        wavePaint.alpha = alpha
        canvas.drawPath(wavePath, wavePaint)

        if (drawRim) {
            waveRimPaint.alpha = ((bass * 0.7f + high * 0.3f) * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(wavePath, waveRimPaint)
        }
    }

    private fun rebuildAmbientGradients(barWidth: Int) {
        val h = height.toFloat().coerceAtLeast(1f)
        ambientLeftGradient = LinearGradient(
            0f, 0f, 0f, h, leftColors, null, Shader.TileMode.CLAMP
        )
        ambientRightGradient = LinearGradient(
            0f, 0f, 0f, h, rightColors, null, Shader.TileMode.CLAMP
        )
        ambientFadeLeft = LinearGradient(
            barWidth.toFloat(), 0f, barWidth * AMBIENT_FADE_START, 0f,
            intArrayOf(0x00000000, AMBIENT_FADE_COLOR),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        ambientFadeRight = LinearGradient(
            (width - barWidth).toFloat(), 0f, width - barWidth * AMBIENT_FADE_START, 0f,
            intArrayOf(0x00000000, AMBIENT_FADE_COLOR),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        ambientGradientHeight = height
        ambientGradientBarWidth = barWidth
        ambientDirty = false
    }

    /**
     * 用 PixelCopy 采样游戏画面左右贴边处的一条窄带。
     *
     * 只读取窄带、按行合并成 [ambientBands] 个色块，开销很低（约每 [AMBIENT_SAMPLE_INTERVAL_MS] 一次）；
     * 复制失败时退回上一次的颜色，不做任何破坏性处理。
     */
    private fun requestAmbientSample() {
        if (ambientSamplePending || !isAttachedToWindow) return
        val window = hostWindow() ?: return
        val child = getChildAt(0) ?: return
        if (child.width <= 0 || height <= 0) return

        val stripWidth = (resources.displayMetrics.density * AMBIENT_STRIP_DP)
            .roundToInt()
            .coerceAtLeast(2)
        val w = min(stripWidth, child.width / 3).coerceAtLeast(1)
        val h = height
        // PixelCopy 以窗口坐标工作，容器不一定从 (0,0) 开始
        val location = IntArray(2)
        getLocationInWindow(location)
        // 从画面内容边缘向内缩一点取样，跳过边框/描边像素
        val inset = (resources.displayMetrics.density * ambientEdgeInsetDp)
            .roundToInt()
            .coerceAtMost((child.width - w) / 3)
            .coerceAtLeast(0)
        val leftRect = Rect(
            location[0] + child.left + inset, location[1],
            location[0] + child.left + inset + w, location[1] + h
        )
        val rightRect = Rect(
            location[0] + child.right - inset - w, location[1],
            location[0] + child.right - inset, location[1] + h
        )
        if (leftRect.left < 0 || leftRect.isEmpty || rightRect.isEmpty) return

        val leftBitmap = sampleBitmap(ambientLeftBitmap, w, h) ?: return
        ambientLeftBitmap = leftBitmap
        val rightBitmap = sampleBitmap(ambientRightBitmap, w, h) ?: return
        ambientRightBitmap = rightBitmap

        ambientSamplePending = true
        var remaining = 2
        fun finish(bitmap: Bitmap, target: IntArray, result: Int) {
            if (result == PixelCopy.SUCCESS) {
                reduceEdgeColors(bitmap, target)
                ambientFailureLogged = false
            }
            remaining--
            if (remaining == 0) ambientSamplePending = false
        }
        try {
            PixelCopy.request(
                window, leftRect, leftBitmap,
                { result -> finish(leftBitmap, leftTarget, result) }, pixelCopyHandler
            )
            PixelCopy.request(
                window, rightRect, rightBitmap,
                { result -> finish(rightBitmap, rightTarget, result) }, pixelCopyHandler
            )
        } catch (e: IllegalArgumentException) {
            // 窗口暂时没有 Surface（启动瞬间、锁屏/被其它界面遮挡）：
            // 放弃这次采样并退避，等窗口恢复后再继续，避免逐帧刷日志。
            ambientSamplePending = false
            lastAmbientSampleAt = SystemClock.uptimeMillis() + AMBIENT_SAMPLE_BACKOFF_MS
            if (!ambientFailureLogged) {
                ambientFailureLogged = true
                Log.d(TAG, "Ambient sampling paused until the window is drawn again")
            }
        }
    }

    /** 从 View 的 Context 逐层找到宿主 Activity 的窗口 */
    private fun hostWindow(): Window? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx.window
            ctx = ctx.baseContext
        }
        return null
    }

    private fun sampleBitmap(current: Bitmap?, w: Int, h: Int): Bitmap? = try {
        if (current != null && !current.isRecycled && current.width == w && current.height == h) {
            current
        } else {
            current?.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Unable to allocate the ambient sample bitmap", e)
        null
    }

    /** 把窄带按高度合并成若干色块，写入 [target] */
    private fun reduceEdgeColors(bitmap: Bitmap, target: IntArray) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val bandHeight = (h / target.size).coerceAtLeast(1)
        val buffer = IntArray(w * bandHeight)
        for (band in target.indices) {
            val startY = band * bandHeight
            if (startY >= h) break
            val rows = min(bandHeight, h - startY)
            try {
                bitmap.getPixels(buffer, 0, w, 0, startY, w, rows)
            } catch (e: IllegalArgumentException) {
                break
            }
            val count = rows * w
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in 0 until count) {
                val color = buffer[i]
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
            }
            target[band] = glowColor(
                (r / count).toInt(),
                (g / count).toInt(),
                (b / count).toInt()
            )
        }
    }

    /** 提亮采样到的颜色，让黑边上的光更明显；很暗的区域保持接近黑色 */
    private fun glowColor(r0: Int, g0: Int, b0: Int): Int {
        val peak = max(r0, max(g0, b0))
        val gain = if (peak < 20) 1f else 1.35f
        val r = (r0 * gain).toInt().coerceIn(0, 255)
        val g = (g0 * gain).toInt().coerceIn(0, 255)
        val b = (b0 * gain).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 逐通道插值，返回是否有颜色发生变化 */
    private fun lerpColors(from: IntArray, to: IntArray, k: Float): Boolean {
        var changed = false
        for (i in from.indices) {
            val a = from[i]
            val b = to[i]
            if (a == b) continue
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val nr = (ar + (br - ar) * k).roundToInt().coerceIn(0, 255)
            val ng = (ag + (bg - ag) * k).roundToInt().coerceIn(0, 255)
            val nb = (ab + (bb - ab) * k).roundToInt().coerceIn(0, 255)
            from[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            changed = true
        }
        return changed
    }

    // ── 动画 ──

    private val frameTick = object : Runnable {
        override fun run() {
            if (!animating) return
            val now = SystemClock.uptimeMillis()
            val deltaMs = if (lastFrameAt == 0L) 16L else (now - lastFrameAt).coerceIn(1L, 80L)
            lastFrameAt = now

            if (sideMode == SideMode.AMBIENT) {
                if (now - lastAmbientSampleAt >= AMBIENT_SAMPLE_INTERVAL_MS) {
                    lastAmbientSampleAt = now
                    requestAmbientSample()
                }
                val colorSmoothing = 1f - exp(-deltaMs / AMBIENT_FADE_MS)
                val leftChanged = lerpColors(leftColors, leftTarget, colorSmoothing)
                val rightChanged = lerpColors(rightColors, rightTarget, colorSmoothing)
                if (leftChanged || rightChanged) ambientDirty = true

                if (ambientWavesEnabled) {
                    val stale = now - lastAudioUpdateAt > 260L
                    val goalLevel = if (stale) 0f else targetAudioLevel
                    val goalBass = if (stale) 0f else targetAudioBass
                    val goalMid = if (stale) 0f else targetAudioMid
                    val goalHigh = if (stale) 0f else targetAudioHigh

                    fun smooth(curr: Float, goal: Float, attackMs: Float, releaseMs: Float): Float {
                        val tau = if (goal > curr) attackMs else releaseMs
                        val next = curr + (goal - curr) * (1f - exp(-deltaMs / tau))
                        return if (next < 0.002f && goal == 0f) 0f else next
                    }

                    smoothAudioLevel = smooth(smoothAudioLevel, goalLevel, 22f, 140f)
                    smoothAudioBass = smooth(smoothAudioBass, goalBass, 18f, 130f)
                    smoothAudioMid = smooth(smoothAudioMid, goalMid, 24f, 145f)
                    smoothAudioHigh = smooth(smoothAudioHigh, goalHigh, 20f, 120f)

                    if (smoothAudioLevel > 0.004f || smoothAudioBass > 0.004f) {
                        wavePhase = (wavePhase + (deltaMs / 1000f) * (smoothAudioLevel * 5.2f + smoothAudioBass * 2.5f)) % (PI.toFloat() * 200f)
                    }
                }
            }

            invalidate()
            postOnAnimation(this)
        }
    }

    /** 只在需要动效时才逐帧重绘，其余情况完全静止 */
    private fun ensureAnimation() {
        val shouldAnimate = isAttachedToWindow && !fullscreen && sideMode.animated
        if (shouldAnimate) {
            if (!animating) {
                animating = true
                lastFrameAt = 0L
                postOnAnimation(frameTick)
            }
        } else if (animating) {
            animating = false
            removeCallbacks(frameTick)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimation()
    }

    override fun onDetachedFromWindow() {
        animating = false
        removeCallbacks(frameTick)
        dropSideArtCache()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 允许的最宽比例 17:9 */
        const val MAX_ASPECT_W = 171
        const val MAX_ASPECT_H = 90

        /** 允许的最窄比例 16:10 */
        const val MIN_ASPECT_W = 160
        const val MIN_ASPECT_H = 100

        /** 环境光：每侧色块数量、贴边采样宽度（dp）与采样间隔 */
        val AMBIENT_BANDS = 16
        const val AMBIENT_STRIP_DP = 5f
        const val AMBIENT_SAMPLE_INTERVAL_MS = 140L

        /** 窗口暂时没有 Surface 时，暂停采样的时长 */
        const val AMBIENT_SAMPLE_BACKOFF_MS = 1500L

        /**
         * 采样带距游戏画面边缘的内缩量（dp）。
         * 0 = 紧贴边缘；2dp 可避开 WebView 边缘的细亮描边，又不会取到画面深处。
         */
        const val AMBIENT_EDGE_INSET_DP = 2f

        /** 色块向新颜色的过渡时间常数，以及从游戏边缘向外淡到黑的起点（占黑边宽度比例） */
        const val AMBIENT_FADE_MS = 220f
        const val AMBIENT_FADE_START = 0.15f

        /** 还没采到颜色时的兜底色与外侧渐隐色 */
        val AMBIENT_FALLBACK_COLOR = 0xFF0C120E.toInt()
        val AMBIENT_FADE_COLOR = 0xC6000000.toInt()

        const val TAG = "Gardendless"
    }
}
