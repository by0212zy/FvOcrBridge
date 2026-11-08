# 原生库放置说明

本目录在构建时需要三个 `arm64-v8a` 的 so：

| 文件 | 作用 |
| --- | --- |
| `libmiocr.so` | 小米自研 OCR 引擎（MACE + OpenCV + OpenCL/APU，模型内嵌 `.rodata`） |
| `libmiocr_wrapper.so` | JNI 薄壳，导出 `Java_com_xiaomi_ocr_sdk_1ocr_OCREngine_*` |
| `libc++_shared.so` | C++ 运行时 |

**公开源码仓库不含这三个文件**（小米私有二进制，避免直接分发）。
想直接使用请下载 Release 里的成品 APK；想自行构建，请从你自己设备上的
小米相册 APK（`lib/arm64-v8a/`）中提取同名文件放到本目录，再执行
`./gradlew assembleRelease`。

> JNI 用静态命名绑定 `com/xiaomi/ocr/sdk_ocr/OCREngine` 与
> `com/xiaomi/ocr/sdk_ocr/OCRData`，所以这两个 Java 类的包名、类名、字段名
> 都保持原样，不要改，也不要对它们开启混淆。
