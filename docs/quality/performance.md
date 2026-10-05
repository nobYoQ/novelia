# 性能测量

[质量目录](README.md) · [文档首页](../README.md)

先确定要测的是数据处理、排版、启动还是帧时间，再选工具。缓存命中率或纯函数耗时不能直接代表用户看到的流畅度。

## 三种工具

| 工具 | 回答什么问题 |
| --- | --- |
| [PerformanceScenarioTest](../../app/src/androidTest/java/cc/novelia/app/performance/PerformanceScenarioTest.kt) | 大书架、长目录和正文投影/排版的开销在哪里 |
| [ReadingBenchmark](../../benchmark/src/main/java/cc/novelia/benchmark/ReadingBenchmark.kt) | 冷启动和书架/设置导航的启动、帧指标 |
| Baseline Profile 采集 | 给运行时和启动布局提供热点路径，随后还需测量收益 |

离线场景使用合成数据和隔离目录，包含 1,000 本书、10,000 章目录及 1 万/10 万/50 万字正文。分别记录写入、读取、筛选、投影、StaticLayout 和分页，不用真实书库做夹具。

```powershell
./build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.performance.PerformanceScenarioTest', '-Pandroid.testInstrumentationRunnerArguments.performanceIterations=3')
```

报告写到设备应用外部目录的 `performance/scenarios-<运行ID>.json` 和 `.txt`，instrumentation 返回路径。它记录设备、API、ABI、版本、耗时和内存快照。Debug 结果适合定位热点，不作为正式包性能结论。

## 启动和帧时间

benchmark 模块最低 API 28。`ReadingBenchmark` 使用 `CompilationMode.Partial`，在可用时使用 Profile，重复测量冷启动和书架/设置导航。

```powershell
# 专用设备上的本地测试签名，仅用于测量
./build.ps1 -Tasks @(':benchmark:connectedBenchmarkReleaseAndroidTest', '-PlocalReleaseSigning=true', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.benchmark.ReadingBenchmark')
```

任务由当前插件和变体配置产生；变更构建配置后用 `:benchmark:tasks --all` 确认。保留报告和 trace，不根据一次模拟器运行宣称真机加速。

## 正确解读数字

前后对比使用同一设备、API、ABI、字体缩放、视口、供电/温度、数据、构建类型和编译模式，记录提交及是否带 Profile。比较多次分布，不只选最快结果。

测试中的“冷缓存”指新建 LocalStore 后的进程内缓存，不是清除系统文件缓存；前后内存快照也不是峰值。虚拟时间的并发测试验证请求数量和取消规则，不证明真实吞吐提升。

当前值得关注的路径包括元数据热缓存、章节批量缓存、两路下载调度、正文投影和分页测量。先用对应场景验证假设，再调整算法；不要通过取消校验或无限缓存改善数字。

## Profile 与提交结果

生成方法及源码移动后的处理见 [Profile 维护](baseline-profiles.md)。普通构建关闭自动采集，手工过滤失效描述符不等于重新采集。

PR 应附原始报告、测量条件和结论适用范围，同时保留缓存失效、账号切换、取消与阅读锚点等正确性回归。更多场景参数见 [benchmark README](../../benchmark/README.md)。
