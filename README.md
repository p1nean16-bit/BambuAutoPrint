# 竹竹定时打印

Android 手机端 Bambu Handy 定时打印辅助工具，当前版本 **1.1.2**。

## 功能

- 一次性、每天、每周和自定义重复计划。
- 从 Handy 导入打印机、收藏模型、打印历史和设备文件。
- 用户授权的无障碍操作、精确闹钟、执行日志及通知。
- 多任务群发计划：按收菜时间排程或手动设置顺序与间隔。
- 换板确认、失败后确认重打和后续计划顺延。
- 可选按层切换速度、任务结束息屏与无密码锁屏唤醒。

## 安装与使用

在 Releases 下载 APK 后手动安装。当前安装包是调试签名版本，适合体验与反馈。

1. 手机安装并登录 Bambu Handy，绑定打印机。
2. 在竹竹“我的”完成无障碍、精确闹钟、通知、后台运行及打开 Handy 授权。
3. 在资源库导入打印机和模型，确认名称后创建计划。
4. 按说明书配置夜间运行准备与换板确认。

详细操作见 [说明书](app/src/main/assets/manual.txt)。

## 实际限制

UI 自动化依赖 Handy 暴露的控件及页面布局；本版本通过编译和静态检查，尚未完成真实手机全流程兼容性验证。打开应用后会先尝试返回主页面，再按来源导航，无法确认入口时超时报错。

收藏只读取“收藏 → 模型”。设备文件路径为“设备 → 指定打印机 → 设置 → 文件管理 → 模型”。导入过程不点击开始打印。

“提交成功”不表示实体打印完成。首次使用应在有人看管的情况下确认设备、模型与耗材选择。换板后点击“已换板”才允许下一项继续。不保存拓竹账号密码，也不使用未公开云接口。

## 技术与结构

Kotlin、Jetpack Compose、Room、AlarmManager、AccessibilityService。

- `app/src/main/java/com/example/bambuautoprint/ui`：界面。
- `app/src/main/java/com/example/bambuautoprint/data`：任务、资源与日志。
- `app/src/main/java/com/example/bambuautoprint/automation`：导航、打印和状态控制。
- `app/src/main/java/com/example/bambuautoprint/scheduling`：调度。
- `app/src/main/assets/manual.txt`：应用内说明书。

## 构建

需要 JDK 17、Android SDK 35、Gradle 8.9；设置 `ANDROID_HOME` 后在项目目录运行：

```sh
gradle assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`tools/build.ps1` 是原开发环境的 D 盘构建脚本，可按本机安装路径修改。

## 项目关系

本工具是独立项目，与 Bambu Lab 无隶属关系。不包含 Bambu Handy 安装包、账号凭据、用户日志和截图。

## 开源许可

本项目源码使用 [MIT License](LICENSE)。第三方依赖遵循其各自许可。
