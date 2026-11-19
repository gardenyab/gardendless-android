/*
 * gp-next Tauri shim（Android / WebView）
 *
 * 游戏包（pvzge_web-master）的 index.html 内联了一份 Tauri polyfill，其文件系统由 localStorage
 * 模拟：read_dir 恒返回空数组，write_text_file 的参数形状与真实命令不一致，导致 gp-next 的
 * 数据包系统在 WebView 下无法工作。
 *
 * 本脚本不修改游戏包，而是在页面中包装 __TAURI_INTERNALS__.invoke：
 *   - plugin:fs|*、plugin:path|resolve_directory、plugin:opener|open_path 交由原生处理；
 *   - 其余命令继续由原 polyfill 处理，行为不变。
 *
 * 数据目录约定（与 GameActivity.gpNextDir 一致）：
 *   AppData 根为 filesDir，游戏会在其后拼接 /gp-next，因此数据实际位于 filesDir/gp-next。
 *   读操作走 https://appassets.androidplatform.net/gp-next/... 虚拟路径，由 WebViewAssetLoader
 *   的 PathHandler 映射到该目录，流式返回原始字节，无需 base64 中转。
 *
 * 当 window.__gdForceJsModding === true 时代为调用一次 enableJsModding()，以解开游戏侧默认
 * 关闭、且无法从界面打开的 JS Modding 开关（见文件末尾）。
 */
