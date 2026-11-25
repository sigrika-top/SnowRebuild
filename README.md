# 雪境重建助手

给《明日方舟》「雪山降临1101」重建小游戏算当前回合走法。提供 Android 悬浮窗和 Windows x64 便携版。

## 下载

| 平台 | 版本 | 文件 |
| --- | --- | --- |
| Android | [android-v0.7](https://github.com/sigrika-top/SnowRebuild/releases/tag/android-v0.7) | [下载 APK](https://github.com/sigrika-top/SnowRebuild/releases/download/android-v0.7/SnowRebuild.apk) |
| Windows x64 | [windows-v0.7](https://github.com/sigrika-top/SnowRebuild/releases/tag/windows-v0.7) | [下载便携 ZIP](https://github.com/sigrika-top/SnowRebuild/releases/download/windows-v0.7/SnowRebuild-0.7-windows-x64.zip) |

## Android

1. 安装 Android APK，打开「雪境重建助手」。
2. 点「开启悬浮窗」，允许显示在其他应用上层。系统询问屏幕共享时，选择整个屏幕。
3. 返回横屏游戏。等本回合的牌和棋盘稳定后，点悬浮窗「分析」，按结果里的顺序操作。
4. 可以拖动悬浮按钮或结果标题。建议在回合开始时分析，路线以「前进」起步。点「关闭」会结束屏幕共享。

## Windows

1. 解压 Windows x64 ZIP，双击其中的 `SnowRebuild.exe`。便携包自带 Java 运行时。
2. 在模拟器或投屏窗口打开重建小游戏。点「框选游戏」，框住整个横屏游戏内容。
3. 每回合点「分析当前画面」。移动或缩放游戏窗口后重新框选。也可以用「打开截图」分析已保存的 PNG 或 JPG。

两版均在本地读取画面。手牌和重建计划需要完整出现在画面里。助手显示走法，由你在游戏里操作。

求解器枚举这一手牌能执行的行动，先比较已显示计划能拿到的分数，再比较剩余计划的完成进度。随机宝箱会计入经过数量。宝箱内容、之后刷出的计划和手牌在画面更新后才能纳入计算。

Android 使用 OpenCV 找资源格，Windows 使用共享的 Java 识别器。两版使用同一套资源图标模板和求解器，取屏时会先移开自己的窗口。

## 构建

需要 JDK 17 或更新版本、Python、Android SDK Platform 35 和 Build Tools 35.0.0。首次构建下载并校验 OpenCV 4.12.0。Windows 下运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Sdk C:\path\to\android-sdk
```

脚本会构建并签名 `dist/SnowRebuild.apk`。第一次构建会在 `build` 目录生成本地签名密钥。APK 更新时应使用同一密钥。

Windows 便携包需要 Windows x64、包含 `jpackage` 的 x64 JDK 17 或更新版本、Python 和 Pillow。

```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
```

便携包会输出到 `dist/SnowRebuild-<版本>-windows-x64.zip`。

## 发布版本

两个平台共用 `main`，各自维护版本号和 Release：

| 平台 | 版本来源 | Release 标签 | 上传文件 |
| --- | --- | --- | --- |
| Android | `AndroidManifest.xml` 中的 `versionName` 和 `versionCode` | `android-v<版本>` | `dist/SnowRebuild.apk` |
| Windows x64 | `desktop/VERSION` | `windows-v<版本>` | `dist/SnowRebuild-<版本>-windows-x64.zip` |

发布时将标签指向构建对应的 `main` 提交，上传该平台的安装包，再更新上方下载表中对应的一行。
