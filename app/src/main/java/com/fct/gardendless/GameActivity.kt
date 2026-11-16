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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.Menu
import android.view.MenuItem
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
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

class GameActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var aspectContainer: AspectRatioFrameLayout

    private val prefs by lazy { getSharedPreferences("app_data", MODE_PRIVATE) }
    private val gameLocalStorageBackup by lazy { GameLocalStorageBackupStore(filesDir) }

    /**
     * gp-next 数据目录，对应 Tauri 的 AppData 根。
     * 游戏会把 plugin:path|resolve_directory 的返回值拼上 `/gp-next`，
     * 因此数据实际位于 filesDir/gp-next，与 GameDocumentsProvider 的 gpnext 根指向同一目录。
     */
    private val gpNextDir: File by lazy { File(filesDir, GP_NEXT_DIR_NAME).apply { mkdirs() } }
    private val gpNextPathResolver: GpNextPathResolver by lazy {
        GpNextPathResolver(filesDir, gpNextDir)
    }

    /**
     * 注入页面的 Tauri shim（assets/gdnext-shim.js）。
     * 前置的 window.__gdForceJsModding 供 shim 决定是否自动启用 JS Modding，见 [FORCE_JS_MODDING]。
     */
    private val gpNextShimJs: String by lazy {
        val js = runCatching {
            assets.open(SHIM_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { e ->
            Log.e(TAG, "无法读取 $SHIM_ASSET，gp-next 的数据包与 JS 模组将不可用", e)
            ""
        }
        if (js.isEmpty()) return@lazy ""
        val savedStorage = try {
            gameLocalStorageBackup.read()
        } catch (e: IOException) {
            Log.e(TAG, "Unable to read the game local-storage backup", e)
            null
        }
        buildString {
            if (savedStorage != null) {
                append("window.__gdGameLocalStorageBackup=")
                append(JSONObject.quote(savedStorage))
                append(";\n")
            }
            append("window.__gdForceJsModding=$FORCE_JS_MODDING;\n")
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

    // 网页全屏（HTML5 Fullscreen API）回调，非空表示当前处于全屏
    private var fullscreenCallback: CustomViewCallback? = null
    private var isLauncherVisible = true

    private enum class LauncherButtonStyle {
        PRIMARY,
        TONAL,
        OUTLINED
    }

    private companion object {
        const val PREF_FULLSCREEN = "webview_fullscreen"
        const val PREF_AUTO_LAUNCH_GAME = "auto_launch_game"
        const val TAG = "Gardendless"
        const val SHIM_ASSET = "gdnext-shim.js"
        const val GP_NEXT_DIR_NAME = "gp-next"
        const val TELEGRAM_CHANNEL_URL = "https://t.me/pvz2gardendless"
        const val MENU_SETTINGS_ID = 1

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
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        val shouldAutoLaunch = savedInstanceState == null &&
            prefs.getBoolean(PREF_AUTO_LAUNCH_GAME, false) &&
            GameArchiveInstaller(filesDir).installedSiteDirectory() != null
        if (shouldAutoLaunch) launchGame() else showGameMenu()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isLauncherVisible) showGameMenu()
    }

    private fun launchGame() {
        if (GameArchiveInstaller(filesDir).installedSiteDirectory() == null) {
            showGameMenu()
            return
        }
        isLauncherVisible = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setGameRefreshRate(true)
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
            webView = MouseGameWebView(this)

            // 黑底容器，负责 16:10 ~ 17:9 的比例适配与全屏切换
            aspectContainer = AspectRatioFrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // 沿用上次退出时的全屏状态，避免先小后大的尺寸跳变
                fullscreen = prefs.getBoolean(PREF_FULLSCREEN, false)
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

                @JavascriptInterface
                fun saveGameLocalStorageBackup(snapshot: String): Boolean {
                    return try {
                        val saved = gameLocalStorageBackup.write(snapshot)
                        if (!saved) {
                            Log.w(TAG, "Game local-storage snapshot exceeds the Android backup size limit")
                        }
                        saved
                    } catch (e: IOException) {
                        Log.e(TAG, "Unable to save the game local-storage backup", e)
                        false
                    }
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

    private fun showGameMenu() {
        isLauncherVisible = true
        setGameRefreshRate(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        val hasGame = GameArchiveInstaller(filesDir).installedSiteDirectory() != null
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val onSurfaceVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.LTGRAY
        )
        val secondaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSecondaryContainer,
            Color.DKGRAY
        )
        val onSecondaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            Color.WHITE
        )
        val primaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            surface
        )
        val onPrimaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            onSurface
        )
        val tertiaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorTertiaryContainer,
            secondaryContainer
        )
        val onTertiaryContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnTertiaryContainer,
            onSecondaryContainer
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.app_name)
            setTitleTextAppearance(
                this@GameActivity,
                com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
            )
            setTitleTextColor(onSurface)
            setBackgroundColor(surface)
            elevation = 0f
            subtitle = getString(R.string.launcher_subtitle)
            setSubtitleTextColor(onSurfaceVariant)
            menu.add(Menu.NONE, MENU_SETTINGS_ID, Menu.NONE, R.string.launcher_settings).apply {
                setIcon(R.drawable.ic_settings)
                icon?.mutate()?.setTint(onSurfaceVariant)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_SETTINGS_ID) {
                    showLauncherSettings()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72))
        )
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val centeredContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(36).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(primaryContainer)
        }
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        val statusChip = Chip(this).apply {
            text = getString(if (hasGame) R.string.game_status_installed else R.string.game_status_missing)
            isCheckable = false
            isClickable = false
            chipCornerRadius = dp(16).toFloat()
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(tertiaryContainer)
            setTextColor(onTertiaryContainer)
        }
        cardContent.addView(statusChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })
        cardContent.addView(MaterialTextView(this).apply {
            text = getString(if (hasGame) R.string.game_ready_title else R.string.game_setup_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall)
            setTextColor(onPrimaryContainer)
        })
        cardContent.addView(MaterialTextView(this).apply {
            text = getString(R.string.game_menu_description)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(onPrimaryContainer)
            alpha = 0.82f
            setPadding(0, dp(12), 0, dp(20))
        })

        if (hasGame) {
            addMenuButton(
                cardContent,
                R.string.launch_game,
                R.drawable.ic_play_arrow,
                LauncherButtonStyle.PRIMARY,
                ::launchGame
            )
            addMenuButton(
                cardContent,
                R.string.update_game_zip,
                R.drawable.ic_refresh,
                LauncherButtonStyle.OUTLINED,
                ::confirmGameUpdate
            )
        } else {
            addMenuButton(
                cardContent,
                R.string.choose_game_zip,
                R.drawable.ic_folder_open,
                LauncherButtonStyle.PRIMARY,
                ::selectGameArchive
            )
        }
        addMenuButton(
            cardContent,
            R.string.download_zip_from_telegram,
            R.drawable.ic_download,
            LauncherButtonStyle.TONAL,
            ::openTelegramChannel
        )

        card.addView(cardContent)
        val cardWidth = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(760))
        centeredContent.addView(card, LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        scrollView.addView(
            centeredContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        window.statusBarColor = surface
        window.navigationBarColor = surface
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightNavigationBars = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun showLauncherSettings() {
        val surfaceContainer = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        )
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val onSurfaceVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.LTGRAY
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(MaterialTextView(this).apply {
            text = getString(R.string.auto_launch_game)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(onSurface)
        })
        labels.addView(MaterialTextView(this).apply {
            text = getString(R.string.auto_launch_game_summary)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceVariant)
            setPadding(0, dp(4), dp(12), 0)
        })
        content.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val autoLaunchSwitch = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(PREF_AUTO_LAUNCH_GAME, false)
            contentDescription = getString(R.string.auto_launch_game)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PREF_AUTO_LAUNCH_GAME, checked).apply()
            }
        }
        content.addView(autoLaunchSwitch)
        content.setOnClickListener { autoLaunchSwitch.isChecked = !autoLaunchSwitch.isChecked }
        val card = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(surfaceContainer)
            addView(content)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.launcher_settings)
            .setView(card, dp(12), dp(8), dp(12), dp(4))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun createGameControls(): View {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        fun addIconButton(iconRes: Int, descriptionRes: Int, onClick: () -> Unit) {
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
            val rippleColor = MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorControlHighlight,
                onSurface
            )
            val button = FrameLayout(this).apply {
                contentDescription = getString(descriptionRes)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(surfaceContainer)
                        cornerRadius = dp(18).toFloat()
                    },
                    null
                )
                setOnClickListener { onClick() }
            }
            button.addView(ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(onSurface)
                contentDescription = null
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            controls.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                if (controls.childCount > 0) topMargin = dp(8)
            })
        }
        addIconButton(R.drawable.ic_home, R.string.home_button_description, ::returnToLauncher)
        addIconButton(R.drawable.ic_tune, R.string.gp_next_button_description, ::toggleGpNextMenu)
        return controls
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

    private fun addMenuButton(
        parent: LinearLayout,
        textRes: Int,
        iconRes: Int,
        style: LauncherButtonStyle,
        onClick: () -> Unit
    ) {
        val button = MaterialButton(this).apply {
            text = getString(textRes)
            cornerRadius = dp(20)
            insetTop = 0
            insetBottom = 0
            icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@GameActivity, iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(12)
            setOnClickListener { onClick() }
            val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.GRAY)
            val onPrimary = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnPrimary,
                Color.WHITE
            )
            val secondaryContainer = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val onSecondaryContainer = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                Color.WHITE
            )
            when (style) {
                LauncherButtonStyle.PRIMARY -> {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
                    iconTint = android.content.res.ColorStateList.valueOf(onPrimary)
                    setTextColor(onPrimary)
                }
                LauncherButtonStyle.TONAL -> {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(secondaryContainer)
                    iconTint = android.content.res.ColorStateList.valueOf(onSecondaryContainer)
                    setTextColor(onSecondaryContainer)
                }
                LauncherButtonStyle.OUTLINED -> {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)
                    )
                    strokeWidth = dp(1)
                    strokeColor = android.content.res.ColorStateList.valueOf(
                        MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOutline,
                            Color.GRAY
                        )
                    )
                    iconTint = android.content.res.ColorStateList.valueOf(primary)
                    setTextColor(primary)
                }
            }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )
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
        val progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
        }
        val progressText = TextView(this).apply {
            text = getString(R.string.installing_game_description)
            setPadding(0, dp(12), 0, 0)
        }
        val progressContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
            addView(progressText)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.installing_game)
            .setView(progressContent)
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
                                progress.setProgressCompat(percentage, false)
                                progressText.text = getString(R.string.installing_progress, percentage)
                            }
                        }
                    }
                }
                progress.isIndeterminate = false
                progress.setProgressCompat(100, false)
                dialog.dismiss()
                showGameMenu()
                Toast.makeText(this@GameActivity, R.string.game_zip_installed, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install game ZIP", e)
                dialog.dismiss()
                showGameMenu()
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
                Log.i(TAG, "GP-Next toggle API unavailable; dispatching F9 to WebView")
                webView.requestFocus()
                webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F9))
                webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F9))
            }
        }
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

        // 保持屏幕常亮，适合游戏场景
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
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

