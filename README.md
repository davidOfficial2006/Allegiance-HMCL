# Allegiance HMCL

基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的 Allegiance 定制启动器，加入 DeceasedCraft 客户端同步、受管理 Mod 备份和单 Mod 回档功能。这是非官方维护版本，保留 HMCL 原作者版权声明与项目许可证。

## 下载并开始使用

到 [最新 Release](https://github.com/davidOfficial2006/Allegiance-HMCL/releases/latest) 下载 `Allegiance-HMCL-maint-20261103.3-Windows.zip`。解压后双击“启动器.cmd”。当前版本使用 JAR 和 CMD 入口，需要事先安装 64 位 Java 21；没有附带 Java 或 EXE 包装。

1. 准备 Java 21，解压启动器压缩包。
2. 双击“启动器.cmd”，在 HMCL 中添加自己的账号。
3. 自行获取整合包 ZIP，拖入启动器导入。
4. 点击启动，先补齐 Minecraft、Forge 和整合包依赖，再识别整合包、检查服务器发布清单并同步补丁。

启动器默认连接 `https://sync.decreasedcraftallegiance.com` 的公开同步接口；玩家无需后台管理密钥。整合包及 Mod 不包含在本仓库或玩家压缩包中。

## 当前维护版本

`maint-20261103.3` 保留原构建产物，未为了发布重新替换二进制。JAR SHA256：

```text
0c396d4f388b7f32c197fd02ee6f78229d3e211178bb1dab282d8521e4d7b742
```

- CurseForge 元数据统一选择官方接口或 MCIM，无密钥时使用 MCIM。
- 并发读取元数据，已安装 Mod 优先检查本地文件，避免每次启动重复排队查询。
- 使用实际实例目录、DCTweaks 身份及原始整合包清单或目录线索识别版本；无法识别时提示并继续启动。
- 按服务器公开清单校验和同步补丁，替换受管理 JAR 前备份，支持单 Mod 回档。
- 保留玩家额外客户端 Mod；不自动替换 DCTweaks，冲突文件由玩家确认处理。
- Windows 维护入口明确设置的系统证书类型和短本地管道目录同时传给游戏 Java，修复已复现的初始化崩溃。

## 验证范围

27 项选定 Java 回归测试、4 项维护脚本测试通过。已实际启动 DeceasedCraft 5.11.19 到主菜单，并通过公开接口完成配置补丁同步与备份检查。随后使用者反馈本地测试无问题；本仓库不把该反馈扩写为已独立完成全新导入、实际进服和生产单 Mod 回档的全部验收。

## 源码与维护

源码由本地已验证构建的源码归档导入。原 HMCL 上游的确切提交未核实；导入基线本身已包含早期定制，不能当作未经修改的官方上游。`upstream/imported-customized-20261102` 保留该导入基线，`codex/hmcl-maintenance` 维护定制，`main` 指向发布源码。

原构建源码记录：`1bf173721650532ee74bc8743b2f310eb711c6ae`，供追溯旧本地仓库；本仓库的提交编号会不同。Release 同时提供与 JAR 对应的原始源码归档和校验文件。

使用 JDK 21，在 Windows PowerShell 构建相同版本号：

```powershell
$env:VERSION_ROOT = '3.17-allegiance.maint-20261103.3'
.\gradlew.bat :HMCLCore:test --tests 'org.jackhuang.hmcl.sync.AllegianceSyncTest' --tests 'org.jackhuang.hmcl.addon.repository.CurseForge*Test' --tests 'org.jackhuang.hmcl.launch.DefaultLauncherRuntimeTest' :HMCL:shadowJar
```

更多维护流程见 [maintenance/README.md](maintenance/README.md)。该文档保留了原工作区归档脚本的布局要求；在独立仓库进行日常编译时使用上面的 Gradle 命令即可。

## 许可证与致谢

依据源码所附 [LICENSE](LICENSE)（GNU GPLv3 及 HMCL 附加条款），保留原作者署名。项目名称及版本号标明 Allegiance 定制，以区别 HMCL 官方版本。上游文档保留于 [docs](docs/README_zh_Hans.md)。
