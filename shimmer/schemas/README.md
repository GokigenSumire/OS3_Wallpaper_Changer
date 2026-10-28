# Room 数据库结构证据

KSP 处理期间，`room.schemaLocation` 导出应用数据库 `ShimmerDatabase` 的结构。`3.json` 是冻结的 P0 实体结构（P0 来源中未改变的 `folders`/`images` 表），保留为 `MigrationTestHelper` 的测试夹具。Android 迁移测试先使用此夹具创建旧版本数据库，再通过 `ShimmerDatabase.MIGRATION_3_4` 和 `MIGRATION_4_5` 迁移打开；该过程不会覆盖用户配置。

v4 到 v5 的迁移同时支持早期 P1 原型创建的历史 v4 关联结构。迁移会规范化两种可能的 SQL 默认值变体；存在重复图片 URI 时合并来源关联；然后才建立 v5 URI 唯一性索引。

电脑端 Room/Robolectric 测试和设备端插桩测试分别用于验证生成的数据库结构、DAO 及 SQLite 迁移行为。测试的编写、编译或电脑端结果不能替代 Android 16 文档提供方、厂商系统、APK 升级或目标机验证；设备端未执行的项目在 P1 测试卡中保持 `NOT_RUN`。
