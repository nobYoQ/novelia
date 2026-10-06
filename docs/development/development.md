# 日常开发

[开发目录](README.md) · [文档首页](../README.md)

先确定改动影响的是页面交互、业务规则、持久化还是网络协议，再找对应实现和测试。一个 PR 尽量解决一个问题；代码移动、依赖升级和功能修改各自有验证成本。

## 开始一项改动

1. 用[功能文档](../features/README.md)确认当前行为，从[源码导航](../architecture/source-layout.md)定位入口。
2. 在专用设备或模拟器复现，记录版本、API、入口及电子纸/减少动效设置。能用合成数据和 MockWebServer 复现的，不依赖真实账号。
3. 完成改动后按[测试指南](../quality/testing.md)选择检查，更新受到影响的文档。
4. PR 写清问题、最终行为、验证结果与未验证范围。普通贡献不升版，维护者发行时统一修改版本并汇总 Release 说明。

较大功能先通过 Issue 讨论范围。Fork、许可和协作要求见[贡献指南](../../CONTRIBUTING.md)。

## 新增页面

页面放 `ui/<功能>/`，在 [MainActivity](../../app/src/main/java/cc/novelia/app/MainActivity.kt) 注册路由，通过 `AppController` 或现有回调进入。先决定哪些状态只在页面有效，哪些需要重建恢复，哪些必须写入书库。

复用公共页面、列表和弹层组件，它们已经处理电子纸与减少动效。账号相关加载使用正确的小说/论坛会话，并在结果返回时确认绑定。系统返回、横屏、宽屏、大字号、键盘遮挡和外部链接都可能影响页面，详见[界面与导航](../architecture/ui-and-navigation.md)。

## 新增阅读偏好

以新增一项排版偏好为例，除了加一个控件，还要逐项核对：

| 位置 | 要决定什么 |
| --- | --- |
| `ReaderSettings` / `LibraryState` | 默认值、旧 JSON 缺字段时的行为 |
| 默认与单书设置 | 单书保存整份快照；关闭覆盖后是否正确继承默认值 |
| 电子纸预设 | 切换和恢复模式时是否保留该偏好 |
| 正文与定位 | 是否需要重新投影、重新排版，如何保留阅读位置 |
| 设置 JSON / 阅读资料 ZIP | 是否导出，导入范围和非法值如何校验 |
| WebDAV | 是否属于可同步偏好，是否需整组同步或排除设备字段 |

通过 `LocalStore.update { copy(...) }` 提交短小的状态变换，不在其中执行 I/O。内部书库兼容、备份版本和 WebDAV 协议是不同层次，不能只加默认值就认为迁移完成。见[数据存储](../data/data-and-storage.md)。

## 新增 API

先确认调用属于小说服务、论坛还是用户配置的 WebDAV。三者的会话、缓存和错误处理不同，入口见[网络与同步](../network/network-and-sync.md)。

响应模型要分清缺字段、null、空列表和明确的零值。路径段通过现有编码函数处理，查询参数交给 URL 构造器；测试检查真正发出的请求及响应解析。

读接口决定是否使用缓存以及缓存键中的账号/来源。写接口先判断重复执行是否安全：只有明确允许重放的操作才能进入云端队列。发帖、评论和上传失败后，不能默认服务端没有处理而自动再发一次。

至少覆盖权限错误、网络失败、取消和请求期间换账号/书源。相关测试使用虚构凭据，不打印令牌或 Cookie。

## 书源与传输线路

这两个概念分开维护：

- **内容书源**是 `kakuyomu`、`syosetu`、`novelup`、`hameln`、`pixiv`、`alphapolis` 等 provider，定义在 [Providers.kt](../../app/src/main/java/cc/novelia/app/data/catalog/Providers.kt)。服务器已支持后，再补链接解析、筛选、章节和下载兼容。
- **访问线路**是原站或反代镜像，管理网络路由和认证。配置与隔离要求见[书源线路](book-source-mirrors.md)。

内部能解析某种链接，不代表 Android 已验证对应 App Link；修改接收域名还要检查 Manifest。

## 依赖和代码组织

文件使用 UTF-8 无 BOM，遵循 [.editorconfig](../../.editorconfig)。用书籍键、章节 ID、操作 ID 表达身份，不用书名和列表下标。保留协程取消，I/O 和计算使用相应调度器。

调整依赖后检查 Debug、Release、R8、原生 ABI 和最低 Android 版本，并重新生成、复核[开源许可证](../../licenses/README.md)。[Preview APK](../../.github/workflows/preview-apk.yml) 自动构建 Release 预览包并在默认分支更新 Pre-release；仓库没有 ktlint、detekt 或完整测试门禁，不能把未配置的检查写成通过。

移动 Worker 要保留旧任务类名兼容；移动 Kotlin 包和文件要检查 [Profile 描述符](../quality/baseline-profiles.md)。新文件的放置规则见[源码导航](../architecture/source-layout.md)。
