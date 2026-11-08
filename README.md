# FvOcrBridge · FV 识图桥

**一个 APK 搞定**：模块自带小米本地 OCR 引擎（`libmiocr.so` + `OCREngine`），
把 **FV 悬浮球**的「自动文字识别」无感接到这个引擎。

不用装第二个 App、不用小米相册、不 Hook 相册进程、不用下载 FV 的百度语言包，
LSPosed 里**只勾一个作用域** `com.fooview.android.fooview`。

基于 [libxposed 现代 API 102](https://github.com/libxposed/api)：无界面、无桌面图标、
不联网、不修改系统属性与 Settings 数据库、识别全部在本机离线完成。

> 与 `GalleryOCRBridge` **二选一**，不要同时在两个模块里勾选 FV 悬浮球作用域。

---

## v2.0.0 改了什么

v1.0.0 是「模块 + 独立引擎 App」两个安装包，本版按需求合并成**一个 APK**：

| | v1.0.0 | **v2.0.0** |
| --- | --- | --- |
| 安装数量 | 模块 + `MiOcrStandalone`，两个 | **一个** |
| OCR 引擎 | 由另一个 App 的 Provider 提供 | **内置在本模块 APK** |
| authority | `com.example.miocr.standalone.ocr` | `content://com.fvocr.bridge.ocr` |
| Manifest | 纯模块 | 补 26 个 `uses-native-library` + `:ocr` Provider |
| 源码仓库 | 不含 so | 不含 so（成品 APK / Release 附件含） |

引擎代码与三个 so 直接来自已验证可用的独立 demo（v1.0.3），
SHA256 逐字节一致；`OCREngine` / `OCRData` 保持 `com.xiaomi.ocr.sdk_ocr` 包名不变
（JNI 静态绑定硬编码了类名与字段名）。

---

## 一个 APK，两个进程

```
┌─ 本模块 APK (com.fvocr.bridge) ──────────────────────────────┐
│                                                              │
│  ① LSPosed 注入到 FV 进程（纯 Java，不加载任何 native）        │
│     java_init.list → ModuleMain → FvOcrHook                   │
│     Hook recognize(Bitmap)->String                            │
│              │                                               │
│              │  ContentResolver.call()                        │
│              │  Bundle 直传 Bitmap（ashmem，不受 1MB 限制）    │
│              ▼                                               │
│  ② 模块自身 :ocr 子进程（com.fvocr.bridge:ocr）               │
│     OcrProvider → OcrCore → OCREngine.doOCR(Bitmap)           │
│     libmiocr.so / libmiocr_wrapper.so / libc++_shared.so      │
│              │                                               │
│              ▼  Bundle{text}                                 │
│     文本作为 recognize() 的返回值交回 FV                     │
└──────────────────────────────────────────────────────────────┘
```

**为什么把 native 放进 `:ocr` 子进程**：MACE / MNN 初始化时的 glog `CHECK` 失败会
直接 `abort()`（SIGABRT），Java 的 try-catch 拦不住。进程隔离后崩溃只杀
`com.fvocr.bridge:ocr`，FV 悬浮球与模块主进程都不受影响，下一次识别会重新拉起。

**两级保险**：内置引擎成功就用它的结果；引擎未就绪 / 超时 / 返回失败 / 图片无字，
一律 `chain.proceed()` 回退到 FV 自带 PaddleOCR，**识别功能不会变坏**。

---

## 与 GalleryOCRBridge 的区别

| | GalleryOCRBridge | **FvOcrBridge v2.0.0** |
| --- | --- | --- |
| OCR 引擎来源 | Hook 小米相册进程，复用相册私有 `OCREngine` | 本 APK 自带 `libmiocr.so` |
| 是否依赖小米相册 | ✅ 依赖 | ❌ 完全不依赖 |
| LSPosed 作用域 | 相册 + FV，两个包 | **只有 FV 一个包** |
| 安装数量 | 1 个模块 | **1 个模块** |
| 模块是否含 so | 不含 | 含（小米私有，见「合规」） |

相册被精简、相册「提取文字」不可用，或不想 Hook 系统应用时，用本模块。

---

## 前提

1. **arm64-v8a 设备**，且系统提供 OpenCL / vendor 原生库（小米 / HyperOS 机型）。
   Manifest 已声明 26 个 `uses-native-library`（`required=false`），
   并在 `nativeInit` 前主动预加载 `libOpenCL.so`，否则 MACE 会因
   `No OpenCL platforms found` 直接 abort。
2. **LSPosed**（支持 libxposed API 102）与 **FV 悬浮球** `com.fooview.android.fooview`。
3. 桌面**不会出现图标**——这是正常的，本模块没有 Activity。

## 安装（三步）

1. 安装本 APK（Release 附件 `FvOcrBridge-v2.0.0-release.apk`）。
2. 打开 LSPosed → 模块 → 启用 **FV 识图桥**，作用域**只勾** `com.fooview.android.fooview`。
3. 重启 FV 悬浮球（或重启手机）。之后直接用 FV 原来的「自动文字识别」，**不需要任何命令**。

建议装完先跑一次下面的 `self_test`，确认引擎在这台机器上可用。

---

## Shell 命令（诊断 / 命令行识别）

日常用 FV 不需要输入命令。以下命令用于验证引擎，也可供 Termux / Tasker / 其它 App 单独调用 OCR。

### ① 内置测试图自检（最推荐，不需要外部图片）

```sh
content call --uri content://com.fvocr.bridge.ocr --method self_test
```

一步完成 `init` + 推理并返回识别到的文字。返回 `success=true` 且 `text` 有内容即引擎可用。
从电脑：`adb shell content call --uri content://com.fvocr.bridge.ocr --method self_test`

### ② 引擎状态（不进推理）

```sh
content call --uri content://com.fvocr.bridge.ocr --method ping
```

关注 `success`、`soLoaded`、`abi`（应为 `arm64-v8a`）、`authority`、`process=:ocr`。

### ③ vendor 库探测（引擎起不来时先查这个）

```sh
content call --uri content://com.fvocr.bridge.ocr --method probe_libs
```

纯文件读取 + 主动预加载 `libOpenCL.so`，返回 `report` / `preload`。
若 `preload` 全是 `✗`，说明该机型的 vendor 库对本进程不可访问。

### ④ 提取当前屏幕文字（截屏走内存管道，不落盘）

```sh
screencap -p | content write --uri content://com.fvocr.bridge.ocr/ocr_input && content call --uri content://com.fvocr.bridge.ocr --method ocr_screen
```

从电脑一条命令：

```sh
adb shell 'screencap -p | content write --uri content://com.fvocr.bridge.ocr/ocr_input && content call --uri content://com.fvocr.bridge.ocr --method ocr_screen'
```

> Termux 是 app uid，`screencap` 会被拒；有 root 时改用 `su -c 'screencap -p' | content write ...`。

### ⑤ 识别已有图片

```sh
content call --uri content://com.fvocr.bridge.ocr --method ocr_path --extra path:s:/sdcard/Pictures/a.png
```

### ⑥ 分步诊断（定位 native 崩在哪一步）

```sh
content call --uri content://com.fvocr.bridge.ocr --method diag_reset
content call --uri content://com.fvocr.bridge.ocr --method diag_step --arg 0   # vendor 库 / 预加载 OpenCL
content call --uri content://com.fvocr.bridge.ocr --method diag_step --arg 1   # System.loadLibrary + so .init_array
content call --uri content://com.fvocr.bridge.ocr --method diag_step --arg 2   # nativeInit(cacheDir, null)
content call --uri content://com.fvocr.bridge.ocr --method diag_step --arg 3   # nativeGetVersion
content call --uri content://com.fvocr.bridge.ocr --method diag_step --arg 4   # doOCR(内置测试图)
```

每步一次 Binder 调用、每步前后同步刷盘；`:ocr` 子进程若崩，看最后一条 `STEP:` 就知道死在哪。

### ⑦ 日志

```sh
adb logcat -s FvOcrBridge
```

接管成功会打印 `接管成功，独立 OCR 文本长度=NN`；失败会打印具体原因，例如
`ocr_bitmap 第1次异常: ...`、`引擎返回失败 ...`、连续失败后的冷却提示。

### 返回字段

| key | 类型 | 含义 |
| --- | --- | --- |
| `success` | boolean | 是否成功 |
| `text` | String | 识别出的全文（失败时为空串） |
| `error` | String | 失败原因 |
| `source` | String | `ocr_bitmap` / `self_test` / `ocr_screen` / `ocr_path` 等 |
| `costMs` | long | 耗时 |

`ping` 额外返回 `soLoaded`、`version`、`cacheDir`、`abi`、`authority`、`process`、`libPreload`。

---

## 排障

| 现象 | 处理 |
| --- | --- |
| `self_test` 返回 `success=false` | 先跑 `probe_libs` 看 OpenCL 是否可加载；再按 `diag_step 0→4` 定位 |
| `probe_libs` 的 `preload` 全是 `✗` | 机型 vendor 库不可访问 / 非小米 ROM，引擎在此设备跑不起来；FV 会自动回退自带识别 |
| FV 识别结果没变化 | 看 `adb logcat -s FvOcrBridge` 有没有 `接管成功`；确认模块已启用、作用域勾的是 FV、已重启 FV |
| 首次识别慢 / 回退 | `:ocr` 冷启动 + `nativeInit` 可能数秒，桥接给 10 秒超时并重试 2 次，预热后正常 |
| 连续提示「30 秒内直接回退」 | 引擎连续 2 次不可用进入冷却，修好后 30 秒自动恢复，或重启 FV 立即恢复 |
| 装完桌面没图标 | 正常，本模块无 Activity；在 LSPosed 模块列表里管理 |

---

## 技术原理

- `META-INF/xposed/java_init.list` 声明入口 `com.fvocr.bridge.ModuleMain`；
  `scope.list` 只写 `com.fooview.android.fooview`；`staticScope=false`、`exceptionMode=protective`。
  `onPackageReady` 严格按包名分流，其它包直接跳过。
- Hook FV 里所有「`(Bitmap) → String`、非抽象」的 `recognize` 方法
  （候选类 `BaiduOCREngine`、`z1/c`、`z1/e`，按签名匹配以兼容混淆差异），
  `PRIORITY_HIGHEST` + `deoptimize`。
- 跨进程 `ContentResolver.call()` 把 Bitmap 放进 Bundle（`putParcelable("bitmap", ...)`），
  走 ashmem，不受 1MB Binder 事务限制；`HARDWARE` / mutable Bitmap 先复制成
  `ARGB_8888` 只读软件副本，用完 `recycle()`。
- Binder 调用放独立守护线程限时 10 秒，避免 `:ocr` 冷启动拖死 FV 工作线程；
  连续失败 2 次进入 30 秒冷却。
- OCR 引擎：`OCREngine.getInstance().init(cacheDir, null).doOCR(bitmap)` →
  `OCRData.OCRResult.total_text`。`libmiocr.so` 模型内嵌 `.rodata`，不需要额外模型文件。
- `uses-native-library` 26 个声明 + `LibProbe.preloadOpenCL()` 是 Android 12+ 能跑起来的关键，
  缺了会 `No OpenCL platforms found` → `SIGABRT`。
- `packaging.jniLibs.useLegacyPackaging = true`：so 解压到 `nativeLibraryDir`，
  便于引擎按路径 dlopen 与写 OpenCL 编译缓存。

## 构建

**公开源码仓库不含三个小米私有 so**（见 `app/src/main/jniLibs/arm64-v8a/README.md`）。
直接从 Release 下载成品 APK 即可使用；若要自行构建：

1. 从你自己设备上的小米相册 APK 的 `lib/arm64-v8a/` 提取
   `libmiocr.so`、`libmiocr_wrapper.so`、`libc++_shared.so` 放到
   `app/src/main/jniLibs/arm64-v8a/`；
2. 执行 `./gradlew assembleRelease`，产物 `app/build/outputs/apk/release/app-release.apk`。

GitHub Actions 在 push 到 `main` 时做 **Java 编译校验**（因为仓库不含 so，不产出完整 APK）；
带 so 的成品 APK 与源码包在 Release 附件里。

## 许可与合规说明

仅用于本机自动化、无障碍辅助等个人用途，识别完全离线、不上传任何内容。
`libmiocr.so` / `libmiocr_wrapper.so` 是小米私有二进制，**不随公开源码仓库分发**；
成品 APK 内包含它们，请自行评估使用场景，不要二次分发。
`OCREngine` / `OCRData` 为接口复刻，仅用于调用设备上已有的引擎能力。
