# 网络与认证

[返回文档总目录](../README.md)

本分类说明客户端怎样使用原站服务，不是原站 API 的永久契约。接口路径和参数以仓库调用代码及合约测试为准。

| 文档 | 主要内容 |
| --- | --- |
| [账号与登录](authentication.md) | WebView 认证、完成登录、会话代次、续期、退出和登录续接 |
| [网络、认证与同步](network-and-sync.md) | API 资源、请求关闭与取消、缓存、离线意图、后台重放 |
| [WebDAV 多设备同步](webdav-sync.md) | 可选资料范围、首次加入、设备合并、冲突和自动调度 |
| [ECH 连接测试版](ech-test.md) | ECH 适配范围、测试开关、诊断、构建与已知边界 |
| [独立论坛 API 预适配](forum-api-preview.md) | 预览分支的论坛接口、独立会话、排序、处罚记录与验证 |

修改云端收藏前阅读[书架专题](../features/library.md)，修改发帖前阅读[社区专题](../features/community.md)。两者对失败重试的要求不同，不能把所有写接口统一塞入离线队列。认证诊断的记录范围见[安全与隐私](../quality/security-and-privacy.md)。
