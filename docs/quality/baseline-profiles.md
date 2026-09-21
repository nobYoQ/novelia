# 性能 Profile 的维护

[返回质量验证索引](README.md) · [文档总目录](../README.md) · [性能测量](performance.md)

`app/src/release/generated/baselineProfiles/` 中的 `baseline-prof.txt` 和 `startup-prof.txt` 是已提交的采集产物，普通 Release 构建会使用它们；自动重新采集处于关闭状态。该目录仅放 Profile 数据，构建工具会读取其中的文件，不能把 README 等普通说明放进去。

UI 按界面、data 按职责拆分文件和包后，旧包的类名、文件生成类和相关方法描述符不再对应当前实现。已移除涉及旧 UI、旧 data、MainActivity 及其生成类、AppAppearance 的规则，并清理 NoveliaApplication 中受重新编译影响的 R8 合成名称，保留其余依赖和未迁移代码的规则。没有手工猜测 Compose/R8 lambda 的新名称，也没有把这次整理标记为重新测量过的性能优化。

当前没有连接可用于采集的测试设备。迁移后的 UI 与数据层覆盖尚待重录；后续在合格的专用设备上重新生成两份完整 Profile，审查变更并提交：

```powershell
# 在仓库根目录执行；此签名仅用于专用测试设备
./build.ps1 -Tasks @(':app:generateReleaseBaselineProfile', '-PlocalReleaseSigning=true')
```

`ReadingBaselineProfile.startup` 应只收集启动路径；`localNavigation` 收集导航路径且不加入 startup profile。不要把这些规则当作新的性能提升证明，仍需同设备、同条件的前后测量。

以后移动包、拆分 Kotlin 文件或修改关键导航时，都应检查这两份文件；仅更新 imports 不会更新 Profile 描述符。重新采集后，更新本页的状态说明。
