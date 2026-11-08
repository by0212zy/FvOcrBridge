# FvOcrBridge · FV 识图桥

**一个 APK 搞定**：模块自带小米本地 OCR 引擎（`libmiocr.so` + `OCREngine`），
把 **FV 悬浮球**的「自动文字识别」无感接到这个引擎。

不用装第二个 App、不用小米相册、不 Hook 相册进程、不用下载 FV 的百度语言包，
LSPosed 里**只勾一个作用域** `com.fooview.android.fooview`。

基于 [libxposed 现代 API 102](https://github.com/libxposed/api)：不联网、
不修改系统属性与 Settings 数据库、识别全部在本机离线完成。
默认带一个**可隐藏的桌面图标**（用于激活，见下文）。

> 与 `GalleryOCRBridge` **二选一**，不要同时在两个模块里勾选 FV 悬浮球作用域。

---

## ⚠️ v2.0.2：修复「v2.0.0 / v2.0.1 都无效」

**这不是代码 bug，是 HyperOS / MIUI 的自启动管控，而且比第一轮判断更深一层。**

### 第一轮（v2.0.0，`LSPosed_20261108_153319.zip`）

```
WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject
  caller=com.fooview.android.fooview callee=com.fvocr.bridge
  classname=com.fvocr.bridge.OcrProvider wakeType=4
ContentProviderHelper: Unable to launch app for com.fvocr.bridge.ocr
ActivityThread: Failed to find provider info for com.fvocr.bridge.ocr
FvOcrBridge: ocr_bitmap 第1次异常: java.lang.IllegalArgumentException:
             Unknown authority com.fvocr.bridge.ocr
```

当时的结论是「FV 跨应用拉起 `:ocr` 进程被拦」，于是 v2.0.1 加了前台服务、
自激活广播、设置页，想让 `:ocr` 进程先由模块自己拉起来。

### 第二轮（v2.0.1，`LSPosed_20261108_170719.zip`）—— v2.0.1 为什么也没用

用户手工执行激活命令，输出看起来「成功」：

```
Broadcasting: Intent { act=com.fvocr.bridge.WAKE flg=0x400020 cmp=com.fvocr.bridge/.BootReceiver }
Broadcast completed: result=0
```

但 **`result=0` 只表示广播队列处理完毕，不代表 Receiver 执行过**。真机日志里：

```
BroadcastQueueInjector: Unable to launch app com.fvocr.bridge/10610 for broadcast
  Intent { act=android.intent.action.MY_PACKAGE_REPLACED ... }:
  process is not permitted to auto start

WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject
  caller=com.fooview.android.fooview callee=com.fvocr.bridge
  classname=com.fvocr.bridge.OcrProvider  action=null                    wakeType=4
  classname=com.fvocr.bridge.OcrService   action=null                    wakeType=8
  classname=com.fvocr.bridge.BootReceiver action=com.fvocr.bridge.WAKE   wakeType=2

BroadcastQueueInjector: Unable to launch app com.fvocr.bridge/10610
  for broadcast Intent { act=com.fvocr.bridge.WAKE ... }:
  process is not permitted to wake path

MST_PackageCache: com.fvocr.bridge has not launcher icon,return
```

三个硬事实：

1. **整个包被禁止后台冷启动** —— 连系统发给应用自己的 `MY_PACKAGE_REPLACED`
   都被拦，Service / Provider / Broadcast 三条唤醒路径全部 Reject。
2. **v2.0.1 存在无解的循环依赖**：Provider、Service、Receiver 全在 `:ocr` 子进程，
   而「激活 `:ocr`」这件事本身又需要 `:ocr` 进程先启动。全日志 `com.fvocr.bridge:ocr`
   进程 **0 次启动**，`SettingsActivity` 也 **0 次启动**。
3. **无 LAUNCHER 图标**导致 MIUI 自启动管理列表直接跳过本应用
   （`has not launcher icon,return`），用户连手动放行都找不到入口。

### v2.0.2 的解法

日志标题写得很明确：**`Service/Provider/Broadcast Reject`** ——
显式 `startActivity` 不在这道管控里（同份日志里多次 `START ... result code=0`）。
于是：

| v2.0.0 / v2.0.1 | **v2.0.2** |
| --- | --- |
| Provider / Service / Receiver 全在 `:ocr` 子进程 | **全部收回主进程**，链路缩到最短：主进程一起 = Provider 已发布 + 前台服务常驻 |
| 激活 `:ocr` 又需要 `:ocr` 先起来 → 死循环 | 无第二次进程冷启动，不再依赖「应用拉起自己子进程」这条未经验证的路径 |
| 靠广播 / 服务 / 设置页激活 → 全被 `WakePathChecker` 拦 | 新增**透明 `WakeActivity` + 桌面图标**，显式 Activity 启动绕过管控 |
| 无图标，MIUI 自启动管理不列出本应用 | 有 LAUNCHER，可手动放行自启动；设置页里可**一键隐藏图标** |
| `am broadcast ... WAKE`（`result=0` 是假成功） | `am start -n com.fvocr.bridge/.WakeActivity` |

`ContentProviderHelper` **只在需要拉起进程时**才做唤醒检查。主进程一旦常驻，
FV 的 `content call` 走「进程已运行」分支直接返回 Provider，不再触发任何管控。
`WakeActivity` 内同应用、同进程直启 `OcrService` 前台保活 —— caller == callee
且由前台 Activity 发起，既不受关联启动管控，也满足 Android 12+ 对后台
`startForegroundService` 的限制。

> **代价**：放弃 `:ocr` 子进程的 native 崩溃隔离。`MiOcrStandalone v1.0.3` 已经修掉
> 导致 MACE `abort()` 的 OpenCL / `uses-native-library` namespace 根因，引擎稳定；
> 且 `OcrProvider.onCreate()` 仍零 native，只有真正识别时才加载 so。

---

## 安装（四步，第 3 步是关键）

1. 安装本 APK（Release 附件 `FvOcrBridge-v2.0.2-release.apk`）。
2. LSPosed → 模块 → 启用 **FV 识图桥**，作用域**只勾** `com.fooview.android.fooview`。
3. **激活引擎进程（必须做一次）**：在桌面点一下 **「FV 识图桥」** 图标
   （会弹出引擎状态页）。或者执行：
   ```sh
   am start -n com.fvocr.bridge/.WakeActivity
   ```
   页面点一下「② 运行自检」，返回 `✓ 引擎可用` 且 `text` 有内容即全部就绪。
4. 重启 FV 悬浮球（或重启手机）。之后直接用 FV 原来的「自动文字识别」，
   **日常不需要任何命令**。

> 桌面图标是这次修复的**技术手段**（让应用出现在 MIUI 自启动管理列表 + 给用户一条
> 显式 Activity 入口）。不想要图标：设置页点「⑤ 隐藏桌面图标」即可，
> 隐藏后仍可用 `am start` 命令或 LSPosed 模块设置页激活。

### 引擎激活的四种方式（任选其一）

| 方式 | 操作 | 说明 |
| --- | --- | --- |
| **① 桌面图标（推荐）** | 点一下「FV 识图桥」 | 一次显式 Activity 启动，主进程即常驻，顺带打开状态页 |
| **② shell 命令** | `am start -n com.fvocr.bridge/.WakeActivity` | adb / Termux / MT 终端，等价的一次性激活 |
| **③ shell `content call`** | `content call --uri content://com.fvocr.bridge.ocr --method ping` | 从 adb shell 发起的 Provider 调用也会拉起主进程，可顺带验证状态 |
| **④ 自启动权限（一劳永逸）** | 系统设置 → 应用设置 → 授权管理 → 自启动管理 → 允许「FV 识图桥」 | 放行后开机由 `BOOT_COMPLETED` 自动激活；设置页「④」有跳转按钮 |

激活后 `OcrService` 以前台服务把主进程保活（静默低优先级通知）；
只要进程在线，FV 的 `content call` 就不会再触发唤醒拦截。
FV 侧的 Hook 在检测到 `Unknown authority` 时也会尝试**显式启动透明 Activity**
自动激活并重试，但 Android 10+ 对后台启动 Activity 有 BAL 限制，
FV 若仅在后台工作可能失败 —— **最可靠的仍是 ① / ② / ④**。

---

## 版本历史

| 版本 | 变化 |
| --- | --- |
| v1.0.0 | 「模块 + 独立引擎 App」两个安装包，转发到 `MiOcrStandalone` 的 Provider |
| v2.0.0 | 按需求合并成**一个 APK**，引擎与三个 so 直接内置（来自已验证的 v1.0.3，SHA256 一致） |
| v2.0.1 | 针对第一轮 `Unknown authority` 加前台服务 / 广播 / 设置页 —— 实测**仍被 MIUI 全拦** |
| **v2.0.2** | 组件收回主进程 + 显式 Activity 激活 + 可隐藏桌面图标，解决循环依赖 |

引擎代码与三个 so 直接来自独立 demo `MiOcrStandalone v1.0.3`（用户真机确认可用），
SHA256 逐字节一致；`OCREngine` / `OCRData` 保持 `com.xiaomi.ocr.sdk_ocr` 包名不变
（JNI 静态绑定硬编码了类名与字段名）。

---

## 架构（v2.0.2：单进程）

```
┌─ 本模块 APK (com.fvocr.bridge) ──────────────────────────────────┐
│                                                                 │
│  ① LSPosed 注入 FV 进程（纯 Java，不加载任何 native）             │
│     java_init.list → ModuleMain → FvOcrHook                      │
│     Hook recognize(Bitmap)->String                               │
│              │ ContentResolver.call() / Bundle 直传 Bitmap        │
│              ▼ （要求模块主进程已在线，否则 MIUI 会拦冷启动）      │
│  ② 模块【主进程】com.fvocr.bridge                                 │
│     OcrProvider → OcrCore → OCREngine.doOCR(Bitmap)              │
│     libmiocr.so / libmiocr_wrapper.so / libc++_shared.so         │
│     OcrService（前台常驻）  BootReceiver（兼容自激活）            │
│     WakeActivity（透明显式激活入口）  SettingsActivity（状态页）    │
│              ▼ Bundle{text}                                      │
│     文本作为 recognize() 的返回值交回 FV                        │
│                                                                 │
│  ③ 桌面图标 LauncherAlias → WakeActivity                         │
│     点一次 = 显式 startActivity = 主进程激活（可在设置页隐藏）     │
└─────────────────────────────────────────────────────────────────┘
```

**为什么 v2.0.2 不再用 `:ocr` 子进程**：v2.0.0 / v2.0.1 把 native 隔离到子进程，
本意是 MACE `abort()` 时不连累其它进程；但 MIUI 禁止本包一切后台冷启动后，
「拉起 `:ocr`」成了永远完不成的前置条件。收回主进程后链路最短、确定性最高；
导致崩溃的 namespace 根因已在 v1.0.3 修复。

**两级保险**：内置引擎成功就用它的结果；引擎未就绪 / 超时 / 返回失败 / 图片无字，
一律 `chain.proceed()` 回退到 FV 自带 PaddleOCR，**识别功能不会变坏**。

---

## 与 GalleryOCRBridge 的区别

| | GalleryOCRBridge | **FvOcrBridge v2.0.2** |
| --- | --- | --- |
| OCR 引擎来源 | Hook 小米相册进程，复用相册私有 `OCREngine` | 本 APK 自带 `libmiocr.so` |
| 是否依赖小米相册 | ✅ 依赖 | ❌ 完全不依赖 |
| LSPosed 作用域 | 相册 + FV，两个包 | **只有 FV 一个包** |
| 安装数量 | 1 个模块 | **1 个模块** |
| 模块是否含 so | 不含 | 含（小米私有，见「合规」） |
| MIUI 关联启动 | 借道系统白名单相册，不受影响 | 需**点一次桌面图标 / `am start` 激活** |

相册被精简、相册「提取文字」不可用，或不想 Hook 系统应用时，用本模块。

---

## 前提

1. **arm64-v8a 设备**，且系统提供 OpenCL / vendor 原生库（小米 / HyperOS 机型）。
   Manifest 已声明 26 个 `uses-native-library`（`required=false`），
   并在 `nativeInit` 前主动预加载 `libOpenCL.so`，否则 MACE 会因
   `No OpenCL platforms found` 直接 abort。
2. **LSPosed**（支持 libxposed API 102）与 **FV 悬浮球** `com.fooview.android.fooview`。
3. **装完必须激活一次**（见上面「安装」第 3 步）。

---

## Shell 命令

### ⓪ 激活（装完 / 重启后失效时执行一次）

```sh
am start -n com.fvocr.bridge/.WakeActivity
```

`WakeActivity` 透明、立即结束，只做一件事：同应用直启 `OcrService` 前台保活。
从电脑：`adb shell am start -n com.fvocr.bridge/.WakeActivity`
不带 `silent` 参数时会弹一句提示并打开设置页；FV 侧自动兜底会带 `silent=true`，完全静默。

### ① 内置测试图自检（最推荐，不需要外部图片）

```sh
content call --uri content://com.fvocr.bridge.ocr --method self_test
```

一步完成 `init` + 推理并返回识别到的文字。返回 `success=true` 且 `text` 有内容即引擎可用。
从电脑：`adb shell content call --uri content://com.fvocr.bridge.ocr --method self_test`

> 这条命令从 adb shell 发起时也会把主进程拉起来，可当作一次性激活手段。

### ② 引擎状态（不进推理）

```sh
content call --uri content://com.fvocr.bridge.ocr --method ping
```

关注 `success`、`soLoaded`、`abi`（应为 `arm64-v8a`）、`authority`、`process=main`。

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

每步一次 Binder 调用、每步前后同步刷盘；主进程若崩，看最后一条 `STEP:` 就知道死在哪。

### ⑦ 日志

```sh
adb logcat -s FvOcrBridge
```

接管成功会打印 `接管成功，独立 OCR 文本长度=NN`；失败会打印具体原因。
激活成功会打印 `WakeActivity 启动（显式 Activity 路径，主进程），激活引擎`
与 `OcrService: 主进程已前台常驻`。

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
| 日志 `Unknown authority com.fvocr.bridge.ocr` / `Failed to find provider info` | **MIUI 关联启动拦截**：主进程没起来。点一次桌面图标，或执行 `am start -n com.fvocr.bridge/.WakeActivity`；长期方案是在系统自启动管理里允许本应用 |
| `am broadcast ... WAKE` 返回 `Broadcast completed: result=0` 但仍无效 | `result=0` 只表示广播队列处理完，Receiver 根本没执行。**别再用广播激活**，改用 `am start` 或点图标 |
| `WakePathChecker: ... Reject` / `process is not permitted to auto start` | 同上，这是系统日志里的同一件事；v2.0.2 用显式 Activity 绕开 |
| 识别结果与以前一样（一直回退） | 先看 `adb logcat -s FvOcrBridge` 有没有 `接管成功`；确认模块已启用、作用域只勾 FV、已重启 FV、引擎已激活 |
| 激活后过一阵又失效 | 主进程被系统回收且再次拉起被拦；到系统自启动管理里允许本应用，开机自动激活，或再点一次桌面图标 |
| `self_test` 返回 `success=false` | 先跑 `probe_libs` 看 OpenCL 是否可加载；再按 `diag_step 0→4` 定位 |
| `probe_libs` 的 `preload` 全是 `✗` | 机型 vendor 库不可访问 / 非小米 ROM，引擎在此设备跑不起来；FV 会自动回退自带识别 |
| 首次识别慢 / 回退 | 主进程冷启动 + `nativeInit` 可能数秒，桥接给 12 秒超时并重试 3 次，预热后正常 |
| 连续提示「30 秒内直接回退」 | 引擎连续 2 次不可用进入冷却，修好后 30 秒自动恢复，或重启 FV 立即恢复 |
| 不想在桌面看到图标 | 设置页点「⑤ 隐藏桌面图标」；隐藏后仍可用 `am start` 命令或 LSPosed 设置页激活 |

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
- Binder 调用放独立守护线程限时 12 秒，避免冷启动拖死 FV 工作线程；
  连续失败 2 次进入 30 秒冷却。
- **v2.0.2 唤醒修复**：Provider / Service / Receiver 全部收回主进程；
  `WakeActivity`（透明、exported）由桌面图标 `LauncherAlias` 或 `am start -n` 显式启动，
  内部同应用直启 `OcrService` 前台保活；`SettingsActivity.wake()` 同样直启服务；
  FV 侧捕获 `Unknown authority` 时优先尝试显式 `startActivity(WakeActivity)`
  （`silent=true`、60 秒节流），服务与广播仅作补充，失败静默回退。
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

GitHub Actions 在 push 到 `main` 时做 **Java / 资源编译校验**（因为仓库不含 so，不产出完整 APK）；
带 so 的成品 APK 与源码包在 Release 附件里。

## 许可与合规说明

仅用于本机自动化、无障碍辅助等个人用途，识别完全离线、不上传任何内容。
`libmiocr.so` / `libmiocr_wrapper.so` 是小米私有二进制，**不随公开源码仓库分发**；
成品 APK 内包含它们，请自行评估使用场景，不要二次分发。
`OCREngine` / `OCRData` 为接口复刻，仅用于调用设备上已有的引擎能力。
