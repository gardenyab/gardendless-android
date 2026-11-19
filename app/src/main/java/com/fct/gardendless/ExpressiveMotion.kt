package com.fct.gardendless

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.motion.MotionUtils
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel

internal object ExpressiveMotion {

    private const val EXPRESSIVE_FAST_DAMPING = 0.6f
    private const val EXPRESSIVE_FAST_STIFFNESS = 800f
    private const val EXPRESSIVE_DEFAULT_DAMPING = 0.8f
    private const val EXPRESSIVE_DEFAULT_STIFFNESS = 380f

    fun fastSpatialSpring(context: Context): SpringForce =
        MotionUtils.resolveThemeSpringForce(
            context,
            com.google.android.material.R.attr.motionSpringFastSpatial,
            R.style.Motion_Gardendless_Spring_Expressive_Fast_Spatial
        ).apply {
            if (stiffness <= 0f) stiffness = EXPRESSIVE_FAST_STIFFNESS
            if (dampingRatio <= 0f) dampingRatio = EXPRESSIVE_FAST_DAMPING
        }

    fun defaultSpatialSpring(context: Context): SpringForce =
        MotionUtils.resolveThemeSpringForce(
            context,
            com.google.android.material.R.attr.motionSpringDefaultSpatial,
            R.style.Motion_Gardendless_Spring_Expressive_Default_Spatial
        ).apply {
            if (stiffness <= 0f) stiffness = EXPRESSIVE_DEFAULT_STIFFNESS
            if (dampingRatio <= 0f) dampingRatio = EXPRESSIVE_DEFAULT_DAMPING
        }

    // ── Tactile Haptic Feedback ──

    fun hapticTap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun hapticHero(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(code)
    }

