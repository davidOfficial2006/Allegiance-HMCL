# Allegiance HMCL

基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的 Allegiance 非官方定制启动器，支持 DeceasedCraft 客户端同步、受管理 Mod 备份和单 Mod 回档。保留原作者版权声明与许可证。

## 玩家下载

前往 [最新版 Release](https://github.com/davidOfficial2006/Allegiance-HMCL/releases/latest)，下载 **Allegiance-HMCL-maint-20261110.3-Windows-x64.zip**。完整解压后打开文件夹内的 **Allegiance-HMCL.exe**。

**已经内置启动器所需的 Java 21 和 JavaFX，无需安装 Java，也不使用 CMD。** EXE、app、runtime 必须保持在同一文件夹，不能只复制一个 EXE。需要桌面入口时创建快捷方式。

在启动器中添加账号，自行获取整合包 ZIP 并导入，随后点击启动。Minecraft 所需的 Java、Forge 和其他游戏依赖仍由 HMCL 按实例要求准备。

支持 Windows 10/11 x64。不包含 Minecraft 整合包、Mod、账号、存档或后台管理密钥。默认连接 `https://sync.decreasedcraftallegiance.com` 的公开同步接口。

Release 仅提供两个附件：对应源码 ZIP 和包含 EXE、Java、JavaFX 的玩家 ZIP。文件 SHA256 列在 Release 说明中。

## 样式定制

先在 EXE 的设置里调整背景、主题、配色。素材建议保存在同目录“样式素材”。修改后退出启动器让配置保存，再检查 `.hmcl/config/launcher-settings.json`、`.hmcl/themes`、`.hmcl/user/themes` 和素材文件。详细说明见 [release/DELIVERY.md](release/DELIVERY.md)。EXE 与标题栏图标已换为用户提供的透明图片；标题文字可在 app/Allegiance-HMCL.cfg 设置 hmcl.window.title，无需重新编译，见 [标题栏修改说明](release/TITLE-BAR.md)。

不要提交账号配置或登录信息。源码修改和发布包重建会在确认样式后进行。

## 当前维护行为

- 统一官方/MCIM 元数据来源；没有官方密钥时使用 MCIM。
- 并发读取元数据，已有 Mod 先检查本地文件。
- 使用真实实例目录、DCTweaks 身份及原始整合包清单或目录线索识别基础版本。识别不了时提示并继续启动。
- 必要游戏依赖补齐后才进行同步。受管理文件先下载校验、再备份替换；保留单 Mod 回档。
- 不自动替换 DCTweaks，保留额外客户端 Mod；冲突文件需要确认，不自动删除。
- Windows 运行设置在 JavaFX 初始化前生效，并传递给游戏 JVM。
- 私有 Java/JavaFX 运行环境随玩家包交付，EXE 固定使用包内环境。

## 构建

需要 Windows、JDK 21 和 Python 3.11+。在仓库根目录执行：

```powershell
$env:VERSION_ROOT = '3.17-allegiance.maint-20261110.3'
.\gradlew.bat :HMCLCore:test --tests 'org.jackhuang.hmcl.sync.AllegianceSyncTest' --tests 'org.jackhuang.hmcl.addon.repository.CurseForge*Test' --tests 'org.jackhuang.hmcl.launch.DefaultLauncherRuntimeTest' :HMCL:shadowJar
python release/build_windows.py --jdk $env:JAVA_HOME --jar "$PWD/HMCL/build/libs/HMCL-3.17-allegiance.maint-20261110.3.SNAPSHOT.jar" --output "$PWD/build/player" --work "$PWD/build/runtime-package"
```

打包脚本下载官方 BellSoft Liberica JRE Full，核对固定 SHA256，并通过 JDK jpackage 生成应用镜像。可用 `--runtime-archive` 指定已经下载的同一官方 ZIP；不会覆盖已有交付目录或玩家数据。供应者链接、版本和校验值随包记录，运行环境许可证保留在 runtime 中。

## 验证与来源

此前运行环境交付版已通过 28 项选定 Java 回归测试。本次图标与标题参数变更通过重新编译、EXE 图标资源核对和中文标题实际界面验证。已实际直接运行 EXE 到 HMCL 主界面，测试进程移除了系统 Java 路径并将 JAVA_HOME 指向不存在目录；进程加载的 JVM 与 JavaFX DLL 均来自包内 runtime。详见 [release/verification.json](release/verification.json)。本次未重新进行完整游戏进服验证；之前维护版已验证整合包主菜单和生产配置补丁同步，使用者反馈测试无问题。

`main` 为发布源码，`codex/hmcl-maintenance` 保留定制维护分支，`upstream/imported-customized-20261102` 保留早期导入基线。导入基线已有定制，不能当作未修改的官方上游，原始上游确切提交尚未核实。

## 许可证

参见 [LICENSE](LICENSE)：GNU GPLv3 与 HMCL 附加条款。名称和版本标明 Allegiance 定制，不代表 HMCL 官方发布。原上游文档保留于 [docs](docs/README_zh_Hans.md)。
