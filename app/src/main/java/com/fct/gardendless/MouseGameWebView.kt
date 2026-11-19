package com.fct.gardendless

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

/**
 * 把触摸手势转换为鼠标事件，供游戏使用。
 *
 * 事件注入分两条路径：
 * - 移动与滚轮使用 native MotionEvent（SOURCE_MOUSE），以保证流畅度；
 * - 左键按下/抬起与右键点击使用 JS MouseEvent，直接派发到 GameCanvas。
 *
 * 触摸事件本身不再交给 WebView：页面只看到鼠标事件，避免同一次手势同时产生
 * touch 与 mouse 两套事件（重复点击、幽灵点击）。
 *
 * 手势映射：
 * - 单指拖动：左键按下 → 移动 → 抬起；
 * - 双指滑动：以两指中点作为光标位置，纵向位移映射为滚轮；
 * - 双指轻点（位移未超过 moveThreshold）：右键点击。
 */
class MouseGameWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var lastScrollY = 0f
    private var touchStartCenterX = 0f
    private var touchStartCenterY = 0f
    private var hasMovedEnough = false
    private val moveThreshold = 20f
    private var maxTouches = 0
    private var isTouching = false

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
                    isTouching = true
                    if (maxTouches == 1) {
                        // 左键按下（JS 事件，button 0）
                        injectJsMouseDownAt(event.x, event.y)
                        isDragging = true
                    }
                }
                // 触摸本身仍交给 WebView：游戏内不少窗口按钮是 Cocos Button，
                // 只响应 touch 事件（start/end），仅靠注入的鼠标事件点不动（例如关闭窗口的叉号）。
                return super.dispatchTouchEvent(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    // 进入双指手势，取消左键拖拽
                    isDragging = false
                    hasMovedEnough = false
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy

                    // 双指按下瞬间，先在两指中点补发一次 native 移动
                    injectMouseEventAt(cx, cy, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 移动统一使用 native 事件，以保证流畅度
                if (pointerCount == 1 && isDragging) {
                    injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                } else if (pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    val deltaTotal = abs(cy - touchStartCenterY)

                    if (hasMovedEnough || deltaTotal > moveThreshold) {
                        hasMovedEnough = true
                        val scrollDelta = cy - lastScrollY
                        // 滚轮使用 native 事件
                        injectScrollEventAt(touchStartCenterX, touchStartCenterY, scrollDelta * 2)
                        lastScrollY = cy
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (action == MotionEvent.ACTION_UP) {
                    isTouching = false
                    mainHandler.removeCallbacksAndMessages(null)

                    if (maxTouches == 1 && isDragging) {
                        // 左键抬起（JS 事件）
                        injectJsMouseUpAt(event.x, event.y)
                        maxTouches = 0
                        isDragging = false
                        hasMovedEnough = false
                    }
                    else if (maxTouches == 2 && !hasMovedEnough) {
                        // 双指轻点：右键点击
                        injectRightClickAt(touchStartCenterX, touchStartCenterY)
                    }

                    maxTouches = 0
                    isDragging = false
                    hasMovedEnough = false
                    // touchend 同样交给 WebView，让 Cocos Button 之类的控件正常收尾
                    return super.dispatchTouchEvent(event)
                }
            }
        }
        // 除按下/抬起外的触摸事件不再下发，避免同一次手势在页面里同时产生 touch 与 mouse 两套输入
        return true
    }

    /**
     * 左键按下：先把光标移到按下位置，等游戏跑完两帧后再派发 mousedown。
     *
     * 游戏（Cocos）每帧才根据 Mouse 组件坐标刷新「光标所在格」（mouseInLnC，用于判定点击落在
     * 哪一格/哪条车道）。若 mousemove 与 mousedown 落在同一帧，点击会按上一帧的格子处理：
     * 第一下只移动光标不生效，第二下可能在新旧两个格子各生效一次。
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     */
    private fun injectJsMouseDownAt(x: Float, y: Float) {
        val cssX = x / density
        val cssY = y / density

        val js = """
            (function() {
                var canvas = document.getElementById("GameCanvas");
                if (!canvas) return;

                function fire(type, button, buttons) {
                    canvas.dispatchEvent(new MouseEvent(type, {
                        view: window,
                        bubbles: true,
                        cancelable: true,
                        screenX: $x,
                        screenY: $y,
                        clientX: $cssX,
                        clientY: $cssY,
                        button: button,
                        buttons: buttons
                    }));
                }

                window.__gdMouseTap = { down: false };
                fire("mousemove", 0, 0);

                var frames = 2;
                (function step() {
                    if (frames-- > 0) {
                        requestAnimationFrame(step);
                        return;
                    }
                    var tap = window.__gdMouseTap;
                    if (!tap) return;
                    tap.down = true;
                    fire("mousedown", 0, 1);
                })();
            })();
        """.trimIndent()

        evaluateJavascript(js, null)
    }

    /**
     * 左键抬起：等待 mousedown 真正派发后再抬起，保证两者顺序不被打乱。
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     */
    private fun injectJsMouseUpAt(x: Float, y: Float) {
        val cssX = x / density
        val cssY = y / density

        val js = """
            (function() {
                var canvas = document.getElementById("GameCanvas");
                if (!canvas) return;

                function fire() {
                    canvas.dispatchEvent(new MouseEvent("mouseup", {
                        view: window,
                        bubbles: true,
                        cancelable: true,
                        screenX: $x,
                        screenY: $y,
                        clientX: $cssX,
                        clientY: $cssY,
                        button: 0,
                        buttons: 0
                    }));
                    window.__gdMouseTap = null;
                }

                var frames = 60;
                (function step() {
                    var tap = window.__gdMouseTap;
                    if (!tap || tap.down || frames-- <= 0) {
                        fire();
                        return;
                    }
                    requestAnimationFrame(step);
                })();
            })();
        """.trimIndent()

        evaluateJavascript(js, null)
    }

    /**
     * 右键点击：同样先移动光标、等两帧后再派发按下与抬起。
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     */
    private fun injectRightClickAt(x: Float, y: Float) {
        val cssX = x / density
        val cssY = y / density

        val js = """
            (function() {
                var canvas = document.getElementById("GameCanvas");
                if (!canvas) return;

                function fire(type, buttons) {
                    canvas.dispatchEvent(new MouseEvent(type, {
                        view: window,
                        bubbles: true,
                        cancelable: true,
                        screenX: $x,
                        screenY: $y,
                        clientX: $cssX,
                        clientY: $cssY,
                        button: 2,
                        buttons: buttons
                    }));
                }

                fire("mousemove", 0);

                var frames = 2;
                (function step() {
                    if (frames-- > 0) {
                        requestAnimationFrame(step);
                        return;
                    }
                    fire("mousedown", 2);
                    requestAnimationFrame(function() {
                        fire("mouseup", 0);
                    });
                })();
            })();
        """.trimIndent()

        evaluateJavascript(js, null)
    }

    /** 注入 native 鼠标事件，用于移动 */
    private fun injectMouseEventAt(x: Float, y: Float, action: Int, buttonState: Int) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), action,
            1, props, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** 注入 native 滚轮事件，用于双指滑动 */
    private fun injectScrollEventAt(x: Float, y: Float, delta: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, delta / 15f)
        })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), MotionEvent.ACTION_SCROLL,
            1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }
}
