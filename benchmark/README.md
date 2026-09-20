# 可复现性能测量

`ReadingBenchmark` 测量应用冷启动和书架/外观设置导航的帧时间。`ReadingBaselineProfile.startup` 只收集启动路径，`localNavigation` 单独收集非启动路径，避免把设置页面放进 startup profile。设置入口不在首屏时会先滚动查找。

`PerformanceScenarioTest` 是独立的离线数据与排版测量：

| 场景 | 测量阶段 |
| --- | --- |
| 1000 本书 | 持久化、重新载入、三种排序、筛选 |
| 10000 章目录 | 持久化、仅目录读取、目录投影、首章/末章的冷内存缓存与热缓存读取 |
| 1 万 / 10 万 / 50 万字 | 正文准备、Android `StaticLayout` 测量及分页 |
| 视口 | 327×640、690×240、327×640 且系统字体缩放 1.5 |

测试正文每段 500 字，字号 20、行距 1.8，测量密度固定为 1。默认重复 3 次；各阶段逐项记录，不设置未经测量的性能合格阈值。测试使用独立 `ContextWrapper` 和随机临时目录，不替换真实书架；结束或失败时刷新写入并删除本次临时数据，不增加 release 导出组件。

PowerShell 7 中运行诊断矩阵（调试构建只用于验证测量流程）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.PerformanceScenarioTest' '-Pandroid.testInstrumentationRunnerArguments.performanceIterations=3'
```

JSON 与文本报告写入应用外部文件目录 `performance/scenarios-<运行ID>.json` / `.txt`，instrumentation 结果返回准确路径。日志标签 `NoveliaPerformance` 会打印当前阶段、字数、视口和重复编号，便于定位耗时阶段。报告包含设备型号、API、ABI、低内存设备标记、RAM、构建版本、是否可调试、每阶段耗时和前后 Java/native heap、PSS。

前后内存快照不等于峰值；测试没有强制 GC，也没有清空操作系统文件缓存。“冷”仅指新建 `LocalStore` 后尚未填充的进程内章节缓存。数据/排版耗时不等于用户可见首帧或滚动流畅度，后两者用 Macrobenchmark 的启动和帧指标评估。

用模拟器验证测试是否完整执行即可。发布性能结论应在同一台低端真机上，保持 release 构建、供电、温度、系统字体、屏幕和编译状态一致，保留完整报告并比较多次分布。生成 Baseline Profile 也不等于已经获得性能提升，必须保留前后实测数据。
