# 怎样修改顶部标题栏

截图中带有图标和“Hello Minecraft! Launcher …”文字的区域叫标题栏。

## 改标题文字：不用改源码

1. 先退出启动器。
2. 用记事本打开 EXE 同目录下的 `app/Allegiance-HMCL.cfg`。
3. 在 `[JavaOptions]` 段落末尾添加下面这一行；也可以去掉文件末尾示例行开头的 `#` 后修改：

```text
java-options=-Dhmcl.window.title=DecreasedCraft Allegiance · 客户端同步启动器（基于 HMCL）
```

4. 把等号后面的标题改成你想显示的文字。保留前面的 `java-options=-Dhmcl.window.title=`，不要加引号。
5. 保存为 UTF-8，重新打开 EXE。顶部文字和 Windows 窗口标题会一起改变。

只保留一条启用的 `hmcl.window.title`。要恢复原来的自动版本标题，删掉这行，或在整行前面加 `#`。不要删除其他 JavaOptions，它们用于加载 JavaFX 等运行组件。

标题自定义只是显示文字；真实版本号仍可在启动器关于页面查看。请保留“基于 HMCL / 非官方定制”等清晰说明，原作者版权信息继续保留。

## 其他部分

- 左侧图标：本版已换成你提供的图片，EXE 图标和启动器左上角一并更新。图标源文件保存在源码 `release/branding/icon-source.png`，Windows 图标为同目录 `launcher.ico`。
- 配色和透明效果：先使用启动器设置中的个性化/主题选项调整，退出后配置会保存到 `.hmcl`。
- 增减按钮、改变布局或加入导航链接：这需要修改源码并重新打包。截图标出希望改变的位置即可；窗口控制按钮不能通过标题文字参数修改。

完成调整后退出启动器，告诉我交付目录路径。我会对照标题配置、主题配置和素材整理后续源码改动，无需扫描账号或登录文件。
