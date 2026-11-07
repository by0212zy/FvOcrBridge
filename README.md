# FvOcrBridge · FV 识图桥

把 **FV 悬浮球**的「自动文字识别」接到**独立本地 OCR 引擎**（`MiOcrStandalone`），
**不再依赖小米相册、不 Hook 相册进程、作用域只勾 FV 一个包**。

基于 [libxposed 现代 API 102](https://github.com/libxposed/api) 的 LSPosed 模块：
无界面、无桌面图标、不联网、不修改系统属性与 Settings 数据库、
**不含任何 so / 模型 / 第三方 OCR**。

> 这是 `GalleryOCRBridge` 的「独立引擎版」。两个模块**二选一**，不要同时在两者的作用域里勾选 FV 悬浮球。

---

## 它解决什么问题

| | FV 悬浮球自带 | 本模块接管后 |
| --- | --- | --- |
| 识别引擎 | 百度 PaddleLite（`BaiduOCREngine`） | 小米 `libmiocr.so`（由独立 App 提供） |
| 语言包 | 需自行下载，未下载时 `z1/c` 直接返回 `null` | 引擎随 App 内置，开箱即用 |
| 是否需要 Hook 小米相册 | — | ❌ 完全不需要 |
| 作用域 | — | 只勾 `com.fooview.android.fooview` |
| 失败时 | — | 自动回退 FV 自带识别，**功能不会变坏** |

原理上两个引擎的接口形状完全一致（都吃内存 `Bitmap`、吐纯文本），
所以只要在中间架一座桥：

```
FV 自动文字识别（自己已截好的 Bitmap）
        │  Hook BaiduOCREngine / z1-c / z1-e 的 recognize(Bitmap)->String
        ▼  Bundle 直传 Bitmap，一次 Binder（ashmem，不受 1MB 事务限制）
content call content://com.example.miocr.standalone.ocr / ocr_bitmap
        ▼  独立 App 的 :ocr 子进程 → OCREngine.doOCR(Bitmap) → total_text
        ▼  text 作为 recognize() 的返回值交回 FV
FV 照常显示 / 复制 / 搜索 / 分享，全程无感
```

**不用给 FV 截屏权限、不用 root、零临时图片文件、不用下载百度语言包。**

---

## 前提（重要）

本模块只是**客户端桥接**，OCR 引擎由另一个普通 App 提供，需要一起安装：

1. **安装 `MiOcrStandalone` v1.0.3 或更高版本**（独立本地 OCR 引擎，普通 App，无需 root / 无需 LSPosed）。
   它把 `OCREngine` 包成 `content://com.example.miocr.standalone.ocr` 这个 `exported=true`、无权限的 Provider。
   > 该 App 内含小米私有 `libmiocr.so`，出于合规考虑**不上传公开仓库**，请从本地交付包获取。
2. **打开它成功识别一次图**，确认引擎在该机型上可用（首次会初始化 OpenCL / 模型）。
3. 安装本模块 APK，在 LSPosed 里**启用并只勾选作用域** `com.fooview.android.fooview`。
4. **重启 FV 悬浮球**（或重启手机）。

之后直接使用 FV 原来的「自动文字识别」，不需要输入任何命令。

---

## Shell 命令（诊断 / 验证用，日常不需要）

以下命令用于确认独立 OCR 引擎是否就绪，也可供 Termux / Tasker / 其它 App 单独调用文字识别。

### ① 查看引擎状态（不截屏，最快）

```sh
content call --uri content://com.example.miocr.standalone.ocr --method ping
```

关注 `success=true`、`soLoaded=true`、以及 `version` / `abi`。`abi` 必须是 `arm64-v8a`。

### ② 命令行提取当前屏幕文字（截屏走内存管道，不落盘）

```sh
screencap -p | content write --uri content://com.example.miocr.standalone.ocr/ocr_input && content call --uri content://com.example.miocr.standalone.ocr --method ocr_screen
```

从电脑一条命令：

```sh
adb shell 'screencap -p | content write --uri content://com.example.miocr.standalone.ocr/ocr_input && content call --uri content://com.example.miocr.standalone.ocr --method ocr_screen'
```

> Termux 是 app uid，`screencap` 会被拒；有 root 时改用
> `su -c 'screencap -p' | content write ...`。

### ③ 识别已有图片

```sh
content call --uri content://com.example.miocr.standalone.ocr --method ocr_path --extra path:s:/sdcard/Pictures/a.png
```

### ④ 查看模块运行日志

```sh
adb logcat -s FvOcrBridge
```

接管成功会打印 `接管成功，独立 OCR 文本长度=NN`；失败会打印具体原因，例如
`ocr_bitmap 第1次异常: ...`、`引擎返回失败 ...`、
`连续 N 次未取到独立 OCR，30 秒内直接回退 FV 自带识别`。

### 返回字段（`ocr_bitmap` / `ocr_screen` / `ocr_path`）

| key | 类型 | 含义 |
| --- | --- | --- |
| `success` | boolean | 是否成功 |
| `text` | String | 识别出的全文（失败时为空串） |
| `error` | String | 失败原因 |
| `source` | String | `ocr_bitmap` / `ocr_screen` / `ocr_path` 等 |
| `costMs` | long | 耗时 |

`ping` 额外返回 `soLoaded`、`version`、`cacheDir`、`abi`、`authority`、`process`、`libPreload`。

---

## 与 GalleryOCRBridge 的区别

| | GalleryOCRBridge | **FvOcrBridge（本项目）** |
| --- | --- | --- |
| OCR 引擎来源 | Hook 小米相册进程，复用相册私有 `OCREngine` | 独立 App `MiOcrStandalone` 自己带 `libmiocr.so` |
| 是否依赖小米相册 | ✅ 依赖（相册要装、要勾作用域、要能用「提取文字」） | ❌ 完全不依赖 |
| LSPosed 作用域 | 相册 + FV，两个包 | **只有 FV 一个包** |
| 相册被系统精简 / 换版本 | 可能失效 | 不受影响 |
| 对外 shell 命令 | 走 `content://com.miui.gallery.widget` | 走 `content://com.example.miocr.standalone.ocr` |
| 模块是否含 so | 不含 | 不含 |

如果你已经装了 `GalleryOCRBridge` 且相册 OCR 正常，继续用那个即可；
如果相册被精简、相册 OCR 不可用，或想把 OCR 引擎与系统应用彻底解耦，用本模块。

---

## 排障

| 现象 | 处理 |
| --- | --- |
| FV 识别结果和以前一样 / 没变化 | 看 `adb logcat -s FvOcrBridge` 有没有 `接管成功`；没有就先执行 `ping` 确认引擎 App 装了且能识别 |
| `ping` 报 `Can't find provider` / 无返回 | `MiOcrStandalone` 未安装，或装的是旧版（需 ≥ v1.0.3） |
| 日志出现 `ocr_bitmap 第N次异常` | 把异常全文贴出来；常见是引擎 App 的 `:ocr` 进程崩溃（机型不支持 OpenCL/APU），先在引擎 App 界面里单独识图一次确认 |
| 首次识别慢 / 回退到 FV 自带 | `:ocr` 冷启动 + nativeInit 可能数秒，本模块给 10 秒超时并重试 2 次；预热后正常 |
| 连续提示「30 秒内直接回退」 | 引擎连续 2 次不可用已进入冷却，修好引擎后 30 秒自动恢复，或重启 FV 立即恢复 |
| 引擎明明装了还是回退 | 确认 LSPosed 里本模块已启用、作用域勾的是 `com.fooview.android.fooview`、且已重启 FV；另外确认没有同时启用 `GalleryOCRBridge` 的 FV 作用域 |

**两级保险**：引擎成功就用独立 OCR 结果；引擎未装 / 未就绪 / 超时 / 图片无字，
一律 `chain.proceed()` 回退到 FV 自带 PaddleOCR，识别功能不会变坏。

---

## 技术原理

- `META-INF/xposed/java_init.list` 声明入口 `com.fvocr.bridge.ModuleMain`；
  `scope.list` 只写 `com.fooview.android.fooview`；`staticScope=false`、`exceptionMode=protective`。
  `onPackageReady` 严格按包名分流，其它包直接跳过。
- Hook FV 里所有「`(Bitmap) → String`、非抽象」的 `recognize` 方法
  （候选类 `BaiduOCREngine`、`z1/c`、`z1/e`，按签名匹配以兼容混淆差异），
  设 `PRIORITY_HIGHEST`，并对方法 `deoptimize`。
- 跨进程用 `ContentResolver.call()` 把 `Bitmap` 放进 `Bundle`（`putParcelable("bitmap", ...)`），
  走 ashmem，不受 1MB Binder 事务限制；`HARDWARE` / mutable Bitmap 先复制成
  `ARGB_8888` 只读软件副本，用完 `recycle()`。
- Binder 调用放在独立守护线程并限时 10 秒，避免 `:ocr` 冷启动拖死 FV 的工作线程；
  连续失败 2 次进入 30 秒冷却，期间直接回退，防止引擎未装时每次识别都卡顿。
- 独立引擎 App 把 Provider 放在 `:ocr` 子进程，native 崩溃只杀子进程，不会连累 FV。
- 本模块全程只做 Java 层转发，**不加载任何 native 库**。

## 构建

```sh
./gradlew assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`。
GitHub Actions 在 push 到 `main` 时自动构建、打源码包，并创建 / 更新
Release `v1.0.0`（附件：带版本号的 APK 与 src.zip）。

## 许可与说明

仅用于本机自动化、无障碍辅助等个人用途。识别完全在本机离线完成，
本模块不联网、不上传任何内容。OCR 引擎所需的小米私有 `libmiocr.so`
由独立 App 单独提供，**不包含在本仓库中**。
