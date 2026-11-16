package com.fct.gardendless

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/** Canvas gestures are translated by the document shim; DOM controls keep native WebView touch input. */
class MouseGameWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        super.dispatchTouchEvent(event)
}
