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

package com.fct.gardendless

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class GameActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var aspectContainer: AspectRatioFrameLayout

    private val prefs by lazy { getSharedPreferences("app_data", MODE_PRIVATE) }

    /**
     * gp-next 数据目录，对应 Tauri 的 AppData 根。
     * 游戏会把 plugin:path|resolve_directory 的返回值拼上 `/gp-next`，
     * 因此数据实际位于 filesDir/gp-next，与 GameDocumentsProvider 的 gpnext 根指向同一目录。
     */
    private val gpNextDir: File by lazy { File(filesDir, GP_NEXT_DIR_NAME).apply { mkdirs() } }
    private val gpNextPathResolver: GpNextPathResolver by lazy {
        GpNextPathResolver(filesDir, gpNextDir)
    }

    private val rawGpNextShimJs: String by lazy {
        runCatching {
            assets.open(SHIM_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { e ->
            Log.e(TAG, "无法读取 $SHIM_ASSET，gp-next 的数据包与 JS 模组将不可用", e)
            ""
        }
    }

    /**
     * 注入页面的 Tauri shim（assets/gdnext-shim.js）。
     * 前置的 window.__gdForceJsModding 与 window.__gdAmbientWavesEnabled 由设置项控制。
     */
    private val gpNextShimJs: String
        get() {
            val js = rawGpNextShimJs
            if (js.isEmpty()) return ""
            val forceJsModding = prefs.getBoolean(PREF_WV_JS_MODDING, FORCE_JS_MODDING)
            val ambientWaves = prefs.getBoolean(PREF_AMBIENT_WAVES, false) &&
                loadSideMode() == AspectRatioFrameLayout.SideMode.AMBIENT
            return buildString {
                append("window.__gdForceJsModding=$forceJsModding;\n")
                append("window.__gdAmbientWavesEnabled=$ambientWaves;\n")
                append(js)
            }
        }

    private val FILE_CHOOSER_RESULT_CODE = 101
    private val EXPORT_SAVE_RESULT_CODE = 102
    private val GAME_ARCHIVE_REQUEST_CODE = 103

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 导出流程中解出的存档内容，等待用户在保存对话框里选定目标后再写入
    private var pendingExport: ByteArray? = null
    private var pendingExportFile: File? = null
    // 游戏经 Tauri dialog.save 给出的文件名，可能覆盖默认建议名
    private var pendingExportName: String? = null
    private val exportTransferLock = Any()
    private var activeExportTransfer: ExportTransfer? = null

    private data class ExportTransfer(
        val file: File,
        val fileName: String,
        val mimeType: String,
        val output: FileOutputStream
    )

    private enum class LauncherTab { HOME, SETTINGS, WEBVIEW_SETTINGS }

    // 网页全屏（HTML5 Fullscreen API）回调，非空表示当前处于全屏
    private var fullscreenCallback: CustomViewCallback? = null
    private var isLauncherVisible = true
    private var activeLauncherTab = LauncherTab.HOME
    private var hasCheckedUpdateOnLaunch = false
    private var isCheckingUpdate = false
    private var latestUpdateResult: UpdateCheckResult? = null
    private var refreshUpdateUi: (() -> Unit)? = null
    private var navigateBackInLauncherAction: (() -> Boolean)? = null
    private var isBackCallbackRegistered = false

    private companion object {
        const val PREF_FULLSCREEN = "webview_fullscreen"
        const val PREF_AUTO_LAUNCH_GAME = "auto_launch_game"
        const val PREF_WV_HIGH_REFRESH = "wv_high_refresh"
        const val PREF_WV_KEEP_SCREEN_ON = "wv_keep_screen_on"
        const val PREF_WV_HW_ACCEL = "wv_hw_accel"
        const val PREF_WV_JS_MODDING = "wv_js_modding"
        const val PREF_WV_DEBUGGING = "wv_debugging"

        /** 左右黑边的装饰方式：off / sprite / ambient（默认 off） */
        const val PREF_SIDE_MODE = "side_mode"

        /** AMBIENT 采样时从画面边缘向内缩进的距离（dp，默认 2，最大 16） */
        const val PREF_AMBIENT_INSET = "ambient_inset_dp"
        const val PREF_AMBIENT_WAVES = "ambient_waves_enabled"
        const val DEFAULT_AMBIENT_INSET_DP = 2
        const val MAX_AMBIENT_INSET_DP = 16

        /** 旧版本的布尔开关，仅用于迁移 */
        const val PREF_SIDE_ART_LEGACY = "side_art_enabled"

        /** 游戏内快捷按键，以逗号分隔的 Android keyCode 保存 */
        const val PREF_SHORTCUT_KEYS = "shortcut_keys"

        /** 快捷按键最多数量 */
        const val MAX_SHORTCUTS = 8

        /** 快捷按键列同时可见的行数，超出后滚动 */
        const val SHORTCUT_VISIBLE_ROWS = 3

        /** 快捷按键按钮高度与间距（dp） */
        const val SHORTCUT_BUTTON_HEIGHT_DP = 44
        const val SHORTCUT_BUTTON_GAP_DP = 8

        const val TAG = "Gardendless"
        const val SHIM_ASSET = "gdnext-shim.js"
        const val GP_NEXT_DIR_NAME = "gp-next"
        const val TELEGRAM_CHANNEL_URL = "https://t.me/pvz2gardendless"
        const val GITHUB_RELEASES_URL = "https://github.com/gardenyab/gardendless-android/releases"

        /**
         * 是否在启动时自动启用 gp-next 的 JS Modding 开关。
         *
         * 该开关默认关闭，且游戏「实验性」页中的对应开关处于锁定状态（pointer-events:none，
         * 回调会把 true 还原为 false），唯一入口是控制台的 window.gpNext.mods.enableJsModding()。
         * 移植版没有可用的控制台，因此由 gdnext-shim.js 代为调用一次。
         *
         * 置为 false 可恢复原行为（需手动从 devtools 开启）。
         */
        const val FORCE_JS_MODDING = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setupBackNavigation()
        val shouldAutoLaunch = savedInstanceState == null &&
            prefs.getBoolean(PREF_AUTO_LAUNCH_GAME, false) &&
            GameArchiveInstaller(filesDir).installedSiteDirectory() != null
        if (shouldAutoLaunch) launchGame() else showGameMenu(animateEntrance = savedInstanceState == null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isLauncherVisible) showGameMenu(animateEntrance = false)
    }

    private fun launchGame() {
        if (GameArchiveInstaller(filesDir).installedSiteDirectory() == null) {
            showGameMenu()
            return
        }
        isLauncherVisible = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setGameRefreshRate(prefs.getBoolean(PREF_WV_HIGH_REFRESH, true))
        setupFullScreen()
        setupWebview()
    }

    private fun setGameRefreshRate(enabled: Boolean) {
        val rate = if (enabled) maxSupportedRefreshRate() else 0f
        window.attributes = window.attributes.apply {
            preferredRefreshRate = rate
        }
        if (enabled && rate > 0f) {
            Log.i(TAG, "Requested game display refresh rate: $rate Hz")
        }
    }

    @Suppress("DEPRECATION")
    private fun maxSupportedRefreshRate(): Float =
        windowManager.defaultDisplay.supportedModes.maxOfOrNull { it.refreshRate } ?: 0f

    /** 装配 WebView、AssetLoader、JS 桥与各回调，并加载已导入的游戏 */
    private fun setupWebview() {
            val gameSiteDir = GameArchiveInstaller(filesDir).installedSiteDirectory()
            if (gameSiteDir == null) {
                showGameMenu()
                return
            }
            WebView.setWebContentsDebuggingEnabled(prefs.getBoolean(PREF_WV_DEBUGGING, false))
            webView = MouseGameWebView(this).apply {
                val layer = if (prefs.getBoolean(PREF_WV_HW_ACCEL, true)) {
                    View.LAYER_TYPE_HARDWARE
                } else {
                    View.LAYER_TYPE_NONE
                }
                setLayerType(layer, null)
            }

            // 黑底容器，负责 16:10 ~ 17:9 的比例适配与全屏切换
            aspectContainer = AspectRatioFrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // 沿用上次退出时的全屏状态，避免先小后大的尺寸跳变
                fullscreen = prefs.getBoolean(PREF_FULLSCREEN, false)
                // 左右黑边装饰（默认纯黑）
                val mode = loadSideMode()
                if (mode == AspectRatioFrameLayout.SideMode.SPRITE) {
                    sideArt = loadSideArtBitmap()
                }
                ambientEdgeInsetDp = loadAmbientInsetDp().toFloat()
                ambientWavesEnabled = prefs.getBoolean(PREF_AMBIENT_WAVES, false)
                sideMode = mode
                // 设置 WebView 居中显示
                addView(
                    webView,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.CENTER }
                )
                addView(
                    createGameControls(),
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.END or android.view.Gravity.TOP
                    ).apply {
                        topMargin = dp(12)
                        marginEnd = dp(12)
                    }
                )
            }

            // 将容器设置为 Content View
            setContentView(aspectContainer)

            // AssetLoader 把 https://appassets.androidplatform.net/ 映射到本地目录。
            //
            // /gp-next/ 供 shim 读取 gp-next 数据：shim 中的 plugin:fs|read_text_file 与 read_file
            // 通过 fetch 该虚拟路径获取流式的原始字节，无需经 JS 桥传送 base64。
            // gpNextDir 的懒初始化会先确保目录存在，否则该路径下的请求全部 404。
            //
            // 注册顺序不可调整：WebViewAssetLoader 按注册顺序遍历 handler，返回第一个非 null 的响应；
            // 而 "/" 能匹配任意路径，且 InternalStoragePathHandler 在文件不存在时返回的是 404 空响应
            // （并非 null），因此 "/" 若排在前面会拦截 /gp-next/ 的请求，表现为到 docs 目录下查找
            // gp-next/... 并输出 "Error opening the requested path"。
            // 依据：androidx.webkit 1.15.0 的 WebViewAssetLoader.shouldInterceptRequest 与
            // Builder.addPathHandler。
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler(
                    "/$GP_NEXT_DIR_NAME/",
                    InternalStoragePathHandler(this, gpNextDir)
                )
                .addPathHandler(
                    "/",
                    InternalStoragePathHandler(this, gameSiteDir)
                )
                .build()

            webView.settings.apply {
                javaScriptEnabled = true
                // 游戏与 gp-next 的设置都依赖 localStorage
                domStorageEnabled = true
                // 资源统一经 AssetLoader 提供，文件与 content 访问保持关闭
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
            }
            webView.overScrollMode = View.OVER_SCROLL_NEVER
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
            webView.setBackgroundColor(Color.BLACK)

            // 网页到原生的桥。游戏运行在 Tauri polyfill 之上，其中两件事在 WebView 里会失效：
            // 全屏被实现为空函数；保存文件名使用 prompt 而 WebView 不响应。
            // 因此由 JS 层截获意图后经此桥转发。其余方法面向 gp-next 数据目录的读写，
            // 由 assets/gdnext-shim.js 调用。
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun setFullscreen(value: Boolean) {
                    webView.post { setWebviewFullscreen(value) }
                }

                @JavascriptInterface
                fun setAmbientAudioSpectrum(level: Float, bass: Float, mid: Float, high: Float) {
                    if (::aspectContainer.isInitialized) {
                        aspectContainer.updateAmbientAudioSpectrum(level, bass, mid, high)
                    }
                }

                @JavascriptInterface
                fun setExportName(name: String) {
                    pendingExportName = name
                }

                @JavascriptInterface
                fun exportDataUri(dataUri: String, fileName: String) {
                    webView.post { beginSaveExport(dataUri, fileName, null, null) }
                }

                @JavascriptInterface
                fun beginExportTransfer(fileName: String, mimeType: String): Boolean =
                    beginExportTransferNative(fileName, mimeType)

                @JavascriptInterface
                fun appendExportChunk(base64: String): Boolean = appendExportChunkNative(base64)

                @JavascriptInterface
                fun finishExportTransfer(mimeType: String): Boolean =
                    finishExportTransferNative(mimeType)

                @JavascriptInterface
                fun cancelExportTransfer() {
                    cancelExportTransferNative()
                }

                // ── gp-next 数据目录（Tauri plugin:path / plugin:fs 的落地实现）──

                /** plugin:path|resolve_directory(14)：返回 AppData 根，游戏会自行拼上 /gp-next */
                @JavascriptInterface
                fun appDataRoot(): String = filesDir.absolutePath

                /** plugin:fs|read_dir：返回 [{name, isFile, isDirectory, isSymlink}] 形式的 JSON */
                @JavascriptInterface
                fun fsReadDir(rel: String): String? = gpNextReadDir(rel)

                /**
                 * plugin:fs|stat 与 plugin:fs|lstat。
                 * dist-js 会把返回值直接映射为 FileInfo（首个字段即 isFile），
                 * 因此路径不存在时返回 null 交由调用方转成 reject，其余字段不可缺失。
                 */
                @JavascriptInterface
                fun fsStat(rel: String): String? = gpNextStatJson(rel)

                /** plugin:fs|rename（新版 dist-js 新增的命令） */
                @JavascriptInterface
                fun fsRename(oldRel: String, newRel: String): Boolean = gpNextRename(oldRel, newRel)

                /** plugin:fs|write_file（新版 dist-js 新增的命令），内容以 base64 传入 */
                @JavascriptInterface
                fun fsWriteBytes(rel: String, base64: String): Boolean = gpNextWriteBytes(rel, base64)

                /** plugin:fs|exists */
                @JavascriptInterface
                fun fsExists(rel: String): Boolean = gpNextFile(rel).exists()

                /** plugin:fs|mkdir */
                @JavascriptInterface
                fun fsMkdir(rel: String): Boolean = gpNextMkdir(rel)

                /** plugin:fs|write_text_file */
                @JavascriptInterface
                fun fsWriteText(rel: String, text: String): Boolean = gpNextWriteText(rel, text)

                /** plugin:fs|remove */
                @JavascriptInterface
                fun fsRemove(rel: String): Boolean = gpNextRemove(rel)

                /** plugin:opener|open_path：以系统文件管理器打开 gp-next 数据目录 */
                @JavascriptInterface
                fun openDataFolder() {
                    webView.post { openGpNextFolder() }
                }
            }, "GardendlessBridge")

            val shimAtDocumentStart = gpNextShimJs.isNotEmpty() &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (shimAtDocumentStart) {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    gpNextShimJs,
                    setOf("https://appassets.androidplatform.net")
                )
            }
            webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                beginSaveExport(url, pendingExportName, contentDisposition, mimeType)
            }

            webView.webViewClient = object : WebViewClient() {
                /** 内部资源留在 WebView 中加载，外部链接交给系统浏览器 */
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    if (url.startsWith("https://appassets.androidplatform.net/") ||
                        url.startsWith("data:") ||
                        url.startsWith("blob:")
                    ) {
                        return false
                    }

                    try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        startActivity(intent)
                        // 已由原生处理，阻止 WebView 继续加载
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return false
                    }
                }
                /** 注入兼容 shim；游戏触摸输入由 MouseGameWebView 转为鼠标事件。 */
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!shimAtDocumentStart && gpNextShimJs.isNotEmpty()) {
                        view?.evaluateJavascript(gpNextShimJs, null)
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    if (!shimAtDocumentStart && gpNextShimJs.isNotEmpty()) {
                        view?.evaluateJavascript(gpNextShimJs, null)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    // 交由 AssetLoader 解析（游戏资源与 /gp-next/ 虚拟路径）
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val message = "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "WebView: $message")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "WebView: $message")
                        else -> Log.d(TAG, "WebView: $message")
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 保存回调以便在 onActivityResult 中使用
                    this@GameActivity.filePathCallback = filePathCallback

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)

                        // 主类型放宽为通配符，使选择器能显示更多文件
                        type = "*/*"

                        // 显式补充类型：各系统对 .json5 的识别结果不一致
                        val mimeTypes = arrayOf(
                            "application/json",
                            "application/octet-stream", // 部分系统识别为二进制
                            "text/plain"                // 部分系统识别为纯文本
                        )
                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    }
                    try {
                        startActivityForResult(intent!!, FILE_CHOOSER_RESULT_CODE)
                    } catch (e: Exception) {
                        this@GameActivity.filePathCallback = null
                        return false
                    }
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    val jsResult = result ?: return false
                    MaterialAlertDialogBuilder(this@GameActivity)
                        .setTitle(R.string.game_dialog_title)
                        .setMessage(message.orEmpty())
                        .setPositiveButton(android.R.string.ok) { _, _ -> jsResult.confirm() }
                        .setOnCancelListener { jsResult.cancel() }
                        .show()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    val jsResult = result ?: return false
                    MaterialAlertDialogBuilder(this@GameActivity)
                        .setTitle(R.string.game_dialog_title)
                        .setMessage(message.orEmpty())
                        .setNegativeButton(android.R.string.cancel) { _, _ -> jsResult.cancel() }
                        .setPositiveButton(android.R.string.ok) { _, _ -> jsResult.confirm() }
                        .setOnCancelListener { jsResult.cancel() }
                        .show()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    val jsResult = result ?: return false
                    val input = EditText(this@GameActivity).apply {
                        setText(defaultValue.orEmpty())
                        setSingleLine(true)
                        inputType = InputType.TYPE_CLASS_TEXT
                    }
                    MaterialAlertDialogBuilder(this@GameActivity)
                        .setTitle(R.string.game_dialog_title)
                        .setMessage(message.orEmpty())
                        .setView(input)
                        .setNegativeButton(android.R.string.cancel) { _, _ -> jsResult.cancel() }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            jsResult.confirm(input.text.toString())
                        }
                        .setOnCancelListener { jsResult.cancel() }
                        .show()
                    return true
                }

                override fun onJsBeforeUnload(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    val jsResult = result ?: return false
                    MaterialAlertDialogBuilder(this@GameActivity)
                        .setTitle(R.string.game_dialog_title)
                        .setMessage(message.orEmpty())
                        .setNegativeButton(android.R.string.cancel) { _, _ -> jsResult.cancel() }
                        .setPositiveButton(android.R.string.ok) { _, _ -> jsResult.confirm() }
                        .setOnCancelListener { jsResult.cancel() }
                        .show()
                    return true
                }

                // 网页调用 element.requestFullscreen() 时触发
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreenCallback != null) {
                        // 已在全屏，拒绝重复进入并立刻通知网页
                        callback.onCustomViewHidden()
                        return
                    }
                    fullscreenCallback = callback
                    setWebviewFullscreen(true)
                }

                // 网页调用 document.exitFullscreen() 时触发
                override fun onHideCustomView() {
                    val callback = fullscreenCallback ?: return
                    // 必须回调 onCustomViewHidden，否则 WebView 内部会一直停留在全屏状态
                    callback.onCustomViewHidden()
                    fullscreenCallback = null
                    setWebviewFullscreen(false)
                }
            }

            // 加载入口页：https://appassets.androidplatform.net/ 已映射到游戏 docs 目录
            webView.loadUrl("https://appassets.androidplatform.net/index.html")

            setupBackNavigation()
    }

    private fun showGameMenu(animateEntrance: Boolean = true) {
        isLauncherVisible = true
        setGameRefreshRate(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        val surfaceContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainer,
            Color.BLACK
        )
        window.setBackgroundDrawable(ColorDrawable(surfaceContainer))

        setContentView(R.layout.activity_launcher)

        val root = findViewById<View>(R.id.launcherRoot)
        val homeScroll = findViewById<NestedScrollView>(R.id.homeScroll)
        val homeContent = findViewById<LinearLayout>(R.id.homeContent)
        val homeBodyContainer = findViewById<LinearLayout>(R.id.homeBodyContainer)
        val settingsScroll = findViewById<NestedScrollView>(R.id.settingsScroll)
        val settingsContent = findViewById<LinearLayout>(R.id.settingsContent)
        val webViewSettingsScroll = findViewById<NestedScrollView>(R.id.webViewSettingsScroll)
        val webViewSettingsContent = findViewById<LinearLayout>(R.id.webViewSettingsContent)
        val webViewBodyContainer = findViewById<LinearLayout>(R.id.webViewBodyContainer)
        val webViewColumnPerformance = findViewById<LinearLayout>(R.id.webViewColumnPerformance)
        val webViewColumnEngine = findViewById<LinearLayout>(R.id.webViewColumnEngine)
        val webViewEngineHeader = findViewById<View>(R.id.webViewEngineHeader)
        val floatingNavContainer = findViewById<View>(R.id.floatingNavContainer)
        val floatingNavCard = findViewById<MaterialCardView>(R.id.floatingNavCard)
        val floatingNavItems = findViewById<LinearLayout>(R.id.floatingNavItems)
        val navTabHome = findViewById<MaterialCardView>(R.id.navTabHome)
        val navHomeIcon = findViewById<ImageView>(R.id.navHomeIcon)
        val navHomeLabel = findViewById<MaterialTextView>(R.id.navHomeLabel)
        val navTabSettings = findViewById<MaterialCardView>(R.id.navTabSettings)
        val navSettingsIcon = findViewById<ImageView>(R.id.navSettingsIcon)
        val navSettingsLabel = findViewById<MaterialTextView>(R.id.navSettingsLabel)
        val navUpdateDot = findViewById<View>(R.id.navUpdateDot)

        val headerRow = findViewById<View>(R.id.headerRow)
        val btnGpNextFolder = findViewById<MaterialCardView>(R.id.btnGpNextFolder)
        val heroCard = findViewById<MaterialCardView>(R.id.heroCard)
        val heroWatermark = findViewById<ImageView>(R.id.heroWatermark)
        val heroEmblem = findViewById<View>(R.id.heroEmblem)
        val heroEmblemBg = findViewById<ImageView>(R.id.heroEmblemBg)
        val heroEmblemIcon = findViewById<ImageView>(R.id.heroEmblemIcon)
        val statusIcon = findViewById<ImageView>(R.id.statusIcon)
        val statusText = findViewById<MaterialTextView>(R.id.statusText)
        val heroTitle = findViewById<MaterialTextView>(R.id.heroTitle)
        val heroDescription = findViewById<MaterialTextView>(R.id.heroDescription)
        val btnHeroPrimary = findViewById<MaterialButton>(R.id.btnHeroPrimary)
        val actionButtonGroup = findViewById<LinearLayout>(R.id.actionButtonGroup)
        val btnUpdateZip = findViewById<MaterialButton>(R.id.btnUpdateZip)
        val btnTelegram = findViewById<MaterialButton>(R.id.btnTelegram)

        val versionBadge = findViewById<MaterialTextView>(R.id.versionBadge)
        val cardAutoLaunch = findViewById<MaterialCardView>(R.id.cardAutoLaunch)
        val switchAutoLaunch = findViewById<MaterialSwitch>(R.id.switchAutoLaunch)
        val sideModeGroup = findViewById<MaterialButtonToggleGroup>(R.id.sideModeGroup)
        val ambientInsetContainer = findViewById<View>(R.id.ambientInsetContainer)
        val rowAmbientWaves = findViewById<View>(R.id.rowAmbientWaves)
        val switchAmbientWaves = findViewById<MaterialSwitch>(R.id.switchAmbientWaves)
        val ambientInsetBadge = findViewById<MaterialTextView>(R.id.ambientInsetBadge)
        val ambientInsetSlider = findViewById<Slider>(R.id.ambientInsetSlider)
        val shortcutCountBadge = findViewById<MaterialTextView>(R.id.shortcutCountBadge)
        val shortcutEmptyText = findViewById<View>(R.id.shortcutEmptyText)
        val shortcutChipGroup = findViewById<ChipGroup>(R.id.shortcutChipGroup)
        val btnAddShortcut = findViewById<MaterialButton>(R.id.btnAddShortcut)
        val cardWebViewSettings = findViewById<MaterialCardView>(R.id.cardWebViewSettings)
        val updateStatusText = findViewById<MaterialTextView>(R.id.updateStatusText)
        val updateCheckingIndicator = findViewById<LoadingIndicator>(R.id.updateCheckingIndicator)
        val btnCheckUpdates = findViewById<MaterialButton>(R.id.btnCheckUpdates)
        val btnDownloadUpdate = findViewById<MaterialButton>(R.id.btnDownloadUpdate)
        val cardGpNextFolder = findViewById<MaterialCardView>(R.id.cardGpNextFolder)

        val btnWebViewBack = findViewById<MaterialCardView>(R.id.btnWebViewBack)
        val webViewVersionPill = findViewById<View>(R.id.webViewVersionPill)
        val webViewVersionBadge = findViewById<MaterialTextView>(R.id.webViewVersionBadge)
        val cardWvHighRefresh = findViewById<MaterialCardView>(R.id.cardWvHighRefresh)
        val switchWvHighRefresh = findViewById<MaterialSwitch>(R.id.switchWvHighRefresh)
        val cardWvFullscreen = findViewById<MaterialCardView>(R.id.cardWvFullscreen)
        val switchWvFullscreen = findViewById<MaterialSwitch>(R.id.switchWvFullscreen)
        val cardWvKeepScreenOn = findViewById<MaterialCardView>(R.id.cardWvKeepScreenOn)
        val switchWvKeepScreenOn = findViewById<MaterialSwitch>(R.id.switchWvKeepScreenOn)
        val cardWvHwAccel = findViewById<MaterialCardView>(R.id.cardWvHwAccel)
        val switchWvHwAccel = findViewById<MaterialSwitch>(R.id.switchWvHwAccel)
        val cardWvJsModding = findViewById<MaterialCardView>(R.id.cardWvJsModding)
        val switchWvJsModding = findViewById<MaterialSwitch>(R.id.switchWvJsModding)
        val cardWvDebugging = findViewById<MaterialCardView>(R.id.cardWvDebugging)
        val switchWvDebugging = findViewById<MaterialSwitch>(R.id.switchWvDebugging)
        val btnWvClearCache = findViewById<MaterialButton>(R.id.btnWvClearCache)

        // ── Tablet & Foldable Adaptive Layout (Floating Rail + 2-Pane Home on wide screens) ──
        val screenWidthDp = resources.configuration.screenWidthDp
        val isWideLayout = screenWidthDp >= 600
        val isTwoPaneHome = screenWidthDp >= 720

        if (isWideLayout) {
            // Floating vertical navigation rail docked cleanly on the left edge
            floatingNavContainer.layoutParams = (floatingNavContainer.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            floatingNavCard.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(28).toFloat())
                .build()
            floatingNavItems.orientation = LinearLayout.VERTICAL
            navTabHome.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            navTabSettings.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(6) }
            homeContent.setPadding(dp(24), 0, dp(24), dp(36))
            settingsContent.setPadding(dp(24), 0, dp(24), dp(36))
            webViewSettingsContent.setPadding(dp(24), 0, dp(24), dp(36))
        } else {
            // Centered floating bottom pill bar for phones
            floatingNavContainer.layoutParams = (floatingNavContainer.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            floatingNavCard.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(100).toFloat())
                .build()
            floatingNavItems.orientation = LinearLayout.HORIZONTAL
            navTabHome.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            )
            navTabSettings.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            ).apply { marginStart = dp(4) }
            homeContent.setPadding(dp(16), 0, dp(16), dp(108))
            settingsContent.setPadding(dp(16), 0, dp(16), dp(108))
            webViewSettingsContent.setPadding(dp(16), 0, dp(16), dp(36))
        }

        if (isTwoPaneHome) {
            homeBodyContainer.orientation = LinearLayout.HORIZONTAL
            heroCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
            actionButtonGroup.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                marginStart = dp(16)
                topMargin = 0
            }

            webViewBodyContainer.orientation = LinearLayout.HORIZONTAL
            webViewColumnPerformance.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            webViewColumnEngine.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            }
            webViewEngineHeader.layoutParams = (webViewEngineHeader.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
            }
        } else {
            homeBodyContainer.orientation = LinearLayout.VERTICAL
            heroCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            actionButtonGroup.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 0
                topMargin = dp(14)
            }

            webViewBodyContainer.orientation = LinearLayout.VERTICAL
            webViewColumnPerformance.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            webViewColumnEngine.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 0
            }
            webViewEngineHeader.layoutParams = (webViewEngineHeader.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = dp(22)
            }
        }

        val railWidthPx = if (isWideLayout) dp(168) else 0
        val availableWidth = (resources.displayMetrics.widthPixels - railWidthPx).coerceAtLeast(dp(320))
        val homeMaxWidth = if (isTwoPaneHome) dp(920) else dp(560)
        val settingsMaxWidth = if (isWideLayout) dp(720) else dp(560)
        val webViewMaxWidth = if (isTwoPaneHome) dp(920) else settingsMaxWidth
        homeContent.layoutParams = (homeContent.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (availableWidth > homeMaxWidth) homeMaxWidth else ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER_HORIZONTAL
        }
        settingsContent.layoutParams = (settingsContent.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (availableWidth > settingsMaxWidth) settingsMaxWidth else ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER_HORIZONTAL
        }
        webViewSettingsContent.layoutParams = (webViewSettingsContent.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (availableWidth > webViewMaxWidth) webViewMaxWidth else ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Edge-to-edge inset handling for all pages and the floating navigation bar/rail
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            homeScroll.setPadding(bars.left + railWidthPx, bars.top, bars.right, bars.bottom)
            settingsScroll.setPadding(bars.left + railWidthPx, bars.top, bars.right, bars.bottom)
            webViewSettingsScroll.setPadding(bars.left + railWidthPx, bars.top, bars.right, bars.bottom)
            if (isWideLayout) {
                floatingNavContainer.setPadding(
                    dp(16) + bars.left,
                    dp(16) + bars.top,
                    dp(8),
                    dp(16) + bars.bottom
                )
            } else {
                floatingNavContainer.setPadding(
                    dp(16) + bars.left,
                    dp(8),
                    dp(16) + bars.right,
                    dp(16) + bars.bottom
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val hasGame = GameArchiveInstaller(filesDir).installedSiteDirectory() != null
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.GREEN)
        val primaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, primary)
        val onPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK)
        val secondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, Color.DKGRAY)
        val onSecondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE)
        val tertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary, primary)
        val onTertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnTertiary, Color.WHITE)
        val tertiaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiaryContainer, secondaryContainer)
        val onTertiaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnTertiaryContainer, onSecondaryContainer)
        val surfaceContainerLowest = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.WHITE)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)

        // ── Home Tab: Hero Launch Card (consistent rich dark tertiaryContainer in both states) ──
        heroCard.setCardBackgroundColor(tertiaryContainer)
        ImageViewCompat.setImageTintList(heroWatermark, ColorStateList.valueOf(tertiary))
        ImageViewCompat.setImageTintList(heroEmblemBg, ColorStateList.valueOf(tertiary))
        ImageViewCompat.setImageTintList(heroEmblemIcon, ColorStateList.valueOf(onTertiary))
        ImageViewCompat.setImageTintList(statusIcon, ColorStateList.valueOf(tertiary))
        heroTitle.setTextColor(onTertiaryContainer)
        heroDescription.setTextColor(onTertiaryContainer)
        btnHeroPrimary.backgroundTintList = ColorStateList.valueOf(tertiary)
        btnHeroPrimary.setTextColor(onTertiary)
        btnHeroPrimary.iconTint = ColorStateList.valueOf(onTertiary)

        if (hasGame) {
            heroEmblemIcon.setImageResource(R.drawable.ic_play_arrow)
            statusIcon.setImageResource(R.drawable.ic_check)
            statusText.setText(R.string.game_status_installed)
            heroTitle.setText(R.string.game_ready_title)

            btnHeroPrimary.setText(R.string.launch_game)
            btnHeroPrimary.icon = AppCompatResources.getDrawable(this, R.drawable.ic_play_arrow)
            btnHeroPrimary.setOnClickListener { launchGame() }

            btnUpdateZip.visibility = View.VISIBLE
            btnUpdateZip.backgroundTintList = ColorStateList.valueOf(secondaryContainer)
            btnUpdateZip.setTextColor(onSecondaryContainer)
            btnUpdateZip.iconTint = ColorStateList.valueOf(onSecondaryContainer)
            btnUpdateZip.setOnClickListener { confirmGameUpdate() }

            btnTelegram.layoutParams = (btnTelegram.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = dp(10)
            }
            btnTelegram.backgroundTintList = ColorStateList.valueOf(surfaceContainerLowest)
            btnTelegram.setTextColor(onSurface)
            btnTelegram.iconTint = ColorStateList.valueOf(primary)
            btnTelegram.setOnClickListener { showDownloadArchiveSheet() }
        } else {
            heroEmblemIcon.setImageResource(R.drawable.ic_folder_open)
            statusIcon.setImageResource(R.drawable.ic_warning)
            statusText.setText(R.string.game_status_missing)
            heroTitle.setText(R.string.game_setup_title)

            btnHeroPrimary.setText(R.string.choose_game_zip)
            btnHeroPrimary.icon = AppCompatResources.getDrawable(this, R.drawable.ic_folder_open)
            btnHeroPrimary.setOnClickListener { selectGameArchive() }

            btnUpdateZip.visibility = View.GONE
            btnTelegram.layoutParams = (btnTelegram.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
            }
            btnTelegram.backgroundTintList = ColorStateList.valueOf(secondaryContainer)
            btnTelegram.setTextColor(onSecondaryContainer)
            btnTelegram.iconTint = ColorStateList.valueOf(onSecondaryContainer)
            btnTelegram.setOnClickListener { showDownloadArchiveSheet() }
        }

        // Tactile haptics + Expressive spring press & pill-to-squircle corner morphing
        heroEmblem.setOnClickListener { ExpressiveMotion.playEmblemSpinBounce(heroEmblemBg) }
        ExpressiveMotion.attachExpressiveButton(
            btnHeroPrimary,
            pressedCornerDp = 18f,
            pressedScale = 0.97f,
            morphCorners = true,
            isHero = true
        )
        ExpressiveMotion.attachExpressiveCardButton(
            btnGpNextFolder,
            pressedCornerDp = 14f,
            pressedScale = 0.90f
        )
        ExpressiveMotion.attachExpressiveButton(
            btnUpdateZip,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true
        )
        ExpressiveMotion.attachExpressiveButton(
            btnTelegram,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true
        )
        ExpressiveMotion.attachExpressiveButton(
            btnAddShortcut,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true
        )
        ExpressiveMotion.attachExpressiveButton(
            btnCheckUpdates,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true
        )
        ExpressiveMotion.attachExpressiveButton(
            btnDownloadUpdate,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true,
            isHero = true
        )
        ExpressiveMotion.attachSpringPress(cardGpNextFolder, pressedScale = 0.97f, withHaptic = true)

        btnGpNextFolder.setOnClickListener { openGpNextFolder() }
        cardGpNextFolder.setOnClickListener { openGpNextFolder() }

        val currentVersion = currentLauncherVersion()
        versionBadge.text = "v$currentVersion"

        // ── Settings Tab 1: Auto-launch switch card ──
        val initialAutoLaunch = prefs.getBoolean(PREF_AUTO_LAUNCH_GAME, false)
        switchAutoLaunch.isChecked = initialAutoLaunch
        syncSwitchThumbIcon(switchAutoLaunch, initialAutoLaunch)
        switchAutoLaunch.setOnCheckedChangeListener { view, checked ->
            syncSwitchThumbIcon(switchAutoLaunch, checked)
            ExpressiveMotion.hapticToggle(view, checked)
            prefs.edit().putBoolean(PREF_AUTO_LAUNCH_GAME, checked).apply()
        }
        cardAutoLaunch.setOnClickListener {
            switchAutoLaunch.isChecked = !switchAutoLaunch.isChecked
        }

        // ── Settings Tab 2: Side bars connected expressive selector + ambient waves + inset slider ──
        val currentSideMode = loadSideMode()
        val initialModeId = when (currentSideMode) {
            AspectRatioFrameLayout.SideMode.OFF -> R.id.btnSideOff
            AspectRatioFrameLayout.SideMode.SPRITE -> R.id.btnSideSprite
            AspectRatioFrameLayout.SideMode.AMBIENT -> R.id.btnSideAmbient
        }
        sideModeGroup.check(initialModeId)
        ambientInsetContainer.visibility =
            if (currentSideMode == AspectRatioFrameLayout.SideMode.AMBIENT) View.VISIBLE else View.GONE

        sideModeGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            ExpressiveMotion.hapticSegment(group)
            val mode = when (checkedId) {
                R.id.btnSideSprite -> AspectRatioFrameLayout.SideMode.SPRITE
                R.id.btnSideAmbient -> AspectRatioFrameLayout.SideMode.AMBIENT
                else -> AspectRatioFrameLayout.SideMode.OFF
            }
            applySideMode(mode)
            ambientInsetContainer.visibility =
                if (mode == AspectRatioFrameLayout.SideMode.AMBIENT) View.VISIBLE else View.GONE
        }

        val initialAmbientWaves = prefs.getBoolean(PREF_AMBIENT_WAVES, false)
        switchAmbientWaves.isChecked = initialAmbientWaves
        syncSwitchThumbIcon(switchAmbientWaves, initialAmbientWaves)
        switchAmbientWaves.setOnCheckedChangeListener { view, checked ->
            syncSwitchThumbIcon(switchAmbientWaves, checked)
            ExpressiveMotion.hapticToggle(view, checked)
            applyAmbientWaves(checked)
        }
        rowAmbientWaves.setOnClickListener {
            switchAmbientWaves.isChecked = !switchAmbientWaves.isChecked
        }

        fun updateInsetBadge(dpValue: Int) {
            ambientInsetBadge.text = getString(R.string.ambient_inset_value, dpValue)
        }
        val initialInset = loadAmbientInsetDp().coerceIn(0, MAX_AMBIENT_INSET_DP)
        var lastHapticInset = initialInset
        updateInsetBadge(initialInset)
        ambientInsetSlider.valueFrom = 0f
        ambientInsetSlider.valueTo = MAX_AMBIENT_INSET_DP.toFloat()
        ambientInsetSlider.stepSize = 1f
        ambientInsetSlider.value = initialInset.toFloat()
        ambientInsetSlider.setLabelFormatter { value -> getString(R.string.ambient_inset_value, value.toInt()) }
        ambientInsetSlider.addOnChangeListener { slider, value, fromUser ->
            val inset = value.toInt()
            updateInsetBadge(inset)
            if (::aspectContainer.isInitialized) {
                aspectContainer.ambientEdgeInsetDp = inset.toFloat()
            }
            if (fromUser) {
                if (inset != lastHapticInset) {
                    lastHapticInset = inset
                    ExpressiveMotion.hapticSliderTick(slider)
                }
                saveAmbientInsetDp(inset)
            }
        }

        // ── Settings Tab 3: Keyboard shortcuts expressive chips + bottom sheet picker ──
        fun refreshShortcutChips() {
            shortcutChipGroup.removeAllViews()
            val keys = loadShortcutKeys()
            shortcutCountBadge.text = "${keys.size} / $MAX_SHORTCUTS"
            shortcutEmptyText.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
            shortcutChipGroup.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
            btnAddShortcut.isEnabled = keys.size < MAX_SHORTCUTS
            btnAddShortcut.alpha = if (keys.size < MAX_SHORTCUTS) 1f else 0.5f

            keys.forEach { keyCode ->
                val label = shortcutLabel(keyCode)
                val chip = Chip(this).apply {
                    text = label
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge_Emphasized)
                    setTextColor(onSecondaryContainer)
                    chipBackgroundColor = ColorStateList.valueOf(secondaryContainer)
                    chipStrokeWidth = 0f
                    shapeAppearanceModel = ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, dp(16).toFloat())
                        .build()
                    chipMinHeight = dp(38).toFloat()
                    isCheckable = false
                    isCloseIconVisible = true
                    closeIcon = AppCompatResources.getDrawable(this@GameActivity, R.drawable.ic_close)
                    closeIconTint = ColorStateList.valueOf(onSecondaryContainer)
                    closeIconSize = dp(16).toFloat()
                    closeIconContentDescription = getString(R.string.shortcut_remove, label)
                    setEnsureMinTouchTargetSize(false)
                    setOnCloseIconClickListener { v ->
                        ExpressiveMotion.hapticTap(v)
                        saveShortcutKeys(loadShortcutKeys() - keyCode)
                        refreshShortcutChips()
                    }
                }
                shortcutChipGroup.addView(chip)
            }
        }

        btnAddShortcut.setOnClickListener {
            showKeyboardPickerDialog { refreshShortcutChips() }
        }
        refreshShortcutChips()

        // ── Settings Tab 4: WebView Settings Sub-Page ──
        fun bindSwitchRow(
            card: MaterialCardView,
            toggle: MaterialSwitch,
            prefKey: String,
            defaultValue: Boolean,
            onChanged: (Boolean) -> Unit = {}
        ) {
            val initial = prefs.getBoolean(prefKey, defaultValue)
            toggle.isChecked = initial
            syncSwitchThumbIcon(toggle, initial)
            toggle.setOnCheckedChangeListener { view, checked ->
                syncSwitchThumbIcon(toggle, checked)
                ExpressiveMotion.hapticToggle(view, checked)
                prefs.edit().putBoolean(prefKey, checked).apply()
                onChanged(checked)
            }
            card.setOnClickListener {
                toggle.isChecked = !toggle.isChecked
            }
        }

        val webViewVersion = runCatching {
            WebViewCompat.getCurrentWebViewPackage(this)?.versionName
        }.getOrNull()
        if (!webViewVersion.isNullOrBlank()) {
            val shortVer = webViewVersion.split('.').take(2).joinToString(".")
            webViewVersionBadge.text = "v$shortVer"
            webViewVersionPill.visibility = View.VISIBLE
        }

        bindSwitchRow(cardWvHighRefresh, switchWvHighRefresh, PREF_WV_HIGH_REFRESH, defaultValue = true)
        bindSwitchRow(cardWvFullscreen, switchWvFullscreen, PREF_FULLSCREEN, defaultValue = false) { checked ->
            if (::aspectContainer.isInitialized) {
                aspectContainer.fullscreen = checked
            }
        }
        bindSwitchRow(cardWvKeepScreenOn, switchWvKeepScreenOn, PREF_WV_KEEP_SCREEN_ON, defaultValue = true)
        bindSwitchRow(cardWvHwAccel, switchWvHwAccel, PREF_WV_HW_ACCEL, defaultValue = true)
        bindSwitchRow(cardWvJsModding, switchWvJsModding, PREF_WV_JS_MODDING, defaultValue = FORCE_JS_MODDING)
        bindSwitchRow(cardWvDebugging, switchWvDebugging, PREF_WV_DEBUGGING, defaultValue = false) { checked ->
            WebView.setWebContentsDebuggingEnabled(checked)
        }

        ExpressiveMotion.attachExpressiveCardButton(
            btnWebViewBack,
            pressedCornerDp = 14f,
            pressedScale = 0.90f
        )
        ExpressiveMotion.attachExpressiveButton(
            btnWvClearCache,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true
        )
        btnWvClearCache.setOnClickListener { view ->
            runCatching {
                val tempWebView = WebView(this)
                tempWebView.clearCache(true)
                tempWebView.destroy()
            }
            ExpressiveMotion.hapticConfirm(view)
            Toast.makeText(this, R.string.webview_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        // ── Settings Tab 5: Launcher Updates via GitHub ──
        fun bindUpdateUi() {
            updateCheckingIndicator.visibility = if (isCheckingUpdate) View.VISIBLE else View.GONE
            btnCheckUpdates.isEnabled = !isCheckingUpdate
            btnCheckUpdates.alpha = if (isCheckingUpdate) 0.55f else 1f

            when (val result = latestUpdateResult) {
                is UpdateCheckResult.UpdateAvailable -> {
                    navUpdateDot.visibility = View.VISIBLE
                    updateStatusText.text = getString(R.string.update_available_status, result.info.latestVersion)
                    updateStatusText.setTextColor(primary)
                    btnDownloadUpdate.visibility = View.VISIBLE
                    btnDownloadUpdate.text = getString(R.string.update_download_version, result.info.latestVersion)
                    btnDownloadUpdate.setOnClickListener { openUpdateDownload(result.info.downloadUrl) }
                }
                is UpdateCheckResult.UpToDate -> {
                    navUpdateDot.visibility = View.GONE
                    updateStatusText.text = getString(R.string.update_up_to_date)
                    updateStatusText.setTextColor(onSurfaceVariant)
                    btnDownloadUpdate.visibility = View.GONE
                }
                is UpdateCheckResult.Error -> {
                    navUpdateDot.visibility = View.GONE
                    updateStatusText.text = getString(R.string.update_check_failed)
                    updateStatusText.setTextColor(onSurfaceVariant)
                    btnDownloadUpdate.visibility = View.GONE
                }
                null -> {
                    navUpdateDot.visibility = View.GONE
                    updateStatusText.text = if (isCheckingUpdate) {
                        getString(R.string.checking_updates)
                    } else {
                        getString(R.string.launcher_current_version, currentVersion)
                    }
                    updateStatusText.setTextColor(onSurfaceVariant)
                    btnDownloadUpdate.visibility = View.GONE
                }
            }
        }
        refreshUpdateUi = ::bindUpdateUi
        bindUpdateUi()

        btnCheckUpdates.setOnClickListener {
            checkForLauncherUpdates(showPopupIfAvailable = true, triggeredByUser = true)
        }

        // ── Material 3 Expressive Floating Bottom Navigation Bar & Sub-Page Routing ──
        fun applyTabState(tab: LauncherTab, animate: Boolean) {
            val previous = activeLauncherTab
            activeLauncherTab = tab

            val isHome = tab == LauncherTab.HOME
            val isSettings = tab == LauncherTab.SETTINGS
            val isWebViewSettings = tab == LauncherTab.WEBVIEW_SETTINGS

            navTabHome.setCardBackgroundColor(if (isHome) primaryContainer else Color.TRANSPARENT)
            ImageViewCompat.setImageTintList(
                navHomeIcon,
                ColorStateList.valueOf(if (isHome) onPrimaryContainer else onSurfaceVariant)
            )
            navHomeLabel.setTextColor(if (isHome) onPrimaryContainer else onSurfaceVariant)

            navTabSettings.setCardBackgroundColor(if (!isHome) primaryContainer else Color.TRANSPARENT)
            ImageViewCompat.setImageTintList(
                navSettingsIcon,
                ColorStateList.valueOf(if (!isHome) onPrimaryContainer else onSurfaceVariant)
            )
            navSettingsLabel.setTextColor(if (!isHome) onPrimaryContainer else onSurfaceVariant)

            // On phones hide the bottom bar inside nested WebView settings; on tablets/foldables keep the side rail anchored
            floatingNavContainer.visibility = if (isWebViewSettings && !isWideLayout) View.GONE else View.VISIBLE

            if (animate && previous != tab) {
                homeScroll.visibility = View.GONE
                settingsScroll.visibility = View.GONE
                webViewSettingsScroll.visibility = View.GONE
                when (tab) {
                    LauncherTab.HOME -> {
                        ExpressiveMotion.animateTabEnter(homeScroll, fromRight = false)
                        ExpressiveMotion.pulseNavTab(navTabHome)
                    }
                    LauncherTab.SETTINGS -> {
                        val fromRight = previous == LauncherTab.HOME
                        ExpressiveMotion.animateTabEnter(settingsScroll, fromRight = fromRight)
                        if (fromRight) ExpressiveMotion.pulseNavTab(navTabSettings)
                    }
                    LauncherTab.WEBVIEW_SETTINGS -> {
                        webViewSettingsScroll.scrollTo(0, 0)
                        ExpressiveMotion.animateTabEnter(webViewSettingsScroll, fromRight = true)
                    }
                }
            } else {
                homeScroll.visibility = if (isHome) View.VISIBLE else View.GONE
                settingsScroll.visibility = if (isSettings) View.VISIBLE else View.GONE
                webViewSettingsScroll.visibility = if (isWebViewSettings) View.VISIBLE else View.GONE
            }
        }

        cardWebViewSettings.setOnClickListener { view ->
            ExpressiveMotion.hapticTap(view)
            applyTabState(LauncherTab.WEBVIEW_SETTINGS, animate = true)
        }
        btnWebViewBack.setOnClickListener {
            applyTabState(LauncherTab.SETTINGS, animate = true)
        }

        navigateBackInLauncherAction = {
            when (activeLauncherTab) {
                LauncherTab.WEBVIEW_SETTINGS -> {
                    ExpressiveMotion.hapticTap(btnWebViewBack)
                    applyTabState(LauncherTab.SETTINGS, animate = true)
                    true
                }
                LauncherTab.SETTINGS -> {
                    ExpressiveMotion.hapticSegment(navTabHome)
                    applyTabState(LauncherTab.HOME, animate = true)
                    true
                }
                LauncherTab.HOME -> false
            }
        }

        navTabHome.setOnClickListener { view ->
            if (activeLauncherTab != LauncherTab.HOME) {
                ExpressiveMotion.hapticSegment(view)
                applyTabState(LauncherTab.HOME, animate = true)
            } else {
                ExpressiveMotion.hapticTap(view)
                homeScroll.smoothScrollTo(0, 0)
            }
        }
        navTabSettings.setOnClickListener { view ->
            if (activeLauncherTab != LauncherTab.SETTINGS) {
                ExpressiveMotion.hapticSegment(view)
                applyTabState(LauncherTab.SETTINGS, animate = true)
            } else {
                ExpressiveMotion.hapticTap(view)
                settingsScroll.smoothScrollTo(0, 0)
            }
        }

        applyTabState(activeLauncherTab, animate = false)

        if (animateEntrance) {
            val targets = when (activeLauncherTab) {
                LauncherTab.HOME -> listOf(headerRow, heroCard, actionButtonGroup, floatingNavContainer)
                LauncherTab.SETTINGS -> listOf(settingsContent, floatingNavContainer)
                LauncherTab.WEBVIEW_SETTINGS -> listOf(webViewSettingsContent)
            }
            ExpressiveMotion.animateEntrance(targets)
        }

        if (!hasCheckedUpdateOnLaunch) {
            hasCheckedUpdateOnLaunch = true
            checkForLauncherUpdates(showPopupIfAvailable = true, triggeredByUser = false)
        }
    }

    private fun currentLauncherVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "1.0" }

    private fun checkForLauncherUpdates(showPopupIfAvailable: Boolean, triggeredByUser: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        if (triggeredByUser) latestUpdateResult = null
        refreshUpdateUi?.invoke()

        val currentVersion = currentLauncherVersion()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LauncherUpdateChecker.checkForUpdate(currentVersion)
            }
            isCheckingUpdate = false
            latestUpdateResult = result
            refreshUpdateUi?.invoke()

            if (!isLauncherVisible || isFinishing || isDestroyed) return@launch
            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    if (showPopupIfAvailable) {
                        ExpressiveMotion.hapticConfirm(window.decorView)
                        showUpdateAvailableDialog(result.info)
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    if (triggeredByUser) {
                        ExpressiveMotion.hapticTap(window.decorView)
                        Toast.makeText(this@GameActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    Log.w(TAG, "Launcher update check failed: ${result.message}")
                    if (triggeredByUser) {
                        Toast.makeText(this@GameActivity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: LauncherUpdateInfo) {
        val currentVersion = currentLauncherVersion()
        val view = layoutInflater.inflate(R.layout.dialog_update_available, null)
        view.findViewById<MaterialTextView>(R.id.updateDialogVersionPill).text =
            "v$currentVersion → v${info.latestVersion}"
        view.findViewById<MaterialTextView>(R.id.updateDialogMessage).text =
            getString(R.string.update_dialog_message, info.latestVersion, currentVersion)

        val notesCard = view.findViewById<View>(R.id.updateDialogNotesCard)
        val notesText = view.findViewById<MaterialTextView>(R.id.updateDialogNotes)
        if (!info.releaseNotes.isNullOrBlank()) {
            notesText.text = info.releaseNotes
            notesCard.visibility = View.VISIBLE
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_download) { _, _ ->
                openUpdateDownload(info.downloadUrl)
            }
            .show()
    }

    private fun syncSwitchThumbIcon(toggle: MaterialSwitch, checked: Boolean) {
        toggle.thumbIconSize = dp(16)
        toggle.thumbIconDrawable = if (checked) {
            AppCompatResources.getDrawable(this, R.drawable.ic_switch_check)
        } else {
            null
        }
    }

    private fun applyAmbientWaves(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AMBIENT_WAVES, enabled).apply()
        if (::aspectContainer.isInitialized) {
            aspectContainer.ambientWavesEnabled = enabled
        }
        val active = enabled && loadSideMode() == AspectRatioFrameLayout.SideMode.AMBIENT
        if (::webView.isInitialized && !isLauncherVisible) {
            webView.evaluateJavascript("window.__gdAmbientWavesEnabled=$active;", null)
        }
    }

    private fun showDownloadArchiveSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.sheet_download_archive, null)
        dialog.setContentView(sheetView)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            maxWidth = dp(520)
        }

        val optionTelegram = sheetView.findViewById<MaterialCardView>(R.id.optionTelegram)
        val optionArchiveList = sheetView.findViewById<MaterialCardView>(R.id.optionArchiveList)
        val optionGithubReleases = sheetView.findViewById<MaterialCardView>(R.id.optionGithubReleases)
        ExpressiveMotion.attachSpringPress(optionTelegram, pressedScale = 0.97f, withHaptic = true)
        ExpressiveMotion.attachSpringPress(optionArchiveList, pressedScale = 0.97f, withHaptic = true)
        ExpressiveMotion.attachSpringPress(optionGithubReleases, pressedScale = 0.97f, withHaptic = true)

        optionTelegram.setOnClickListener {
            dialog.dismiss()
            openTelegramChannel()
        }
        optionArchiveList.setOnClickListener {
            dialog.dismiss()
            showArchiveListSheet()
        }
        optionGithubReleases.setOnClickListener {
            dialog.dismiss()
            openGithubReleases()
        }
        dialog.show()
    }

    private fun showArchiveListSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.sheet_archive_list, null)
        dialog.setContentView(sheetView)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            maxWidth = dp(520)
        }

        val loadingView = sheetView.findViewById<View>(R.id.archiveListLoading)
        val emptyStateView = sheetView.findViewById<View>(R.id.archiveListEmptyState)
        val emptyView = sheetView.findViewById<MaterialTextView>(R.id.archiveListEmpty)
        val retryButton = sheetView.findViewById<MaterialButton>(R.id.btnRetryArchiveList)
        val scrollView = sheetView.findViewById<View>(R.id.archiveListScroll)
        val container = sheetView.findViewById<LinearLayout>(R.id.archiveListContainer)

        val surfaceLowest = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.WHITE)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.GREEN)
        val primaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, primary)
        val onPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, onSurface)
        val tertiaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiaryContainer, primaryContainer)
        val onTertiaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnTertiaryContainer, onSurface)

        ExpressiveMotion.attachExpressiveButton(
            retryButton,
            pressedCornerDp = 14f,
            pressedScale = 0.96f,
            morphCorners = true
        )

        fun loadFromGitHub() {
            loadingView.visibility = View.VISIBLE
            emptyStateView.visibility = View.GONE
            scrollView.visibility = View.GONE

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    GameArchiveCatalog.loadCatalog()
                }
                if (!dialog.isShowing) return@launch
                loadingView.visibility = View.GONE

                val entries = when (result) {
                    is GameArchiveCatalogResult.Success -> result.entries
                    is GameArchiveCatalogResult.Error -> {
                        Log.w(TAG, "Failed to fetch archive catalog from GitHub: ${result.message}")
                        emptyView.setText(R.string.archive_list_load_failed)
                        emptyStateView.visibility = View.VISIBLE
                        return@launch
                    }
                }
                if (entries.isEmpty()) {
                    emptyView.setText(R.string.archive_list_empty)
                    emptyStateView.visibility = View.VISIBLE
                    return@launch
                }

                scrollView.visibility = View.VISIBLE
                container.removeAllViews()
                val count = entries.size
                entries.forEachIndexed { index, entry ->
                val shapeModel = when {
                    count == 1 -> ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, dp(24).toFloat())
                        .build()
                    index == 0 -> ShapeAppearanceModel.builder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, dp(24).toFloat())
                        .setTopRightCorner(CornerFamily.ROUNDED, dp(24).toFloat())
                        .setBottomLeftCorner(CornerFamily.ROUNDED, dp(6).toFloat())
                        .setBottomRightCorner(CornerFamily.ROUNDED, dp(6).toFloat())
                        .build()
                    index == count - 1 -> ShapeAppearanceModel.builder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, dp(6).toFloat())
                        .setTopRightCorner(CornerFamily.ROUNDED, dp(6).toFloat())
                        .setBottomLeftCorner(CornerFamily.ROUNDED, dp(24).toFloat())
                        .setBottomRightCorner(CornerFamily.ROUNDED, dp(24).toFloat())
                        .build()
                    else -> ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, dp(6).toFloat())
                        .build()
                }

                val card = MaterialCardView(this@GameActivity).apply {
                    setCardBackgroundColor(surfaceLowest)
                    cardElevation = 0f
                    strokeWidth = 0
                    preventCornerOverlap = false
                    shapeAppearanceModel = shapeModel
                    isClickable = true
                    isFocusable = true
                    ExpressiveMotion.attachSpringPress(this, pressedScale = 0.97f, withHaptic = true)
                    setOnClickListener {
                        dialog.dismiss()
                        confirmAndDownloadArchive(entry)
                    }
                }

                val row = LinearLayout(this@GameActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(16), dp(16), dp(16))
                }

                val textColumn = LinearLayout(this@GameActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                textColumn.addView(MaterialTextView(this@GameActivity).apply {
                    text = entry.name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium_Emphasized)
                    setTextColor(onSurface)
                })
                if (!entry.gpNext.isNullOrBlank()) {
                    val pill = MaterialCardView(this@GameActivity).apply {
                        setCardBackgroundColor(tertiaryContainer)
                        cardElevation = 0f
                        strokeWidth = 0
                        shapeAppearanceModel = ShapeAppearanceModel.builder()
                            .setAllCorners(CornerFamily.ROUNDED, dp(100).toFloat())
                            .build()
                        addView(MaterialTextView(this@GameActivity).apply {
                            text = entry.gpNext
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium_Emphasized)
                            setTextColor(onTertiaryContainer)
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                        })
                    }
                    textColumn.addView(
                        pill,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) }
                    )
                }

                row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(12)
                })

                val dlBadge = MaterialCardView(this@GameActivity).apply {
                    setCardBackgroundColor(primaryContainer)
                    cardElevation = 0f
                    strokeWidth = 0
                    shapeAppearanceModel = ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, dp(100).toFloat())
                        .build()
                    addView(
                        ImageView(this@GameActivity).apply {
                            setImageResource(R.drawable.ic_download)
                            imageTintList = ColorStateList.valueOf(onPrimaryContainer)
                            contentDescription = null
                        },
                        FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                    )
                }
                row.addView(dlBadge, LinearLayout.LayoutParams(dp(42), dp(42)))

                card.addView(row)
                container.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (index > 0) topMargin = dp(3) }
                )
            }
            }
        }

        retryButton.setOnClickListener { loadFromGitHub() }
        dialog.show()
        loadFromGitHub()
    }

    private fun confirmAndDownloadArchive(entry: GameArchiveEntry) {
        val hasGame = GameArchiveInstaller(filesDir).installedSiteDirectory() != null
        if (hasGame) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_warning_title)
                .setMessage(R.string.update_warning_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_update) { _, _ ->
                    downloadAndInstallArchive(entry)
                }
                .show()
        } else {
            downloadAndInstallArchive(entry)
        }
    }

    private fun downloadAndInstallArchive(entry: GameArchiveEntry) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_install_progress, null)
        val titleView = dialogView.findViewById<MaterialTextView>(R.id.installTitle)
        val progress = dialogView.findViewById<LinearProgressIndicator>(R.id.installProgress)
        val progressText = dialogView.findViewById<MaterialTextView>(R.id.installStatusText)
        titleView.setText(R.string.downloading_game)
        progressText.text = entry.name

        var job: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                job?.cancel()
            }
            .create()
        dialog.show()

        job = lifecycleScope.launch {
            val tempZip = File(cacheDir, "gardendless-remote-archive.zip")
            try {
                // Phase 1: Download archive
                withContext(Dispatchers.IO) {
                    tempZip.delete()
                    GameArchiveCatalog.downloadArchiveToFile(entry.url, tempZip) { downloaded, total ->
                        runOnUiThread {
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                progress.isIndeterminate = false
                                progress.setProgressCompat(pct, true)
                                progressText.text = getString(
                                    R.string.downloading_progress,
                                    pct,
                                    formatMegabytes(downloaded),
                                    formatMegabytes(total)
                                )
                            } else {
                                progress.isIndeterminate = true
                                progressText.text = getString(
                                    R.string.downloading_progress_unknown,
                                    formatMegabytes(downloaded)
                                )
                            }
                        }
                    }
                }

                // Phase 2: Extract immediately
                titleView.setText(R.string.installing_game)
                progress.isIndeterminate = false
                progress.setProgressCompat(0, false)
                progressText.text = getString(R.string.installing_progress, 0)
                val archiveSize = tempZip.length()
                withContext(Dispatchers.IO) {
                    tempZip.inputStream().use { input ->
                        GameArchiveInstaller(filesDir).install(input, archiveSize) { bytesRead, totalBytes ->
                            val pct = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                            runOnUiThread {
                                progress.setProgressCompat(pct, true)
                                progressText.text = getString(R.string.installing_progress, pct)
                            }
                        }
                    }
                }

                progress.setProgressCompat(100, true)
                ExpressiveMotion.hapticConfirm(window.decorView)
                dialog.dismiss()
                showGameMenu(animateEntrance = true)
                Toast.makeText(this@GameActivity, R.string.game_zip_installed, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download or install remote game archive", e)
                dialog.dismiss()
                showGameMenu(animateEntrance = false)
                MaterialAlertDialogBuilder(this@GameActivity)
                    .setTitle(R.string.game_zip_install_failed)
                    .setMessage(getString(R.string.game_zip_install_error, e.localizedMessage ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                tempZip.delete()
            }
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    private fun openGithubReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, GITHUB_RELEASES_URL.toUri()))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open GitHub Releases", e)
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openUpdateDownload(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open release URL: $url", e)
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 可视化键盘（Material 3 Expressive Modal Bottom Sheet）：
     * 直接点按虚拟键盘上的按键即可加入快捷按键；已添加的按键变灰。
     */
    private fun showKeyboardPickerDialog(onChanged: () -> Unit) {
        val keys = loadShortcutKeys().toMutableList()
        if (keys.size >= MAX_SHORTCUTS) {
            Toast.makeText(this, getString(R.string.shortcut_limit, MAX_SHORTCUTS), Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.sheet_keyboard_picker, null)
        dialog.setContentView(sheetView)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            maxWidth = dp(520)
        }

        val countBadge = sheetView.findViewById<MaterialTextView>(R.id.pickerCountBadge)
        val rowsContainer = sheetView.findViewById<LinearLayout>(R.id.keyboardRowsContainer)
        val doneButton = sheetView.findViewById<MaterialButton>(R.id.btnPickerDone)
        ExpressiveMotion.attachExpressiveButton(
            doneButton,
            pressedCornerDp = 14f,
            pressedScale = 0.97f,
            morphCorners = true
        )

        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        val keyBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.DKGRAY)
        val keyAddedBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
        val outlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        val rippleColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorControlHighlight, onSurface)

        val keyViews = ArrayList<Pair<TextView, Int>>()

        fun createKeyDrawable(added: Boolean): RippleDrawable {
            val fill = GradientDrawable().apply {
                setColor(if (added) keyAddedBg else keyBg)
                cornerRadius = dp(12).toFloat()
                if (added) {
                    setStroke(dp(1), outlineVariant)
                }
            }
            return RippleDrawable(ColorStateList.valueOf(rippleColor), fill, null)
        }

        fun refreshKeyStates() {
            countBadge.text = "${keys.size} / $MAX_SHORTCUTS"
            keyViews.forEach { (view, code) ->
                val added = keys.contains(code)
                view.isEnabled = !added
                view.alpha = if (added) 0.42f else 1f
                view.setTextColor(if (added) onSurfaceVariant else onSurface)
                view.background = createKeyDrawable(added)
            }
        }

        fun addKey(code: Int, sourceView: View) {
            when {
                keys.contains(code) ->
                    Toast.makeText(this, R.string.shortcut_duplicate, Toast.LENGTH_SHORT).show()
                keys.size >= MAX_SHORTCUTS ->
                    Toast.makeText(this, getString(R.string.shortcut_limit, MAX_SHORTCUTS), Toast.LENGTH_SHORT).show()
                else -> {
                    ExpressiveMotion.hapticConfirm(sourceView)
                    keys.add(code)
                    saveShortcutKeys(keys)
                    refreshKeyStates()
                    onChanged()
                }
            }
        }

        fun makeKey(label: String, code: Int): TextView {
            val key = MaterialTextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium_Emphasized)
                textSize = 11f
                maxLines = 1
                setTextColor(onSurface)
                setPadding(dp(2), 0, dp(2), 0)
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.shortcut_send, label)
                background = createKeyDrawable(false)
                ExpressiveMotion.attachSpringPress(this, pressedScale = 0.90f, withHaptic = true)
                setOnClickListener { addKey(code, it) }
            }
            keyViews.add(key to code)
            return key
        }

        fun addRow(entries: List<Pair<String, Int>>, weights: List<Float>? = null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            entries.forEachIndexed { index, (label, code) ->
                val weight = weights?.getOrNull(index) ?: 1f
                row.addView(
                    makeKey(label, code),
                    LinearLayout.LayoutParams(0, dp(44), weight).apply {
                        if (index > 0) marginStart = dp(4)
                    }
                )
            }
            rowsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (rowsContainer.childCount > 0) topMargin = dp(6) }
            )
        }

        fun letters(chars: String) = chars.map { ch ->
            ch.toString() to KeyEvent.keyCodeFromString("KEYCODE_$ch")
        }

        addRow((1..6).map { "F$it" to KeyEvent.KEYCODE_F1 + it - 1 })
        addRow((7..12).map { "F$it" to KeyEvent.KEYCODE_F1 + it - 1 })
        addRow("1234567890".map { it.toString() to KeyEvent.keyCodeFromString("KEYCODE_$it") })
        addRow(letters("QWERTYUIOP"))
        addRow(letters("ASDFGHJKL"))
        addRow(letters("ZXCVBNM"))
        addRow(
            listOf(
                "ESC" to KeyEvent.KEYCODE_ESCAPE,
                "TAB" to KeyEvent.KEYCODE_TAB,
                "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
                "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
                "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
                "SPACE" to KeyEvent.KEYCODE_SPACE,
                "ENTER" to KeyEvent.KEYCODE_ENTER,
                "DEL" to KeyEvent.KEYCODE_DEL
            ),
            listOf(1f, 1f, 1.2f, 1f, 1f, 2.2f, 1.4f, 1f)
        )
        addRow(
            listOf(
                "←" to KeyEvent.KEYCODE_DPAD_LEFT,
                "↑" to KeyEvent.KEYCODE_DPAD_UP,
                "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
                "→" to KeyEvent.KEYCODE_DPAD_RIGHT
            )
        )
        refreshKeyStates()

        doneButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun createGameControls(): View {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val surfaceHigh = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            Color.DKGRAY
        )
        val onSurface = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.WHITE
        )
        val primaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            surfaceHigh
        )
        val onPrimaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            onSurface
        )
        val outlineVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY
        )
        val rippleColor = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorControlHighlight,
            onSurface
        )

        // Material 3 Expressive Floating Vertical Pill Dock for Home + GP-Next
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(surfaceHigh, 232))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(outlineVariant, 120))
            }
        }

        fun addDockButton(
            iconRes: Int,
            descriptionRes: Int,
            accentFill: Boolean,
            onClick: () -> Unit
        ) {
            val fill = if (accentFill) primaryContainer else Color.TRANSPARENT
            val tint = if (accentFill) onPrimaryContainer else onSurface
            val button = FrameLayout(this).apply {
                contentDescription = getString(descriptionRes)
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(rippleColor),
                    GradientDrawable().apply {
                        setColor(fill)
                        shape = GradientDrawable.OVAL
                    },
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        shape = GradientDrawable.OVAL
                    }
                )
                ExpressiveMotion.attachSpringPress(this, pressedScale = 0.88f)
                setOnClickListener { onClick() }
            }
            button.addView(
                ImageView(this).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(tint)
                    contentDescription = null
                },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
            dock.addView(
                button,
                LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    if (dock.childCount > 0) topMargin = dp(4)
                }
            )
        }

        addDockButton(R.drawable.ic_home, R.string.home_button_description, accentFill = false, ::returnToLauncher)
        addDockButton(R.drawable.ic_tune, R.string.gp_next_button_description, accentFill = true, ::toggleGpNextMenu)
        controls.addView(dock)

        val shortcutKeys = loadShortcutKeys()
        if (shortcutKeys.isNotEmpty()) {
            controls.addView(
                createShortcutButtons(shortcutKeys),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    shortcutColumnHeight(shortcutKeys.size)
                ).apply { topMargin = dp(8) }
            )
        }
        return controls
    }

    /** 快捷按键列高度：最多显示 [SHORTCUT_VISIBLE_ROWS] 行，其余滚动查看 */
    private fun shortcutColumnHeight(count: Int): Int {
        val rows = count.coerceIn(1, SHORTCUT_VISIBLE_ROWS)
        return dp(SHORTCUT_BUTTON_HEIGHT_DP * rows + SHORTCUT_BUTTON_GAP_DP * (rows - 1))
    }

    /**
     * GP-Next 按钮下方的快捷按键列。
     *
     * 每个按钮把对应按键的 KeyEvent 直接派发给 WebView（与 F9 打开 GP-Next 同一路径），
     * 游戏内的键盘监听因此能收到 keydown/keyup。数量多时可纵向滚动。
     */
    private fun createShortcutButtons(keys: List<Int>): View {
        val surfaceContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            Color.DKGRAY
        )
        val onSurface = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.WHITE
        )
        val outlineVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY
        )
        val rippleColor = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorControlHighlight,
            onSurface
        )
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        keys.forEachIndexed { index, keyCode ->
            val label = shortcutLabel(keyCode)
            val button = MaterialTextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium_Emphasized)
                setTextColor(onSurface)
                maxLines = 1
                minWidth = dp(52)
                setPadding(dp(12), 0, dp(12), 0)
                contentDescription = getString(R.string.shortcut_send, label)
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(rippleColor),
                    GradientDrawable().apply {
                        setColor(ColorUtils.setAlphaComponent(surfaceContainer, 232))
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), ColorUtils.setAlphaComponent(outlineVariant, 120))
                    },
                    null
                )
                ExpressiveMotion.attachSpringPress(this, pressedScale = 0.90f)
                setOnClickListener { sendShortcutKey(keyCode) }
            }
            column.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(SHORTCUT_BUTTON_HEIGHT_DP)
                ).apply { if (index > 0) topMargin = dp(SHORTCUT_BUTTON_GAP_DP) }
            )
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /** JS 侧需要的按键描述：DOM key、DOM code 和旧式 keyCode */
    private data class JsShortcutKey(val key: String, val code: String, val legacyCode: Int)

    /**
     * 把 Android keyCode 映射成浏览器认识的描述。
     *
     * 原生 [KeyEvent] 没有 unicode 字符，Chromium 只能给出 key="Unidentified"，
     * 因此字母类按键在网页里匹配不到；这里改为主动构造带 key/code 的 KeyboardEvent。
     */
    private fun jsShortcutKey(keyCode: Int): JsShortcutKey? = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
            val letter = ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            JsShortcutKey(letter.lowercase(), "Key$letter", 65 + (keyCode - KeyEvent.KEYCODE_A))
        }
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
            val digit = keyCode - KeyEvent.KEYCODE_0
            JsShortcutKey("$digit", "Digit$digit", 48 + digit)
        }
        keyCode in KeyEvent.KEYCODE_F1..(KeyEvent.KEYCODE_F1 + 11) -> {
            val number = keyCode - KeyEvent.KEYCODE_F1 + 1
            JsShortcutKey("F$number", "F$number", 111 + number)
        }
        else -> when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> JsShortcutKey("Escape", "Escape", 27)
            KeyEvent.KEYCODE_TAB -> JsShortcutKey("Tab", "Tab", 9)
            KeyEvent.KEYCODE_SPACE -> JsShortcutKey(" ", "Space", 32)
            KeyEvent.KEYCODE_ENTER -> JsShortcutKey("Enter", "Enter", 13)
            KeyEvent.KEYCODE_DEL -> JsShortcutKey("Backspace", "Backspace", 8)
            KeyEvent.KEYCODE_SHIFT_LEFT -> JsShortcutKey("Shift", "ShiftLeft", 16)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> JsShortcutKey("Shift", "ShiftRight", 16)
            KeyEvent.KEYCODE_CTRL_LEFT -> JsShortcutKey("Control", "ControlLeft", 17)
            KeyEvent.KEYCODE_CTRL_RIGHT -> JsShortcutKey("Control", "ControlRight", 17)
            KeyEvent.KEYCODE_ALT_LEFT -> JsShortcutKey("Alt", "AltLeft", 18)
            KeyEvent.KEYCODE_ALT_RIGHT -> JsShortcutKey("Alt", "AltRight", 18)
            KeyEvent.KEYCODE_META_LEFT -> JsShortcutKey("Meta", "MetaLeft", 91)
            KeyEvent.KEYCODE_META_RIGHT -> JsShortcutKey("Meta", "MetaRight", 92)
            KeyEvent.KEYCODE_DPAD_LEFT -> JsShortcutKey("ArrowLeft", "ArrowLeft", 37)
            KeyEvent.KEYCODE_DPAD_UP -> JsShortcutKey("ArrowUp", "ArrowUp", 38)
            KeyEvent.KEYCODE_DPAD_RIGHT -> JsShortcutKey("ArrowRight", "ArrowRight", 39)
            KeyEvent.KEYCODE_DPAD_DOWN -> JsShortcutKey("ArrowDown", "ArrowDown", 40)
            else -> null
        }
    }

    /** 把按键当作网页键盘事件送入游戏：Cocos 和 gp-next 都能读到 key/code */
    private fun sendShortcutKey(keyCode: Int) {
        if (!::webView.isInitialized) return
        val key = jsShortcutKey(keyCode)
        if (key == null) {
            Log.w(TAG, "Unsupported shortcut key: ${shortcutLabel(keyCode)}")
            return
        }
        webView.requestFocus()
        webView.evaluateJavascript(buildShortcutScript(key), null)
    }

    private fun buildShortcutScript(key: JsShortcutKey): String = """
        (function(){
          var keyName = '${key.key}';
          var codeName = '${key.code}';
          var legacyCode = ${key.legacyCode};
          var target = document.getElementById('GameCanvas') ||
                       document.querySelector('canvas') ||
                       document.body || document.documentElement;
          if (!target) { return; }
          function fire(type) {
            var event;
            try {
              event = new KeyboardEvent(type, {
                key: keyName,
                code: codeName,
                bubbles: true,
                cancelable: true,
                composed: true,
                view: window
              });
            } catch (e) {
              event = document.createEvent('Event');
              event.initEvent(type, true, true);
            }
            try { Object.defineProperty(event, 'key', { get: function () { return keyName; } }); } catch (e) {}
            try { Object.defineProperty(event, 'code', { get: function () { return codeName; } }); } catch (e) {}
            try { Object.defineProperty(event, 'keyCode', { get: function () { return legacyCode; } }); } catch (e) {}
            try { Object.defineProperty(event, 'which', { get: function () { return legacyCode; } }); } catch (e) {}
            try { Object.defineProperty(event, 'charCode', { get: function () { return 0; } }); } catch (e) {}
            target.dispatchEvent(event);
          }
          fire('keydown');
          fire('keyup');
        })();
    """.trimIndent()

    private fun loadShortcutKeys(): List<Int> {
        val stored = prefs.getString(PREF_SHORTCUT_KEYS, null) ?: return emptyList()
        return stored.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it != KeyEvent.KEYCODE_UNKNOWN }
            .distinct()
            .take(MAX_SHORTCUTS)
    }

    private fun saveShortcutKeys(keys: List<Int>) {
        prefs.edit()
            .putString(PREF_SHORTCUT_KEYS, keys.take(MAX_SHORTCUTS).joinToString(","))
            .apply()
    }

    /** 按钮上显示的按键名，例如 A、1、SPACE、F9 */
    private fun shortcutLabel(keyCode: Int): String {
        val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        return when (name) {
            "FORWARD_DEL", "DEL" -> "DEL"
            "PAGE_UP" -> "PGUP"
            "PAGE_DOWN" -> "PGDN"
            "ESCAPE" -> "ESC"
            "CAPS_LOCK" -> "CAPS"
            "CTRL_LEFT", "CTRL_RIGHT" -> "CTRL"
            "SHIFT_LEFT", "SHIFT_RIGHT" -> "SHIFT"
            "ALT_LEFT", "ALT_RIGHT" -> "ALT"
            "META_LEFT", "META_RIGHT" -> "META"
            "DPAD_UP" -> "UP"
            "DPAD_DOWN" -> "DOWN"
            "DPAD_LEFT" -> "LEFT"
            "DPAD_RIGHT" -> "RIGHT"
            "MOVE_HOME" -> "HOME"
            "MOVE_END" -> "END"
            "INSERT" -> "INS"
            else -> name
        }
    }

    /** 读取左右黑边的装饰方式；未设置时迁移旧版布尔开关，默认使用环境光（Ambient） */
    private fun loadSideMode(): AspectRatioFrameLayout.SideMode {
        when (prefs.getString(PREF_SIDE_MODE, null)) {
            "sprite" -> return AspectRatioFrameLayout.SideMode.SPRITE
            "ambient" -> return AspectRatioFrameLayout.SideMode.AMBIENT
            "off" -> return AspectRatioFrameLayout.SideMode.OFF
            // 旧版本的 "wave" 已移除，回落到环境光
            "wave" -> return AspectRatioFrameLayout.SideMode.AMBIENT
        }
        if (prefs.getBoolean(PREF_SIDE_ART_LEGACY, false)) {
            saveSideMode(AspectRatioFrameLayout.SideMode.SPRITE)
            return AspectRatioFrameLayout.SideMode.SPRITE
        }
        return AspectRatioFrameLayout.SideMode.AMBIENT
    }

    private fun saveSideMode(mode: AspectRatioFrameLayout.SideMode) {
        val value = when (mode) {
            AspectRatioFrameLayout.SideMode.OFF -> "off"
            AspectRatioFrameLayout.SideMode.SPRITE -> "sprite"
            AspectRatioFrameLayout.SideMode.AMBIENT -> "ambient"
        }
        prefs.edit()
            .putString(PREF_SIDE_MODE, value)
            .remove(PREF_SIDE_ART_LEGACY)
            .apply()
    }

    /** 读取 Ambient 采样的边缘内缩距离（dp） */
    private fun loadAmbientInsetDp(): Int =
        prefs.getInt(
            PREF_AMBIENT_INSET,
            DEFAULT_AMBIENT_INSET_DP
        ).coerceIn(0, MAX_AMBIENT_INSET_DP)

    private fun saveAmbientInsetDp(value: Int) {
        prefs.edit()
            .putInt(PREF_AMBIENT_INSET, value.coerceIn(0, MAX_AMBIENT_INSET_DP))
            .apply()
    }

    /** 立即应用黑边装饰（游戏运行中也能切换） */
    private fun applySideMode(mode: AspectRatioFrameLayout.SideMode) {
        saveSideMode(mode)
        if (!::aspectContainer.isInitialized) return
        if (mode == AspectRatioFrameLayout.SideMode.SPRITE && aspectContainer.sideArt == null) {
            aspectContainer.sideArt = loadSideArtBitmap()
        }
        if (mode == AspectRatioFrameLayout.SideMode.AMBIENT) {
            aspectContainer.ambientEdgeInsetDp = loadAmbientInsetDp().toFloat()
            aspectContainer.ambientWavesEnabled = prefs.getBoolean(PREF_AMBIENT_WAVES, false)
        }
        aspectContainer.sideMode = mode
        if (::webView.isInitialized && !isLauncherVisible) {
            val active = mode == AspectRatioFrameLayout.SideMode.AMBIENT &&
                prefs.getBoolean(PREF_AMBIENT_WAVES, false)
            webView.evaluateJavascript("window.__gdAmbientWavesEnabled=$active;", null)
        }
    }

    /** 载入左右黑边的草坪贴图；资源缺失时退回纯黑 */
    private fun loadSideArtBitmap(): Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.panel)
    } catch (e: Exception) {
        Log.w(TAG, "无法读取黑边贴图 panel.png", e)
        null
    }

    private fun returnToLauncher() {
        isLauncherVisible = true
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        if (::aspectContainer.isInitialized) aspectContainer.fullscreen = false
        if (::webView.isInitialized) {
            val oldWebView = webView
            (oldWebView.parent as? ViewGroup)?.removeView(oldWebView)
            oldWebView.stopLoading()
            oldWebView.destroy()
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showGameMenu()
    }

    private fun confirmGameUpdate() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_warning_title)
            .setMessage(R.string.update_warning_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_update) { _, _ -> selectGameArchive() }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun selectGameArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        }
        try {
            startActivityForResult(intent, GAME_ARCHIVE_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open the ZIP file picker", e)
            Toast.makeText(this, R.string.zip_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun importGameArchive(uri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_install_progress, null)
        val progress = dialogView.findViewById<LinearProgressIndicator>(R.id.installProgress)
        val progressText = dialogView.findViewById<MaterialTextView>(R.id.installStatusText)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            try {
                val archiveSize = withContext(Dispatchers.IO) { getArchiveSize(uri) }
                progress.isIndeterminate = archiveSize <= 0
                if (archiveSize > 0) {
                    progressText.text = getString(R.string.installing_progress, 0)
                }
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("The selected ZIP could not be opened")
                    input.use {
                        GameArchiveInstaller(filesDir).install(it, archiveSize) { bytesRead, totalBytes ->
                            val percentage = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                            runOnUiThread {
                                progress.setProgressCompat(percentage, true)
                                progressText.text = getString(R.string.installing_progress, percentage)
                            }
                        }
                    }
                }
                progress.isIndeterminate = false
                progress.setProgressCompat(100, true)
                ExpressiveMotion.hapticConfirm(window.decorView)
                dialog.dismiss()
                showGameMenu(animateEntrance = true)
                Toast.makeText(this@GameActivity, R.string.game_zip_installed, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install game ZIP", e)
                dialog.dismiss()
                showGameMenu(animateEntrance = false)
                MaterialAlertDialogBuilder(this@GameActivity)
                    .setTitle(R.string.game_zip_install_failed)
                    .setMessage(getString(R.string.game_zip_install_error, e.localizedMessage ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun getArchiveSize(uri: Uri): Long {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null) ?: return -1
        cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) return -1
            return it.getLong(0).takeIf { size -> size > 0 } ?: -1
        }
    }

    private fun openTelegramChannel() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL)))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open the Telegram channel", e)
            Toast.makeText(this, R.string.telegram_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleGpNextMenu() {
        webView.evaluateJavascript(
            "(function(){var api=window.gpNext;try{if(api&&typeof api.toggle==='function'){api.toggle();return true;}}catch(e){console.warn('GP-Next toggle failed',e);}return false;})()"
        ) { result ->
            if (result != "true") {
                Log.i(TAG, "GP-Next toggle API unavailable; sending F9 to the game")
                sendShortcutKey(KeyEvent.KEYCODE_F9)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // WebView 不会随 Activity 自动暂停；暂停后 Chromium 会把 localStorage 落盘，
        // 避免进程被系统回收时丢失最近的设置写入。
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        // 页面若仍处于全屏，通知 WebView 退出，避免其内部状态残留
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        cancelExportTransferNative()
        pendingExportFile?.delete()
        super.onDestroy()
    }

    /** 接收文件选择与导出保存两处 startActivityForResult 的结果 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            GAME_ARCHIVE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) data?.data?.let(::importGameArchive)
            }
            FILE_CHOOSER_RESULT_CODE -> {
                val result = if (data == null || resultCode != RESULT_OK) null else arrayOf(data.data!!)
                filePathCallback?.onReceiveValue(result as Array<Uri>?)
                filePathCallback = null
            }
            EXPORT_SAVE_RESULT_CODE -> {
                val bytes = pendingExport
                pendingExport = null
                val file = pendingExportFile
                pendingExportFile = null
                // 用户取消保存时 data 为 null，直接丢弃暂存内容
                val uri = data?.data
                if (uri == null) {
                    file?.delete()
                } else if (bytes != null) {
                    saveExportTo(uri, bytes)
                } else if (file != null) {
                    saveExportFileTo(uri, file)
                }
            }
        }
    }

    private fun beginSaveExport(
        dataUri: String,
        requestedName: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (!dataUri.startsWith("data:", ignoreCase = true)) return

        val bytes = try {
            DataUriDecoder.decode(dataUri)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to decode exported save data", e)
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
            return
        }
        val type = mimeType?.takeIf { it.isNotBlank() }
            ?: mimeTypeFromDataUri(dataUri)
            ?: "application/octet-stream"
        beginSaveExport(bytes, requestedName, contentDisposition, type)
    }

    private fun beginSaveExport(
        bytes: ByteArray,
        requestedName: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        pendingExportName = null
        pendingExport = bytes
        pendingExportFile?.delete()
        pendingExportFile = null

        val fallbackType = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val name = exportFileName(requestedName, contentDisposition, fallbackType)
        launchExportSaveDialog(name, mimeFromExtension(name) ?: fallbackType)
    }

    private fun beginSaveExport(file: File, requestedName: String?, mimeType: String?) {
        pendingExportName = null
        pendingExport = null
        pendingExportFile?.delete()
        pendingExportFile = file

        val fallbackType = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val name = exportFileName(requestedName, null, fallbackType)
        launchExportSaveDialog(name, mimeFromExtension(name) ?: fallbackType)
    }

    private fun exportFileName(
        requestedName: String?,
        contentDisposition: String?,
        mimeType: String
    ): String {
        val proposedName = requestedName?.takeIf { it.isNotBlank() }
            ?: suggestFileName(contentDisposition, mimeType)
        val name = proposedName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { suggestFileName(contentDisposition, mimeType) }
        return name
    }

    private fun launchExportSaveDialog(name: String, type: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            this.type = type
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, EXPORT_SAVE_RESULT_CODE)
        } catch (e: Exception) {
            pendingExport = null
            pendingExportFile?.delete()
            pendingExportFile = null
            Log.e(TAG, "Unable to open the save-file picker", e)
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun mimeTypeFromDataUri(dataUri: String): String? =
        dataUri.substringAfter("data:").substringBefore(',').substringBefore(';')
            .trim()
            .takeIf { it.contains('/') }

    private fun saveExportTo(uri: Uri, bytes: ByteArray) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("openOutputStream returned null")
                }
                Toast.makeText(
                    this@GameActivity,
                    R.string.export_done,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save exported game data", e)
                Toast.makeText(this@GameActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveExportFileTo(uri: Uri, file: File) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IOException("openOutputStream returned null")
                    output.use { destination ->
                        file.inputStream().use { source -> source.copyTo(destination) }
                    }
                }
                Toast.makeText(
                    this@GameActivity,
                    R.string.export_done,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save exported game data", e)
                Toast.makeText(this@GameActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            } finally {
                file.delete()
            }
        }
    }

    private fun beginExportTransferNative(fileName: String, mimeType: String): Boolean =
        synchronized(exportTransferLock) {
            if (activeExportTransfer != null) return@synchronized false
            try {
                val file = File.createTempFile("gardendless-export-", ".tmp", cacheDir)
                activeExportTransfer = ExportTransfer(
                    file = file,
                    fileName = fileName,
                    mimeType = mimeType,
                    output = FileOutputStream(file)
                )
                true
            } catch (e: IOException) {
                Log.e(TAG, "Unable to start streamed export", e)
                runOnUiThread {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                }
                false
            }
        }

    private fun appendExportChunkNative(base64: String): Boolean =
        synchronized(exportTransferLock) {
            val transfer = activeExportTransfer ?: return@synchronized false
            try {
                transfer.output.write(Base64.decode(base64, Base64.DEFAULT))
                true
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid base64 data in streamed export", e)
                discardActiveExportTransferLocked()
                false
            } catch (e: IOException) {
                Log.e(TAG, "Unable to write streamed export data", e)
                discardActiveExportTransferLocked()
                false
            }
        }

    private fun finishExportTransferNative(mimeType: String): Boolean {
        val transfer = synchronized(exportTransferLock) {
            val active = activeExportTransfer ?: return false
            try {
                active.output.close()
            } catch (e: IOException) {
                Log.e(TAG, "Unable to finish streamed export", e)
                discardActiveExportTransferLocked()
                return false
            }
            activeExportTransfer = null
            active.copy(mimeType = mimeType.takeIf { it.isNotBlank() } ?: active.mimeType)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                transfer.file.delete()
            } else {
                beginSaveExport(transfer.file, transfer.fileName, transfer.mimeType)
            }
        }
        return true
    }

    private fun cancelExportTransferNative() {
        synchronized(exportTransferLock) {
            discardActiveExportTransferLocked()
        }
    }

    private fun discardActiveExportTransferLocked() {
        val transfer = activeExportTransfer ?: return
        activeExportTransfer = null
        try {
            transfer.output.close()
        } catch (e: IOException) {
            Log.w(TAG, "Unable to close incomplete export", e)
        }
        if (!transfer.file.delete() && transfer.file.exists()) {
            Log.w(TAG, "Unable to delete incomplete export file")
        }
    }

    /**
     * 保存对话框默认名的兜底：游戏用 `<a download="名字" href="data:...">` 触发导出时，
     * 文件名会被 assets/gdnext-shim.js 截获并经桥转发过来（setExportName），
     * 取不到时才退回 contentDisposition 的建议名，再取不到才按类型和时间生成。
     */
    private fun suggestFileName(contentDisposition: String?, mimeType: String): String {
        contentDisposition
            ?.let { Regex("""filename\*?=(?:UTF-8''|utf-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val time = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "gardendless_$time.${mimeToExtension(mimeType)}"
    }

    private fun mimeToExtension(mimeType: String): String = when (val type = mimeType.substringBefore(';').trim()) {
        "application/json" -> "json"
        "text/plain" -> "txt"
        "application/octet-stream" -> "bin"
        else -> type.substringAfter('/').takeIf { it.all(Char::isLetterOrDigit) } ?: "bin"
    }

    private fun mimeFromExtension(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != fileName } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    /** 沉浸式全屏：隐藏状态栏与导航栏、允许刘海区域，并保持屏幕常亮 */
    private fun setupFullScreen() {
        // 兜底隐藏 ActionBar
        supportActionBar?.hide()

        // 窗口底色固定为黑：主题的 windowBackground 是浅色（#F8FAF0），
        // 游戏画面与黑边衔接处若露出 1px，会呈现为屏幕两侧的细白线。
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) 及以上
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // 旧版本兼容写法
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        // 保持屏幕常亮（可由 WebView 设置控制）
        if (prefs.getBoolean(PREF_WV_KEEP_SCREEN_ON, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── gp-next 数据目录 I/O ──
    // 以下方法的调用方是 assets/gdnext-shim.js：它把游戏发出的 plugin:fs|* 命令路由到这里。
    // 传入的路径为相对 AppData 的路径，例如 gp-next\packs\Foo\pack.json。

    /**
     * 把 gp-next 相对路径解析为 filesDir 下的真实文件。
     * 越界或非法输入一律收敛到数据根目录，避免逃逸到 filesDir 的其他位置。
     */
    private fun gpNextFile(rawPath: String): File = gpNextPathResolver.resolve(rawPath)

    /**
     * 返回 [{name, isFile, isDirectory, isSymlink}] 形式的 JSON；目录不存在时返回空数组。
     */
    private fun gpNextReadDir(rawPath: String): String? {
        val directory = gpNextFile(rawPath)
        if (!directory.isDirectory) return null
        val children = directory.listFiles() ?: return null
        val entries = JSONArray()
        children.forEach { file ->
            entries.put(JSONObject().apply {
                put("name", file.name)
                put("isFile", file.isFile)
                put("isDirectory", file.isDirectory)
                // 内部存储不涉及符号链接，固定为 false；需要真实判断时见 gpNextStatJson
                put("isSymlink", false)
            })
        }
        return entries.toString()
    }

    /** mkdirs() 在目录已存在时返回 false，因此以 isDirectory 判断最终结果 */
    private fun gpNextMkdir(rawPath: String): Boolean {
        val dir = gpNextFile(rawPath)
        dir.mkdirs()
        return dir.isDirectory
    }

    /** 写入文本，父目录不存在时自动创建 */
    private fun gpNextWriteText(rawPath: String, text: String): Boolean = runCatching {
        val file = gpNextFile(rawPath)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }.isSuccess

    private fun gpNextRemove(rawPath: String): Boolean {
        val file = gpNextFile(rawPath)
        // 仅用于清理 __gpn_edits，禁止删除数据根
        if (file.canonicalFile == gpNextDir.canonicalFile) return false
        return file.deleteRecursively()
    }

    /**
     * 生成 stat / lstat 的结果，字段与 @tauri-apps/plugin-fs 的 FileInfo 一致。
     * dist-js 会逐字段读取，其中 mtime/atime 必须是毫秒时间戳或 null，且字段不可缺失。
     * 使用 Os.lstat 而非 File，以便识别符号链接——新版包快照会拒绝含符号链接的包。
     * 路径不存在时返回 null，由调用方转换为 reject，与 Tauri 的 stat 失败语义一致。
     */
    private fun gpNextStatJson(rawPath: String): String? = runCatching {
        val file = gpNextFile(rawPath)
        val st = Os.lstat(file.absolutePath)
        val type = st.st_mode and OsConstants.S_IFMT
        JSONObject().apply {
            put("isFile", type == OsConstants.S_IFREG)
            put("isDirectory", type == OsConstants.S_IFDIR)
            put("isSymlink", type == OsConstants.S_IFLNK)
            put("size", st.st_size)
            // Os.lstat 返回秒，而 FileInfo 使用毫秒
            put("mtime", st.st_mtime * 1000L)
            put("atime", st.st_atime * 1000L)
            // Android 无法获取 birthtime，此处以 ctime 近似
            put("birthtime", st.st_ctime * 1000L)
            put("readonly", !file.canWrite())
            put("fileAttributes", 0)
            put("dev", st.st_dev)
            put("ino", st.st_ino)
            put("mode", st.st_mode)
            put("nlink", st.st_nlink)
            put("uid", st.st_uid)
            put("gid", st.st_gid)
            put("rdev", st.st_rdev)
            put("blksize", st.st_blksize)
            put("blocks", st.st_blocks)
        }.toString()
    }.getOrNull()

    /** 重命名；源文件不存在，或任一参数指向数据根时返回 false */
    private fun gpNextRename(oldRawPath: String, newRawPath: String): Boolean {
        val src = gpNextFile(oldRawPath)
        if (!src.exists()) return false
        val dst = gpNextFile(newRawPath)
        if (src.canonicalFile == gpNextDir.canonicalFile || dst.canonicalFile == gpNextDir.canonicalFile) {
            return false
        }
        dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }

    /** 写入二进制内容（以 base64 传入），父目录不存在时自动创建 */
    private fun gpNextWriteBytes(rawPath: String, base64: String): Boolean = runCatching {
        val file = gpNextFile(rawPath)
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
    }.isSuccess

    /**
     * 以系统文件管理器打开 gp-next 数据目录（即 DocumentsProvider 的 gpnext 根）。
     * 对应游戏 patcher 页的「打开补丁文件夹」。
     */
    private fun openGpNextFolder() {
        val authority = "$packageName.documents"
        val rootUri = DocumentsContract.buildRootUri(authority, GameDocumentsProvider.GP_NEXT_ROOT_ID)
        val initialUri =
            DocumentsContract.buildDocumentUri(authority, "${GameDocumentsProvider.GP_NEXT_ROOT_ID}:")
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开 gp-next 数据目录", e)
            Toast.makeText(this, R.string.documents_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 切换 WebView 满屏 / 比例适配，并持久化该状态供下次启动沿用。
     */
    private fun setWebviewFullscreen(enabled: Boolean) {
        aspectContainer.fullscreen = enabled
        prefs.edit().putBoolean(PREF_FULLSCREEN, enabled).apply()
    }

    private fun setupBackNavigation() {
        if (isBackCallbackRegistered) return
        isBackCallbackRegistered = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLauncherVisible) {
                    val handled = navigateBackInLauncherAction?.invoke() == true
                    if (!handled) finish()
                } else if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint) // 需在 strings.xml 定义
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}

