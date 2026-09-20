# 性能测量

[返回开发手册](README.md)

性能修改要有可复现的前后数据，并注明测量边界。工程提供三种互补工具：离线数据/排版场景、Macrobenchmark、Baseline Profile 采集。实现和现有测量定义见 [benchmark/README.md](../benchmark/README.md)。

## 离线数据与排版场景

[PerformanceScenarioTest.kt](../app/src/androidTest/java/cc/novelia/app/performance/PerformanceScenarioTest.kt) 在独立临时目录中生成数据，使用 ContextWrapper 隔离数据路径，测量后刷新并清理本次夹具。它没有使用真实书库作为性能数据源。

| 场景 | 分别测量 |
| --- | --- |
| 1000 本书 | 持久化、重新载入、排序、筛选 |
| 10000 章目录 | 持久化、目录读取与投影、首末章冷/热进程内缓存读取 |
| 1 万 / 10 万 / 50 万字 | 正文准备、StaticLayout 测量和分页 |
| 多视口 | 327×640、690×240、327×640 且字体缩放 1.5 |

在专用已连接设备上运行：

```powershell
./build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.performance.PerformanceScenarioTest', '-Pandroid.testInstrumentationRunnerArguments.performanceIterations=3')
```

此命令用于验证测量流程和定位热点，Debug 构建结果不能直接作为正式包性能结论。报告写到应用外部文件目录 `performance/scenarios-<运行ID>.json` 和 `.txt`，instrumentation 返回实际路径，`NoveliaPerformance` 日志标记阶段。保留原始报告后再做对比。

报告包含设备、API、ABI、RAM、低内存设备标记、构建版本和调试状态，以及阶段耗时、Java/native heap 和 PSS。前后内存快照不等于峰值；测试不强制 GC，也不清系统文件缓存。“冷缓存”仅指新建 `LocalStore` 后的进程内缓存状态。

## 启动与帧测量

[ReadingBenchmark.kt](../benchmark/src/main/java/cc/novelia/benchmark/ReadingBenchmark.kt) 中：

- `ReadingBenchmark.coldStart`：冷启动，等待书架入口，收集启动和帧时间，重复 5 次。
- `ReadingBenchmark.shelfAndSettings`：书架滚动、进入阅读与外观设置、返回，收集帧时间，重复 5 次。
- 两者使用 `CompilationMode.Partial`，在可用时采用 Baseline Profile。

benchmark 模块要求 API 28 及以上。采集使用专用设备与可安装的目标变体；普通 Release 默认未签名，需配置正式签名或仅用于本地测量的测试签名。后者不是可公开分发的正式包。

插件提供的具体变体任务可以通过以下命令确认：

```powershell
./build.ps1 -Tasks @(':benchmark:tasks', '--all')
```

选择 `ReadingBenchmark` 类运行对应 benchmark 变体，保留 Gradle 输出提示的报告和 trace。若设备不满足工具要求，记录限制并换用合适设备；不要通过忽略校验将模拟器结果包装成真机性能结果。

当前配置提供 `connectedBenchmarkReleaseAndroidTest`。仅用于专用测试设备的 Debug 证书签名示例：

```powershell
./build.ps1 -Tasks @(':benchmark:connectedBenchmarkReleaseAndroidTest', '-PlocalReleaseSigning=true', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.benchmark.ReadingBenchmark')
```

## Baseline Profile

已提交的采集文件位于 `app/src/release/generated/baselineProfiles/`，状态与操作见 [Profile 维护](baseline-profiles.md)。UI 与 data 包、文件拆分后，已移除旧 UI、旧 data、主 Activity 相关描述符，并清理 NoveliaApplication 中不稳定的 R8 合成名称，保留其余依赖及未迁移代码规则。当前未连接采集设备，迁移后的 UI 与数据层覆盖需要在专用设备上重新采集；不能把构建通过或手工过滤视作性能测量完成。

`ReadingBaselineProfile.startup` 只收集启动路径，并设置 `includeInStartupProfile=true`；`localNavigation` 收集本地导航和设置，明确不纳入 startup profile。这样启动 DEX 布局与非启动路径的 profile 责任保持清楚。

当前 [app 配置](../app/build.gradle.kts) 设置 `automaticGenerationDuringBuild=false`；[benchmark 配置](../benchmark/build.gradle.kts) 设置 `useConnectedDevices=true`。普通构建不会自动启动设备生成 profile。

查看可用生成任务：

```powershell
./build.ps1 -Tasks @(':app:tasks', '--all')
```

生成后审查实际输出的 profile 及关联源码，检查启动条目没有混入设置等非启动路径，按项目构建任务提示集成。生成成功只说明采集流程完成，是否改善启动/滚动仍需对同样条件下的构建测量。

当前配置提供 `generateReleaseBaselineProfile`，同样需要满足插件要求的专用设备：

```powershell
./build.ps1 -Tasks @(':app:generateReleaseBaselineProfile', '-PlocalReleaseSigning=true')
```

这些任务名已通过当前 Gradle 任务列表核对；实际采集是否成功仍以设备执行结果为准。

## 如何提交性能改动

先写出假设，例如“目录展开会重复解析完整正文”，再用相应场景定位。修改前后使用同一设备、API、ABI、供电、温度、字体缩放、视口、编译模式和数据。记录提交标识、版本、签名/构建类型、是否附带 profile，比较多次分布，避免只报最快一次。

数据解析耗时、分页耗时、首次可交互时间和滚动帧时间分别解释；不要用某一个数替代整体体验。性能优化也必须保留正确性回归，尤其是缓存代次、取消、账号切换和阅读锚点。不要为了减少耗时跳过校验或引入无限缓存。
