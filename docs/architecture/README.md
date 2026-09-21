# 架构与界面基础

[返回文档总目录](../README.md)

这里解释跨功能共用的结构和约束。具体业务规则放在[功能专题](../features/README.md)，避免每个页面分别维护一套导航、状态或交互说明。

| 文档 | 核心问题 |
| --- | --- |
| [架构与代码地图](architecture.md) | 服务如何初始化，状态如何从磁盘流向界面，后台任务属于谁 |
| [源码目录导航](source-layout.md) | 文件、包和测试应放在哪里，迁移时需修改哪些引用 |
| [界面、导航与交互](ui-and-navigation.md) | 路由、返回栈、Compose 副作用、屏幕适配、Markdown、电子纸 |

排查跨层问题时沿“页面事件 → AppController/领域函数 → API 或 LocalStore → 可观察状态 → 页面”追踪。有关状态落盘的精确保证见[数据存储](../data/data-and-storage.md)，有关账号和请求归属的保证见[网络与同步](../network/network-and-sync.md)。
