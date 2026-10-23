# admin-server 项目补充规范

公共开发规范统一维护在工作区根目录的 [`docs/开发规范.md`](../../../docs/开发规范.md)。本文件只记录 `admin-server` 的补充内容。

- Java 编译和运行使用 JDK 21。
- HTTP 服务沿用 zfoo 的 `HttpServer`、`@PacketReceiver`、`HttpAttachment` 和 `ApiResponse` 结构；新增或修改接口时同步更新 `apiDocs/` 或对应 `docs/` 文档。
- PostgreSQL 表结构以 `src/main/resources/db/schema.sql` 为准，部署和一次性运维脚本不能替代该完整结构文件。
