# Baseline Profile 维护

[质量目录](README.md) · [性能测量](performance.md)

已提交数据在 [app/src/release/generated/baselineProfiles](../../app/src/release/generated/baselineProfiles)，普通 Release 使用它们，但不会自动重新采集。目录只放 Profile 数据，说明文件放在本页。

## 采集

在符合工具要求的专用设备上运行：

```powershell
./build.ps1 -Tasks @(':app:generateReleaseBaselineProfile', '-PlocalReleaseSigning=true')
```

`ReadingBaselineProfile.startup` 收集启动路径并加入 startup profile；`localNavigation` 收集本地导航，不加入 startup profile。配置分别见 [app](../../app/build.gradle.kts)、[benchmark](../../benchmark/build.gradle.kts) 和[采集代码](../../benchmark/src/main/java/cc/novelia/benchmark/ReadingBenchmark.kt)。

审查生成文件，确保启动规则没有混入非启动操作，再提交。采集成功后仍需同设备、同条件比较启动与帧指标。

## 源码移动后的处理

Kotlin 包、文件生成类、Compose/R8 合成名称变化会使旧描述符失效。仅更新 imports 不会更新 Profile；不要手工猜测新的 lambda 名称。

仓库此前已清理 UI/data 拆包后的旧描述符及部分不稳定合成名称，迁移后的覆盖仍需专用设备重新采集。本轮文档更新未重新采集，也不把手工清理视为性能提升。后续重录后应更新本段状态。