    fun hapticToggle(view: View, checked: Boolean) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checked) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun hapticSegment(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun hapticSliderTick(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun hapticConfirm(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(code)
    }

    // ── Spring Motion & Shape Morphing ──

    /**
     * Attaches a tactile Material 3 Expressive spring scale response (and optional haptic press)
     * on touch down/up without consuming click events.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachSpringPress(
        view: View,
        pressedScale: Float = 0.94f,
        withHaptic: Boolean = true
    ) {
        val springX = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            spring = fastSpatialSpring(view.context).setFinalPosition(1f)
        }
        val springY = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = fastSpatialSpring(view.context).setFinalPosition(1f)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (withHaptic) hapticTap(v)
                    springX.animateToFinalPosition(pressedScale)
                    springY.animateToFinalPosition(pressedScale)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springX.animateToFinalPosition(1f)
                    springY.animateToFinalPosition(1f)
                }
            }
            false
        }
    }

    /**
     * Expressive Pill-to-Squircle morphing + spring scale + tactile haptics for standalone
     * MaterialButtons (Hero button, Action buttons, Icon buttons).
     * Guarantees full-pill resting corners and smooth spring morphing on press.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachExpressiveButton(
        button: MaterialButton,
        pressedCornerDp: Float = 14f,
        pressedScale: Float = 0.96f,
        morphCorners: Boolean = true,
        isHero: Boolean = false
    ) {
        val density = button.resources.displayMetrics.density
        val pressedCornerPx = pressedCornerDp * density
        val springX = SpringAnimation(button, DynamicAnimation.SCALE_X, 1f).apply {
            spring = fastSpatialSpring(button.context).setFinalPosition(1f)
        }
        val springY = SpringAnimation(button, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = fastSpatialSpring(button.context).setFinalPosition(1f)
        }

        var currentCornerPx = -1f
        val cornerProp = object : FloatPropertyCompat<MaterialButton>("expressiveCorner") {
            override fun getValue(view: MaterialButton): Float {
                val pillRadius = (view.height.coerceAtLeast(1) / 2f)
                return if (currentCornerPx < 0f) pillRadius else currentCornerPx
            }

            override fun setValue(view: MaterialButton, value: Float) {
                val clamped = value.coerceAtLeast(pressedCornerPx * 0.7f)
                currentCornerPx = clamped
                view.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, clamped)
                    .build()
            }
        }
        val cornerSpring = SpringAnimation(button, cornerProp).apply {
            spring = fastSpatialSpring(button.context)
            minimumVisibleChange = 0.5f
        }

        button.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isHero) hapticHero(v) else hapticTap(v)
                    springX.animateToFinalPosition(pressedScale)
                    springY.animateToFinalPosition(pressedScale)
                    if (morphCorners && button.height > 0) {
                        val pillRadius = button.height / 2f
                        if (currentCornerPx < 0f) currentCornerPx = pillRadius
                        cornerSpring.animateToFinalPosition(pressedCornerPx.coerceAtMost(pillRadius))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springX.animateToFinalPosition(1f)
                    springY.animateToFinalPosition(1f)
                    if (morphCorners && button.height > 0) {
                        val pillRadius = button.height / 2f
                        cornerSpring.animateToFinalPosition(pillRadius)
                    }
                }
            }
            false
        }
    }

    /**
     * Expressive Circle-to-Squircle morphing + spring scale + tactile haptics for
     * circular MaterialCardView icon buttons.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachExpressiveCardButton(
        card: MaterialCardView,
        pressedCornerDp: Float = 14f,
        pressedScale: Float = 0.90f
    ) {
        val density = card.resources.displayMetrics.density
        val pressedCornerPx = pressedCornerDp * density
        val springX = SpringAnimation(card, DynamicAnimation.SCALE_X, 1f).apply {
            spring = fastSpatialSpring(card.context).setFinalPosition(1f)
        }
        val springY = SpringAnimation(card, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = fastSpatialSpring(card.context).setFinalPosition(1f)
        }

        var currentCornerPx = -1f
        val cornerProp = object : FloatPropertyCompat<MaterialCardView>("expressiveCardCorner") {
            override fun getValue(view: MaterialCardView): Float {
                val circleRadius = view.height.coerceAtLeast(1) / 2f
                return if (currentCornerPx < 0f) circleRadius else currentCornerPx
            }

            override fun setValue(view: MaterialCardView, value: Float) {
                val clamped = value.coerceAtLeast(pressedCornerPx * 0.7f)
                currentCornerPx = clamped
                view.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, clamped)
                    .build()
            }
        }
        val cornerSpring = SpringAnimation(card, cornerProp).apply {
            spring = fastSpatialSpring(card.context)
            minimumVisibleChange = 0.5f
        }

        card.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hapticTap(v)
                    springX.animateToFinalPosition(pressedScale)
                    springY.animateToFinalPosition(pressedScale)
                    if (card.height > 0) {
                        val circleRadius = card.height / 2f
                        if (currentCornerPx < 0f) currentCornerPx = circleRadius
                        cornerSpring.animateToFinalPosition(pressedCornerPx.coerceAtMost(circleRadius))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springX.animateToFinalPosition(1f)
                    springY.animateToFinalPosition(1f)
                    if (card.height > 0) {
                        cornerSpring.animateToFinalPosition(card.height / 2f)
                    }
                }
            }
            false
        }
    }

    /** Playful spring spin-and-pop + confirm haptic for the hero organic Cookie-12 emblem. */
    fun playEmblemSpinBounce(view: View) {
        hapticConfirm(view)
        val targetRotation = view.rotation + 30f
        SpringAnimation(view, DynamicAnimation.ROTATION, targetRotation).apply {
            spring = fastSpatialSpring(view.context).setFinalPosition(targetRotation)
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            setStartValue(0.86f)
            spring = fastSpatialSpring(view.context).setFinalPosition(1f)
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            setStartValue(0.86f)
            spring = fastSpatialSpring(view.context).setFinalPosition(1f)
            start()
        }
    }

    /** Crisp spring cross-transition when switching between floating bar tabs. */
    fun animateTabEnter(view: View, fromRight: Boolean) {
        val offset = (if (fromRight) 20f else -20f) * view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationX = offset
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
        SpringAnimation(view, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring = fastSpatialSpring(view.context).setFinalPosition(0f)
            start()
        }
    }

    /** Spring bounce for the active floating nav tab pill. */
    fun pulseNavTab(view: View) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            setStartValue(0.92f)
            spring = fastSpatialSpring(view.context).setFinalPosition(1f)
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            setStartValue(0.92f)
            spring = fastSpatialSpring(view.context).setFinalPosition(1f)
            start()
        }
    }

    /** Staggered spatial spring reveal for top-level launcher sections. */
    fun animateEntrance(views: List<View>) {
        val density = views.firstOrNull()?.resources?.displayMetrics?.density ?: return
        val offsetPx = 22f * density
        views.forEachIndexed { index, view ->
            if (view.visibility != View.VISIBLE) return@forEachIndexed
            view.alpha = 0f
            view.translationY = offsetPx
            val delay = index * 45L
            view.postDelayed({
                if (!view.isAttachedToWindow) return@postDelayed
                view.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .start()
                SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                    spring = defaultSpatialSpring(view.context).setFinalPosition(0f)
                    start()
                }
            }, delay)
        }
    }
}
