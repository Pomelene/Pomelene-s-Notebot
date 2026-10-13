# 兼容环境

目标为加入铜号音色后的正式版本。截至 2026-10-13，纳入 26.1、26.1.1、26.1.2、26.2、26.3；不将快照或尚未发布的版本声明为兼容。

## 构建与运行矩阵

| Minecraft | 较早 Loader | 较早 Fabric API | 较新 Loader | 较新 Fabric API |
|---|---|---|---|---|
| 26.1 | 0.18.4 | 0.144.4+26.1 | 0.19.5 | 0.145.1+26.1 |
| 26.1.1 | 0.18.4 | 0.145.2+26.1.1 | 0.19.5 | 0.145.4+26.1.1 |
| 26.1.2 | 0.18.4 | 0.145.4+26.1.2 | 0.19.5 | 0.155.3+26.1.2 |
| 26.2 | 0.19.3 | 0.153.0+26.2 | 0.19.5 | 0.161.0+26.2 |
| 26.3 | 0.19.3 | 0.161.0+26.3 | 0.19.5 | 0.162.0+26.3 |

版本表保存在 `compatibility.json`，构建和验证脚本直接读取该文件。表中为固定测试组合，`latest` 是本次固定的新版本环境，不会在构建时自动追随网站最新版本。较早 API 为本分支选择的兼容基线，并不表示更旧的所有版本都无法运行。

每个 JAR 的 `fabric.mod.json` 限定一个确切 Minecraft 版本、Java 25 和 Loader / API 下限。Fabric API 自身还会检查游戏版本及依赖，请安装为当前 Minecraft 发布的 API。中间 Loader / API 组合不逐个穷举；未测试的未来版本不视作已验证。

## 实现方式

2026-10-13 验证完成：上表 10 组环境均通过客户端播放、调音、20 音色检测、64 格交互、高亮、筛选和界面回归。每组通过 47 项单元测试；1 项可选外部 NBS v6 文件测试未提供样本而跳过，合成 v6 文件解析测试通过。结果及产物校验值见 [验证记录](verification/matrix-2026-10-13.json)。

- `src/main/java`：共用的曲谱、文件目录、播放、调音、检测、高亮与界面逻辑。
- `src/platform/26.1`：26.1 系列的界面访问与键盘类型适配。
- `src/platform/26.2`：26.2 的界面访问与 GLFW 键盘类型适配。
- `src/platform/26.3`：26.3 的界面访问与 SDL 键盘类型适配。
- 构建通过 `-PmcVersion=...` 只加入一组适配类，无需在正式模组中反射查找游戏接口。
- 客户端测试单独适配 Fabric 测试框架的连接接口改名；该适配不会进入发行 JAR。

`baseline` 和 `latest` 都针对较早 API 编译正式源码，仅切换运行时的 Loader / API。打包脚本逐项核对两组产物的类、资源和嵌入依赖内容，防止变成针对各环境分别编译的不同程序。仅忽略 Loom 写入清单的 Loader / Mixin 编译来源字段差异；发行包保留较早环境的真实构建信息。

## 复现

```powershell
python tools/build_matrix.py --verify
python tools/package_matrix.py
```

仅检查一个组合：

```powershell
./gradlew.bat build runClientGameTest '-PmcVersion=26.2' '-PfabricProfile=latest' '-PnotebotTestSuite=matrix'
```

可用测试入口：`gameplay`、`detection`、`ui`、`matrix`。矩阵测试串行启动独立客户端，以免窗口焦点干扰播放测试。每个组合的输出分开保存，详细结果在 `build/matrix-logs/results.json`；游戏截图位于对应构建目录的 `run/clientGameTest/screenshots`。

## 版本依据

- [Mojang 正式版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [26.1 官方更新说明：铜号音色](https://www.minecraft.net/zh-hant/article/minecraft-java-edition-26-1)
- [Fabric 26.2：界面接口迁移](https://fabricmc.net/2026/06/15/262.html)
- [Fabric 26.3：SDL 输入迁移](https://fabricmc.net/2026/09/15/263.html)
- [Fabric API Maven 版本清单](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
