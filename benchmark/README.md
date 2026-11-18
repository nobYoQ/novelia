# 可复现性能测量

2000 本本地书的导入、重复检测、进度保存及重启回归：

```powershell
./scripts/test-large-library.ps1 -Offline -Books 2000 -Iterations 3
```

脚本构建并安装独立 Debug 应用 `cc.novelia.app.librarytest`，不会升级或清空正式应用；同时运行隔离的备份/恢复回归。`LargeLibraryPerformanceTest` 实际生成、导入 2000 个独立的小型 UTF-8 TXT，为全部书目建立进度和历史，测量重载、筛选排序、进度更新与落盘，再重复导入并验证书目和阅读锚点完全不变。暂停导入也验证已完成书目和旧进度保留。夹具结束后删除。

报告和日志取回到项目 `outputs/performance/large-library-<时间>/`。Gradle 缓存位于 `.gradle-home/`，Android 工具偏好位于 `.local/android/`，构建临时文件位于 `outputs/tmp/`，JVM 测试夹具位于 `app/build/tmp/<测试任务>/fixtures/`。设备测试使用设备应用内的隔离目录；脚本不创建或启动本机模拟器。

进度报告记录变更分区字节数、主索引及备用副本的内容字节数，并提供旧式整快照编码的字节数/耗时参考。该参考不包含旧版原子写盘成本，不能据此宣称同倍率的耗时提升。TXT 很小，结果也不能代替插图 EPUB、真实整库备份或 UI 帧时间；Debug 仅用于验证场景和定位热点。

`ReadingBenchmark` 测量应用冷启动和书架/外观设置导航的帧时间，通过「我的 → 设置 → 外观与操作」进入外观页。`ReadingBaselineProfile.startup` 只收集启动路径，`localNavigation` 单独收集非启动路径。

[PerformanceScenarioTest](../app/src/androidTest/java/cc/novelia/app/performance/PerformanceScenarioTest.kt) 位于设备测试的 `performance/` 包，是独立的离线数据与排版测量：

| 场景 | 测量阶段 |
| --- | --- |
| 1000 本书 | 持久化、重新载入、三种排序、筛选 |
| 10000 章目录 | 持久化、仅目录读取、目录投影、首章/末章的冷内存缓存与热缓存读取 |
| 1 万 / 10 万 / 50 万字 | 正文准备、Android `StaticLayout` 测量及分页 |
| 视口 | 327×640、690×240、327×640 且系统字体缩放 1.5 |

测试正文每段 500 字，字号 20、行距 1.8，测量密度固定为 1。默认重复 3 次；各阶段逐项记录，不设置未经测量的性能合格阈值。测试使用独立 `ContextWrapper` 和随机临时目录，不替换真实书架；结束或失败时刷新写入并删除本次临时数据，不增加 release 导出组件。

PowerShell 7 中运行诊断矩阵（调试构建只用于验证测量流程）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.performance.PerformanceScenarioTest' '-Pandroid.testInstrumentationRunnerArguments.performanceIterations=3'
```

JSON 与文本报告写入应用外部文件目录 `performance/scenarios-<运行ID>.json` / `.txt`，instrumentation 结果返回准确路径。日志标签 `NoveliaPerformance` 会打印当前阶段、字数、视口和重复编号，便于定位耗时阶段。报告包含设备型号、API、ABI、低内存设备标记、RAM、构建版本、是否可调试、每阶段耗时和前后 Java/native heap、PSS。

前后内存快照不等于峰值；测试没有强制 GC，也没有清空操作系统文件缓存。“冷”仅指新建 `LocalStore` 后尚未填充的进程内章节缓存。数据/排版耗时不等于用户可见首帧或滚动流畅度，后两者用 Macrobenchmark 的启动和帧指标评估。

用模拟器验证测试是否完整执行即可。发布性能结论应在同一台低端真机上，保持 release 构建、供电、温度、系统字体、屏幕和编译状态一致，保留完整报告并比较多次分布。生成 Baseline Profile 也不等于已经获得性能提升，必须保留前后实测数据。

UI 和 data 拆包后，已清理旧包描述符及相关不稳定的编译器/R8 合成名称。迁移后的覆盖尚待专用设备重新采集，具体状态和命令见 [性能 Profile 维护](../docs/quality/baseline-profiles.md)；现有 Profile 的手工清理不能当作重新测量结果。