(function () {
    'use strict';

    var TAG = '[gd-next-shim]';
    var bridge = window.GardendlessBridge;
    var electronApi = window.electronAPI;
    if (!electronApi || typeof electronApi !== 'object') {
        electronApi = {};
        window.electronAPI = electronApi;
    }
    if (typeof electronApi.window !== 'function') {
        electronApi.window = function () {
            if (bridge && typeof bridge.setFullscreen === 'function') {
                bridge.setFullscreen(false);
            }
        };
    }

    // 补齐 Electron 预加载脚本（assets/preload-*.js）提供给游戏的 electronAPI。
    //
    // 游戏里的 ElectronAPI 帮助类对多数方法用的是 `api?.method()` 这种可选调用：
    // 它只能挡住 api 本身为空，挡不住方法不存在——方法缺失会直接抛 TypeError。
    // 其中 getCurrentResolutionSync 缺失会让 SettingWindow.onLoad 在
    // getDefaultResolution() 处中断，后面的音量/音效等字段读不到，UI 停在默认值，
    // 之后任意一次切换又会把默认值整体写回 PvZ2_Settings，看起来就是「设置被重置」。
    // WebView 没有真实的窗口管理，这里按原实现给出等价的空值（原预加载脚本中
    // getCurrentResolutionSync 同样返回空串）。
    var electronApiStubs = {
        isFullScreen: function () { return false; },
        getCurrentResolutionSync: function () { return ''; },
        getAllResolutionsSync: function () { return []; },
        getMassageSync: function () { return ''; },
        setSize: function () { },
        setResolution: function () { },
        nircmd: function () { },
        center: function () { },
        quit: function () { },
        openDevTools: function () { },
        closeDevTools: function () { },
        toggleDevTools: function () { }
    };
    Object.keys(electronApiStubs).forEach(function (name) {
        if (typeof electronApi[name] !== 'function') {
            electronApi[name] = electronApiStubs[name];
        }
    });

    installGameDialogWebViewStyles();
    if (!bridge || window.__gdHooked) return;
    window.__gdHooked = true;

    var ORIGIN = 'https://appassets.androidplatform.net/';

    // ── 全屏与导出 ──
    // 以下两项在 WebView 下必须由原生接管：
    // - 全屏：polyfill 将 plugin:window|set_fullscreen 实现为空函数，且 WebView 对非 video
    //   元素不回调 onShowCustomView；
    // - 导出：WebView 的 DownloadListener 不一定会收到 data: URL，故在链接触发时直接交给原生保存。

    // 游戏启动约 2 秒后会按自身状态同步一次 setFullscreen(false)，覆盖掉由原生从
    // SharedPreferences 恢复的全屏状态。因此在启动保护期内忽略这条自动同步的 false；
    // true 一律放行，不影响用户主动操作。
    var bootGuard = true;
    setTimeout(function () { bootGuard = false; }, 6000);

    function setFullscreen(v) {
        if (bootGuard && !v) { bootGuard = false; return; }
        bootGuard = false;
        try { bridge.setFullscreen(!!v); } catch (e) { /* 桥不可用时静默降级 */ }
    }

    function setExportName(name) {
        if (!name) return;
        try { bridge.setExportName(String(name)); } catch (e) { /* 忽略 */ }
    }

    function isDataHref(el) {
        return !!(el && el.getAttribute && (el.getAttribute('href') || '').indexOf('data:') === 0);
    }

    function isBlobHref(el) {
        return !!(el && el.getAttribute && (el.getAttribute('href') || '').indexOf('blob:') === 0);
    }

    function exportDataUri(anchor) {
        if (!anchor || !anchor.hasAttribute('download') || !isDataHref(anchor) ||
            typeof bridge.exportDataUri !== 'function') return false;
        try {
            bridge.exportDataUri(anchor.getAttribute('href'), anchor.getAttribute('download') || '');
            return true;
        } catch (e) {
            console.warn(TAG, 'native save dialog could not be opened:', e);
            return false;
        }
    }

    function exportBlob(anchor) {
        if (!anchor || !anchor.hasAttribute('download') || !isBlobHref(anchor) ||
            typeof bridge.beginExportTransfer !== 'function' ||
            typeof bridge.appendExportChunk !== 'function' ||
            typeof bridge.finishExportTransfer !== 'function') return false;

        var fileName = anchor.getAttribute('download') || '';
        var mimeType = anchor.getAttribute('type') || 'application/octet-stream';
        try {
            if (!bridge.beginExportTransfer(fileName, mimeType)) return false;
        } catch (e) {
            console.warn(TAG, 'native blob export could not be started:', e);
            return false;
        }

        fetch(anchor.getAttribute('href')).then(function (response) {
            if (!response.ok) throw new Error('blob read failed (' + response.status + ')');
            return response.blob();
        }).then(function (blob) {
            mimeType = blob.type || mimeType;
            var reader = new FileReader();
            reader.onerror = function () {
                bridge.cancelExportTransfer();
                console.warn(TAG, 'failed to read the exported blob');
            };
            reader.onload = function () {
                try {
                    var bytes = new Uint8Array(reader.result);
                    var chunkSize = 48 * 1024;
                    for (var offset = 0; offset < bytes.length; offset += chunkSize) {
                        var binary = String.fromCharCode.apply(
                            null,
                            bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length))
                        );
                        if (!bridge.appendExportChunk(btoa(binary))) {
                            throw new Error('native export rejected a data chunk');
                        }
                    }
                    if (!bridge.finishExportTransfer(mimeType)) {
                        throw new Error('native export could not be completed');
                    }
                } catch (e) {
                    bridge.cancelExportTransfer();
                    console.warn(TAG, 'failed to transfer the exported blob:', e);
                }
            };
            reader.readAsArrayBuffer(blob);
        }).catch(function (e) {
            bridge.cancelExportTransfer();
            console.warn(TAG, 'failed to fetch the exported blob:', e);
        });
        return true;
    }

    function exportDownload(anchor) {
        return exportDataUri(anchor) || exportBlob(anchor);
    }

    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (this.hasAttribute('download')) {
            if (exportDownload(this)) return;
            setExportName(this.getAttribute('download'));
        }
        return origAnchorClick.apply(this, arguments);
    };
    document.addEventListener('click', function (e) {
        var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
        if (a && (isDataHref(a) || isBlobHref(a))) {
            if (exportDownload(a)) {
                e.preventDefault();
                e.stopImmediatePropagation();
            } else {
                setExportName(a.getAttribute('download'));
            }
        }
    }, true);

    // 标准 Fullscreen API 路径（游戏若改用该路径亦可覆盖）
    var ep = Element.prototype;
    var reqFullscreen = ep.requestFullscreen || ep.webkitRequestFullscreen || ep.webkitRequestFullScreen;
    if (reqFullscreen) {
        ep.requestFullscreen = function () { setFullscreen(true); return reqFullscreen.apply(this, arguments); };
    }
    var dp = Document.prototype;
    var exitFullscreen = dp.exitFullscreen || dp.webkitExitFullscreen || dp.webkitCancelFullScreen;
    if (exitFullscreen) {
        dp.exitFullscreen = function () { setFullscreen(false); return exitFullscreen.apply(this, arguments); };
    }

    // ── gp-next 数据目录 ──

    var appRoot = null;

    function appDataRoot() {
        if (appRoot === null) {
            try { appRoot = String(bridge.appDataRoot() || '').replace(/\\/g, '/').replace(/\/+$/, ''); }
            catch (e) { appRoot = ''; }
        }
        return appRoot;
    }

    // 游戏传给 plugin:fs 的路径为相对 AppData 的路径（如 gp-next\packs\Foo\pack.json），
    // 也可能拼接了 resolve_directory 的返回值而成为绝对路径；此处统一收敛为 "gp-next/xxx"。
    function relPath(rawPath) {
        var s = String(rawPath == null ? '' : rawPath).replace(/\\/g, '/').replace(/\/+$/, '');
        var root = appDataRoot();
        if (root && s === root) return 'gp-next';
        if (root && s.indexOf(root + '/') === 0) s = s.slice(root.length);
        return s.replace(/^\/+/, '');
    }

    function urlOf(rawPath) {
        return ORIGIN + relPath(rawPath).split('/').map(encodeURIComponent).join('/');
    }

    // 按 Tauri 语义，文件不存在时抛错，由游戏侧 catch 成 null / false。
    //
    // read_text_file 同样必须返回字节而非字符串：dist-js 的实现为
    //   `r instanceof ArrayBuffer ? r : Uint8Array.from(r)`，之后才交给 TextDecoder.decode。
    // 若返回字符串，Uint8Array.from 会按字符数组处理（每个字符转为 NaN 再变为 0），
    // 解码结果全为 \0。真实 Tauri 命令返回的也是原始字节（tauri::ipc::Response），
    // 因此两种读操作统一使用 arrayBuffer。
    function readBytes(rawPath, label) {
        return fetch(urlOf(rawPath), { cache: 'no-store' }).then(function (res) {
            if (!res.ok) throw new Error(label + ' failed (' + res.status + '): ' + relPath(rawPath));
            return res.arrayBuffer();
        });
    }

    function bodyToText(payload) {
        if (typeof payload === 'string') return payload;
        if (payload instanceof ArrayBuffer) return new TextDecoder().decode(new Uint8Array(payload));
        if (payload && payload.buffer instanceof ArrayBuffer) return new TextDecoder().decode(payload);
        return String(payload == null ? '' : payload);
    }

    function bodyToBytes(payload) {
        if (payload instanceof ArrayBuffer) return new Uint8Array(payload);
        if (payload && payload.buffer instanceof ArrayBuffer) return new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength);
        if (typeof payload === 'string') return new TextEncoder().encode(payload);
        return new Uint8Array(0);
    }

    function installGameDialogWebViewStyles() {
        var styleId = 'gardendless-game-dialog-webview-style';
        var stylesheetId = 'gardendless-game-dialog-stylesheet';
        var css = [
            // 页面的 <body> 默认是 #333；游戏画面铺满时透明边缘会透出底色，
            // 在 WebView 与黑边交界处表现为细亮的竖线，这里统一压成纯黑。
            'html,body{background-color:#000!important}',
            'dialog.game-dialog[open]{display:flex!important;position:fixed!important;',
            'top:50%!important;left:50%!important;right:auto!important;bottom:auto!important;',
            'transform:translate(-50%,-50%) scale(.46)!important;transform-origin:center center!important;',
            'margin:0!important;',
            'width:calc(100vw - 24px)!important;max-width:900px!important;',
            'height:80vh!important;max-height:calc(100vh - 24px)!important;',
            'height:80dvh!important;max-height:calc(100dvh - 24px)!important;',
            'min-height:200px!important;box-sizing:border-box!important;',
            'flex-direction:column!important;overflow:hidden!important;',
            'z-index:2147483647!important}',
            'dialog.game-dialog[open]>.game-markdown,',
            'dialog.game-dialog[open]>.game-github,',
            'dialog.game-dialog[open]>.game-dialog-status{display:flex!important;',
            'flex:1 1 auto!important;min-height:0!important;width:100%!important;}',
            'dialog.game-dialog[open]>.game-markdown{display:block!important;overflow:auto!important}',
            'dialog.game-dialog[open]>.game-github{flex-direction:column!important;overflow:hidden!important}'
        ].join('');

        function ensureGameWindowStylesheet(parent) {
            if (document.querySelector('link[rel="stylesheet"][href*="game-window"]') ||
                document.getElementById(stylesheetId)) return;
            var link = document.createElement('link');
            link.id = stylesheetId;
            link.rel = 'stylesheet';
            link.href = new URL('game-window.css', document.baseURI).href;
            link.onerror = function () {
                console.warn(TAG, 'Could not load the game window stylesheet:', link.href);
            };
            parent.appendChild(link);
        }

        function addStyle() {
            var parent = document.head || document.documentElement;
            if (!parent) return false;
            ensureGameWindowStylesheet(parent);
            if (document.getElementById(styleId)) return true;
            var style = document.createElement('style');
            style.id = styleId;
            style.textContent = css;
            parent.appendChild(style);
            return true;
        }

        if (addStyle()) return;
        var observer = new MutationObserver(function () {
            if (addStyle()) observer.disconnect();
        });
        observer.observe(document, { childList: true, subtree: true });
    }

    // 分块编码，避免大文件在参数展开时导致栈溢出
    function bytesToBase64(bytes) {
        var chunk = 0x8000;
        var parts = '';
        for (var i = 0; i < bytes.length; i += chunk) {
            parts += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(parts);
    }

    /** 读取 ReadableStream 的全部内容，返回 Uint8Array */
    function readStreamBytes(stream) {
        var reader = stream.getReader();
        var chunks = [];
        var total = 0;
        function pump() {
            return reader.read().then(function (r) {
                if (r.done) {
                    reader.releaseLock();
                    var out = new Uint8Array(total);
                    var offset = 0;
                    for (var i = 0; i < chunks.length; i++) {
                        out.set(chunks[i], offset);
                        offset += chunks[i].length;
                    }
                    return out;
                }
                chunks.push(r.value);
                total += r.value.length;
                return pump();
            });
        }
        return pump();
    }

    /**
     * 处理 gp-next 相关命令；返回 null 表示不接管，继续交由原 polyfill。
     *
     * @param {string} cmd 命令名
     * @param {object} args invoke 的第二个参数
     * @param {*} body invoke 的第二个参数原样（write_text_file 的 body 为 Uint8Array）
     * @param {object} options invoke 的第三个参数（write_text_file 的 path 位于 headers 中）
     */
    function handleGpNextCommand(cmd, args, body, options) {
        switch (cmd) {
            case 'plugin:path|resolve_directory':
                // 返回 AppData 根目录，游戏会自行拼上 /gp-next
                return Promise.resolve(appDataRoot() || '/');

            case 'plugin:fs|read_text_file':
                return readBytes(args.path, 'read_text_file');

            case 'plugin:fs|read_file':
                return readBytes(args.path, 'read_file');

            // dist-js 会把返回值直接交给 FileInfo 映射（首个字段即 isFile），
            // 因此必须返回对象；失败时抛错，不可返回 null，否则映射处会读取 null 的属性。
            case 'plugin:fs|stat':
            case 'plugin:fs|lstat': {
                var statJson = bridge.fsStat(relPath(args.path));
                if (statJson === null || statJson === undefined) {
                    throw new Error(cmd + ' failed: ' + relPath(args.path));
                }
                return Promise.resolve(JSON.parse(statJson));
            }

            case 'plugin:fs|rename': {
                // 新版 dist-js 新增的命令：invoke('plugin:fs|rename', { oldPath, newPath, options })
                if (!bridge.fsRename(relPath(args.oldPath), relPath(args.newPath))) {
                    throw new Error('rename failed: ' + relPath(args.oldPath));
                }
                return Promise.resolve(null);
            }

            case 'plugin:fs|write_file': {
                // 新版 dist-js 新增的命令：invoke('plugin:fs|write_file', bytes, { headers: { path, options } })
                var wfHeaders = (options && options.headers) || {};
                var wfPath = wfHeaders.path ? decodeURIComponent(wfHeaders.path) : (args && args.path);
                if (!wfPath) throw new Error('write_file requires a path');
                if (body && typeof body.getReader === 'function') {
                    return readStreamBytes(body).then(function (bytes) {
                        if (!bridge.fsWriteBytes(relPath(wfPath), bytesToBase64(bytes))) {
                            throw new Error('write_file failed: ' + wfPath);
                        }
                        return null;
                    });
                }
                if (!bridge.fsWriteBytes(relPath(wfPath), bytesToBase64(bodyToBytes(body)))) {
                    throw new Error('write_file failed: ' + wfPath);
                }
                return Promise.resolve(null);
            }

            case 'plugin:fs|read_dir': {
                var json = bridge.fsReadDir(relPath(args.path));
                if (json === null || json === undefined) {
                    throw new Error('read_dir failed: ' + relPath(args.path));
                }
                return Promise.resolve(JSON.parse(json));
            }

            case 'plugin:fs|exists':
                return Promise.resolve(!!bridge.fsExists(relPath(args.path)));

            case 'plugin:fs|mkdir':
                if (!bridge.fsMkdir(relPath(args.path))) {
                    throw new Error('mkdir failed: ' + relPath(args.path));
                }
                return Promise.resolve(null);

            case 'plugin:fs|remove':
                if (!bridge.fsRemove(relPath(args.path))) {
                    throw new Error('remove failed: ' + relPath(args.path));
                }
                return Promise.resolve(null);

            case 'plugin:fs|write_text_file': {
                // 真实形态为 invoke(cmd, bodyBytes, { headers: { path, options } })。
                // polyfill 读取的是 args.headers.path，而 args 实际是 body，因此该命令在
                // polyfill 下始终失败。
                var headers = (options && options.headers) || {};
                var path = headers.path ? decodeURIComponent(headers.path) : (args && args.path);
                if (!path) throw new Error('write_text_file requires a path');
                if (!bridge.fsWriteText(relPath(path), bodyToText(body))) {
                    throw new Error('write_text_file failed: ' + path);
                }
                return Promise.resolve(null);
            }

            case 'plugin:opener|open_path':
                // 目前仅 patcher 页的「打开补丁文件夹」使用，落到系统的 gp-next 文档根
                bridge.openDataFolder();
                return Promise.resolve(null);
        }
        return null;
    }

    // 本脚本接管的命令：全部 plugin:fs|* 以及以下两个精确匹配项
    var HANDLED = /^plugin:fs\|/;
    var HANDLED_EXACT = ['plugin:path|resolve_directory', 'plugin:opener|open_path'];

    function patchTauri() {
        var ti = window.__TAURI_INTERNALS__;
        if (!ti || typeof ti.invoke !== 'function' || ti.__gdHooked) return false;
        ti.__gdHooked = true;

        var origInvoke = ti.invoke;
        ti.invoke = function (cmd, args, options) {
            var name = String(cmd || '');

            if (HANDLED_EXACT.indexOf(name) >= 0 || HANDLED.test(name)) {
                try {
                    var result = handleGpNextCommand(name, args || {}, args, options);
                    if (result) return result;
                    // 未接管的 fs 命令会落入 polyfill 的 default 分支并返回 null，
                    // 通常表现为难以定位的 "Cannot read properties of null"，故在此显式告警。
                    console.warn(TAG, 'unhandled command, falling back to polyfill:', name);
                } catch (e) {
                    console.warn(TAG, name + ' failed:', e);
                    return Promise.reject(e);
                }
            }

            if (name === 'plugin:window|set_fullscreen') {
                setFullscreen(!!(args && args.value));
                return Promise.resolve(null);
            }

            return origInvoke.apply(this, arguments);
        };
        console.log(TAG, 'installed, appData =', appDataRoot());
        return true;
    }

    // polyfill 在 head 中同步执行，可能晚于本脚本，故短暂轮询等待
    if (!patchTauri()) {
        var tries = 0;
        var timer = setInterval(function () {
            if (patchTauri() || ++tries > 100) clearInterval(timer);
        }, 50);
    }

    // ── JS Modding 自动启用 ──
    // 游戏侧 experimental.jsModding 默认关闭，且「实验性」页中的对应开关处于锁定状态
    // （pointer-events:none，回调会把 true 还原为 false），唯一入口是控制台的
    // window.gpNext.mods.enableJsModding()。因此这里在启动后代为调用一次。
    //
    // 幂等处理：enableJsModding() 会先持久化 experimental.jsModding=true，再重新载入 JS 模组。
    // 若已持久化为开启，则本次启动的初始加载已载入过模组，这里直接返回，避免多一次
    // dispose + reload；若持久化为关闭（含用户在界面中关闭），则重新开启。

    /** 读取 localStorage 中已持久化的 jsModding 开关 */
    function jsModdingPersisted() {
        try {
            var s = JSON.parse(localStorage.getItem('gp-next-settings') || '{}');
            return !!(s && s.experimental && s.experimental.jsModding === true);
        } catch (e) {
            return false;
        }
    }

    /** 尝试启用 JS Modding；返回 false 表示 window.gpNext.mods 尚未挂载 */
    function tryEnableJsModding() {
        var mods = window.gpNext && window.gpNext.mods;
        if (!mods || typeof mods.enableJsModding !== 'function') return false;
        if (jsModdingPersisted()) {
            console.log(TAG, 'JS Modding already enabled');
            return true;
        }
        Promise.resolve()
            .then(function () { return mods.enableJsModding(); })
            .then(function () { console.log(TAG, 'JS Modding enabled'); })
            .catch(function (e) { console.warn(TAG, 'enableJsModding failed:', e); });
        return true;
    }

    // window.gpNext.mods 由游戏在 phase-4 挂载（引擎就绪且补丁加载完成后），故轮询等待。
    // 上限 300 秒，覆盖引擎等待超时 30 秒与补丁加载的余量。
    if (window.__gdForceJsModding === true) {
        if (!tryEnableJsModding()) {
            var modTries = 0;
            var modTimer = setInterval(function () {
                if (tryEnableJsModding() || ++modTries > 3000) clearInterval(modTimer);
            }, 100);
        }
    }

    // ── Ambient Audio Waves (Web Audio + HTMLAudio Analyser tap, zero permissions) ──
    (function installAmbientAudioTap() {
        if (!bridge || typeof bridge.setAmbientAudioSpectrum !== 'function') return;
        var AudioNodeCtor = window.AudioNode;
        var AudioCtxCtor = window.AudioContext || window.webkitAudioContext;

        var analysers = [];
        var origConnect = AudioNodeCtor && AudioNodeCtor.prototype && AudioNodeCtor.prototype.connect;
        var mediaCtx = null;

        function ensureAnalyser(ctx) {
            if (!ctx || typeof ctx.createAnalyser !== 'function') return null;
            if (ctx.__gdAmbientAnalyser) return ctx.__gdAmbientAnalyser;
            try {
                var analyser = ctx.createAnalyser();
                analyser.fftSize = 256;
                analyser.minDecibels = -64;
                analyser.maxDecibels = -8;
                analyser.smoothingTimeConstant = 0.55;
                var silentGain = ctx.createGain();
                silentGain.gain.value = 0;
                if (origConnect) {
                    origConnect.call(analyser, silentGain);
                    origConnect.call(silentGain, ctx.destination);
                } else {
                    analyser.connect(silentGain);
                    silentGain.connect(ctx.destination);
                }
                ctx.__gdAmbientAnalyser = analyser;
                analysers.push({
                    ctx: ctx,
                    analyser: analyser,
                    freqBins: new Uint8Array(analyser.frequencyBinCount),
                    timeBins: new Uint8Array(analyser.fftSize)
                });
                return analyser;
            } catch (e) {
                return null;
            }
        }

        // 1. Hook Web Audio API (SFX & WebAudio BGM)
        if (origConnect) {
            AudioNodeCtor.prototype.connect = function (dest) {
                var result = origConnect.apply(this, arguments);
                try {
                    if (dest && this && this.context &&
                        dest === this.context.destination &&
                        this !== this.context.__gdAmbientAnalyser) {
                        var analyser = ensureAnalyser(this.context);
                        if (analyser) {
                            origConnect.call(this, analyser);
                        }
                    }
                } catch (e) {
                    /* keep game audio untouched */
                }
                return result;
            };
        }

        // 2. Hook DOM <audio> elements (Cocos DOM_AUDIO BGM tracks)
        if (AudioCtxCtor && window.HTMLMediaElement && HTMLMediaElement.prototype &&
            typeof HTMLMediaElement.prototype.play === 'function') {
            var origPlay = HTMLMediaElement.prototype.play;
            HTMLMediaElement.prototype.play = function () {
                try {
                    if (this && this.tagName === 'AUDIO' && !this.__gdAudioTapped) {
                        var src = this.currentSrc || this.src || '';
                        if (!src || src.indexOf(ORIGIN) === 0 || src.indexOf('blob:') === 0) {
                            this.__gdAudioTapped = true;
                            if (!mediaCtx) mediaCtx = new AudioCtxCtor();
                            if (mediaCtx.state === 'suspended') {
                                mediaCtx.resume().catch(function () { });
                            }
                            var source = mediaCtx.createMediaElementSource(this);
                            var analyser = ensureAnalyser(mediaCtx);
                            if (origConnect) {
                                origConnect.call(source, mediaCtx.destination);
                                if (analyser) origConnect.call(source, analyser);
                            } else {
                                source.connect(mediaCtx.destination);
                                if (analyser) source.connect(analyser);
                            }
                        }
                    }
                } catch (e) {
                    /* ignore if element already bound or unsupported */
                }
                return origPlay.apply(this, arguments);
            };
        }

        var lastSentAt = 0;
        var lastNonZero = false;
        var slowRms = 0;

        function avgSlice(arr, start, end) {
            var e = Math.min(end, arr.length);
            if (e <= start) return 0;
            var sum = 0;
            for (var i = start; i < e; i++) sum += arr[i];
            return sum / ((e - start) * 255);
        }

        function tick(now) {
            requestAnimationFrame(tick);
            if (!window.__gdAmbientWavesEnabled) return;
            if (now - lastSentAt < 16) return;
            lastSentAt = now;

            var maxRms = 0;
            var maxBass = 0;
            var maxMid = 0;
            var maxHigh = 0;

            for (var i = 0; i < analysers.length; i++) {
                var entry = analysers[i];
                if (!entry.ctx || entry.ctx.state !== 'running') continue;
                try {
                    entry.analyser.getByteTimeDomainData(entry.timeBins);
                    var tb = entry.timeBins;
                    var sqSum = 0;
                    for (var s = 0; s < tb.length; s++) {
                        var centered = (tb[s] - 128) / 128;
                        sqSum += centered * centered;
                    }
                    var rms = Math.sqrt(sqSum / tb.length);
                    if (rms > maxRms) maxRms = rms;

                    if (rms > 0.002) {
                        entry.analyser.getByteFrequencyData(entry.freqBins);
                        var b = avgSlice(entry.freqBins, 1, 9);
                        var m = avgSlice(entry.freqBins, 9, 28);
                        var h = avgSlice(entry.freqBins, 28, 64);
                        if (b > maxBass) maxBass = b;
                        if (m > maxMid) maxMid = m;
                        if (h > maxHigh) maxHigh = h;
                    }
                } catch (e) { /* ignore */ }
            }

            // Smooth noise gate + adaptive transient punch so silence is truly 0 and beats pop
            var gate = Math.max(0, Math.min(1, (maxRms - 0.0025) / 0.014));
            slowRms = slowRms * 0.92 + maxRms * 0.08;
            var punch = Math.max(0, Math.min(1, (maxRms - slowRms * 0.72) * 5.5));
            var baseLoudness = Math.max(0, Math.min(1, Math.pow(maxRms * 3.8, 0.78)));

            var outLevel = gate * Math.min(1, baseLoudness * 0.65 + punch * 0.55);
            var outBass = gate * Math.min(1, Math.pow(maxBass, 0.85) * 0.7 + punch * 0.6);
            var outMid = gate * Math.min(1, Math.pow(maxMid, 0.85) * 0.75 + baseLoudness * 0.35);
            var outHigh = gate * Math.min(1, Math.pow(maxHigh, 0.85) * 0.8 + punch * 0.25);

            var active = outLevel > 0.004 || outBass > 0.004 || outMid > 0.004 || outHigh > 0.004;
            if (active || lastNonZero) {
                lastNonZero = active;
                try {
                    bridge.setAmbientAudioSpectrum(
                        active ? outLevel : 0,
                        active ? outBass : 0,
                        active ? outMid : 0,
                        active ? outHigh : 0
                    );
                } catch (e) { /* ignore */ }
            }
        }
        requestAnimationFrame(tick);
    })();
})();
