package com.fct.gardendless

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

/**
 * 把触摸手势转换为鼠标事件，供游戏使用。
 *
 * 只把命中游戏 canvas 的手势转换为鼠标事件；DOM 覆盖层继续接收原始 WebView 触摸事件。
 *
 * 手势映射：
 * - 单指拖动：左键按下 → 移动 → 抬起；
 * - 双指滑动：以两指中点作为光标位置，纵向位移映射为滚轮；
 * - 双指轻点（位移未超过 moveThreshold）：右键点击。
 */
class MouseGameWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private var isDragging = false
    private var lastScrollY = 0f
    private var touchStartCenterX = 0f
    private var touchStartCenterY = 0f
    private var hasMovedEnough = false
    private val moveThreshold = 20f
    private var maxTouches = 0

    // 获取屏幕密度，用于将物理像素转换为CSS像素
    private val density = context.resources.displayMetrics.density

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.source == InputDevice.SOURCE_MOUSE) {
            return super.dispatchTouchEvent(event)
        }

        val action = event.actionMasked
        val pointerCount = event.pointerCount
        if (pointerCount > maxTouches) maxTouches = pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (pointerCount == 1) {
                    // JS dispatch targets GameCanvas only. DOM overlays receive the native touch.
                    injectJsMouseEvent(event.x, event.y, "mousedown", 0)
                    isDragging = true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    // 进入双指手势，取消左键拖拽
                    if (isDragging) {
                        injectJsMouseEvent(event.getX(0), event.getY(0), "mouseup", 0)
                    }
                    isDragging = false
                    hasMovedEnough = false
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy

                    injectJsMouseEvent(cx, cy, "mousemove", 0)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1 && isDragging) {
                    injectJsMouseEvent(event.x, event.y, "mousemove", 0)
                } else if (pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    val deltaTotal = abs(cy - touchStartCenterY)

                    if (hasMovedEnough || deltaTotal > moveThreshold) {
                        hasMovedEnough = true
                        val scrollDelta = cy - lastScrollY
                        injectCanvasWheelEventAt(
                            touchStartCenterX,
                            touchStartCenterY,
                            scrollDelta * 2
                        )
                        lastScrollY = cy
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (action == MotionEvent.ACTION_UP) {
                    if (maxTouches == 1 && isDragging) {
                        // 左键抬起（JS 事件）
                        injectJsMouseEvent(event.x, event.y, "mouseup", 0)
                        maxTouches = 0
                        isDragging = false
                        hasMovedEnough = false
                    }
                    else if (maxTouches == 2 && !hasMovedEnough) {
                        // 双指轻点：右键点击
                        injectRightClickAt(touchStartCenterX, touchStartCenterY)
                    }

                    if (action == MotionEvent.ACTION_UP) {
                        maxTouches = 0
                        isDragging = false
                        hasMovedEnough = false
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    injectJsMouseEvent(event.x, event.y, "mouseup", 0)
                }
                maxTouches = 0
                isDragging = false
                hasMovedEnough = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Canvas input is translated to mouse events; DOM overlays keep native WebView touch behavior.
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     * @param type 事件类型，"mousedown" 或 "mouseup"
     * @param button 按键：0=左键，2=右键
     */
    private fun injectJsMouseEvent(x: Float, y: Float, type: String, button: Int) {
        // 将物理坐标转换为 CSS 坐标
        val cssX = x / density
        val cssY = y / density

        // 构建 JS 脚本
        val js = """
            (function() {
                var canvas = document.getElementById("GameCanvas");
                if (!canvas) return;
                var element = document.elementFromPoint($cssX, $cssY);
                if (element !== canvas && !canvas.contains(element)) return;
                var ev = new MouseEvent('$type', {
                    view: window,
                    bubbles: true,
                    cancelable: true,
                    screenX: $x,
                    screenY: $y,
                    clientX: $cssX,
                    clientY: $cssY,
                    button: $button
                });
                element.dispatchEvent(ev);
            })();
        """.trimIndent()

        this.evaluateJavascript(js, null)
    }

    /** Two-finger game scrolling is sent only to the canvas; DOM controls retain native scrolling. */
    private fun injectCanvasWheelEventAt(x: Float, y: Float, delta: Float) {
        val cssX = x / density
        val cssY = y / density
        val js = """
            (function() {
                var canvas = document.getElementById("GameCanvas");
                if (!canvas) return;
                var element = document.elementFromPoint($cssX, $cssY);
                if (element !== canvas && !canvas.contains(element)) return;
                element.dispatchEvent(new WheelEvent("wheel", {
                    bubbles: true,
                    cancelable: true,
                    clientX: $cssX,
                    clientY: $cssY,
                    deltaY: $delta
                }));
            })();
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    /** 右键点击 is routed to the canvas only. */
    private fun injectRightClickAt(x: Float, y: Float) {
        injectJsMouseEvent(x, y, "mousemove", 0)
        injectJsMouseEvent(x, y, "mousedown", 2)
        injectJsMouseEvent(x, y, "mouseup", 2)
    }
}