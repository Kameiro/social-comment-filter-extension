# 适配软件版本记录与安装基线

本文件记录本项目当前开发与真机（OnePlus 12 PKX110 / Android 17 / Vector 3090 / libxposed API 102）现场验证通过的目标平台软件（小红书、快手、抖音）及采集模块（`comso`）的版本号、包名、构建信息及安装方式。

> [!IMPORTANT]
> 由于各大社交软件频繁迭代发版并更新代码混淆规则、网络层架构或反调试策略，新版本可能会导致 Hook 点或数据模型解析失效。
> 为保证功能稳定性，所有当前验证通过的安装包已上传至 GitHub Releases 作为归档备份，请在开发、测试或部署时优先对齐以下版本。

---

## 1. 软件版本汇总清单

| 软件名称 | 应用包名 (`Package Name`) | 版本号 (`versionName`) | 构建号 (`versionCode`) | Target SDK | 安装包形式 | 验证状态 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **小红书** | `com.xingin.xhs` | `9.53.0` | `9530804` | 36 (Android 16) | Split APKs (App Bundle) |  已验证通过（兼容 9.51.0） |
| **快手** | `com.smile.gifmaker` | `14.8.40.50567` | `50567` | 30 (Android 11) | 单 APK (`base.apk`) |  已验证通过 |
| **抖音** | `com.ss.android.ugc.aweme` | `40.7.0` | `400701` | 34 (Android 14) | 单 APK (`base.apk`) |  已验证通过 |
| **comso 采集模块** | `dev.local.commentcollector` | `0.1.5` | `6` | 36 (Android 16) | Vector / LSPosed 模块 APK |  已验证通过 |

---

## 2. 软件核心特性与适配细节

### 2.1 小红书 (Xiaohongshu)
- **适配版本**：`9.53.0`（versionCode `9530804`）
- **向下兼容**：`9.51.0`（versionCode `9510xxx`）
- **核心适配实现**：
  - **原生静默评论翻页**：通过 `XhsNativePager` Hook `retrofit2.z$a.invoke`，动态代理 `CommentService.getCommentList` 与 `getSubCommentsV3`，以 RxJava 管道（`blockingFirst()`）在后台线程抓取一级评论和二级回复，**零 UI 触控滑动**。
  - **原生静默主页资料采集**：通过 `XhsProfiles` 驱动线程调用 `UserServices.getUserInfo`，从 `UserInfo` 实体解析 IP 属地、性别、年龄、昵称、头像等，**无需打开用户主页，秒级批量采集**。
  - **混淆多版本探测**：API 服务代理工厂自动适配 9.53.0（`gka.c` / `sub.d`）与 9.51.0（`hba.c`），且加入网络层防假死探测（避免捕获未初始化的 fallback dummy 客户端）。
- **安装方法（Split APKs 安装）**：
  小红书采用 Android App Bundle 架构，包含 base 包及架构/资源分包。请通过 `install-multiple` 同时安装：
  ```bash
  unzip -q xiaohongshu-v9.53.0-split-apks.zip -d /tmp/xhs_splits
  adb install-multiple /tmp/xhs_splits/base.apk /tmp/xhs_splits/split_config.arm64_v8a.apk /tmp/xhs_splits/split_config.xxxhdpi.apk
  ```

### 2.2 快手 (Kuaishou)
- **适配版本**：`14.8.40.50567`（versionCode `50567`）
- **核心适配实现**：
  - **评论翻页与展开**：`NativeCommentPager` 深度绑定 `KmpCommentFeaturePageList` 原生模型调度，并支持折叠回复自动点击展开。
  - **原生静默主页资料采集**：通过 `KuaishouProfiles` 后台线程调用快手原生 Retrofit 接口 `com.yxcorp.gifshow.profile.util.m1.v` (`/rest/n/user/profile/v2`，返回 `Observable<t4n.d<UserProfileResponse>>`)，使用 `PublicMapper.profile()` 解析实体得到真实 IP 属地、性别、年龄、昵称，**彻底替换了过去通过 UI 跳页面 (`kwai://profile/<uid>`) 打开关闭主页的低效模式，实现零 UI 交互、零 Activity 跳跃、毫秒级静默并发采集**。
  - **全面消除卡顿与掉帧**：
    - 在 `AutoPager.tick()` 中优先执行 `nativePager.pump()`，在原生翻页与资料抓取时跳过主线程递归遍历数千个 View 的 `flatten(root, nodes)` 操作，杜绝 UI 掉帧。
    - 在 `CaptureProvider` 中为 `profile_targets(task, status)` 及 `groups_eof(task)` 建立联合索引，消除高频 `status()` 轮询造成的 SQLite 全表扫描。
  - **原生嵌入**：在快手原生 `SettingsActivity` 嵌入 `comso 模块设置`，支持双击作品弹出信息浮窗。
- **安装方法**：
  ```bash
  adb install -r kuaishou-v14.8.40.50567.apk
  ```

### 2.3 抖音 (Douyin)
- **适配版本**：`40.7.0`（versionCode `400701`）
- **核心适配实现**：
  - **评论流监听**：`DouyinComments` 拦截网络层响应并支持展开回复。
  - **静默唤醒与防风险应用提示**：模块采用 `silent_wake` 广播静默唤醒机制，并在发布包中移除 `debuggable` 标记，避免触发抖音的“风险应用/调试环境”安全告警。
  - **作品弹窗**：在视频详情页双击作品正文区域，解析当前视频信息并弹出采集与分析浮窗。
- **安装方法**：
  ```bash
  adb install -r douyin-v40.7.0.apk
  ```

### 2.4 comso 采集模块
- **版本号**：`0.1.4`（versionCode `5`）
- **源码路径**：`android/comso/`
- **构建输出**：`android/runtime/collector/comso.apk`（软链接/分发于 `outputs/comso.apk`）
- **构建与测试**：
  ```bash
  node android/comso/test.mjs
  node android/comso/build.mjs
  adb install -r outputs/comso.apk
  ```

---

## 3. GitHub Releases 归档与下载

所有验证通过的原始 APK 均归档于 GitHub Release 标签 [`app-baselines`](https://github.com/Kameiro/social-comment-filter-extension/releases/tag/app-baselines)：

| 文件名称 | 文件大小 | SHA256 校验码 | 说明 |
| :--- | :--- | :--- | :--- |
| `xiaohongshu-v9.53.0-split-apks.zip` | ~187 MB | `7060cd4d46b0b9b741b742570774d00be3a48865d311c45b6a8295acdaa9b375` | 小红书 v9.53.0 完整分包（base + arm64_v8a + xxxhdpi） |
| `kuaishou-v14.8.40.50567.apk` | ~179 MB | `1aea3180f86d97a92ebbf0418c71d7ffa9c1e56808d8a573ac14f4c66422dcae` | 快手 v14.8.40.50567 单 APK |
| `douyin-v40.7.0.apk` | ~343 MB | `51d9fd191904c3112e895bdeaf25ff5bb3b61fa65af1519a617b769e73235f0a` | 抖音 v40.7.0 单 APK |
| `comso-v0.1.4.apk` | ~257 KB | `e51eb123713900fa9f29fd4d18445a9f61412c76458f0a23d452a644490cea30` | 采集模块 Vector / LSPosed 插件 APK (0.1.4 修复快手与跨端兼容性) |
| `comso-v0.1.3.apk` | ~257 KB | `d78f1fa377ae1488d26ed6807017fcb2b804a96ebb5b18731c3705637c0b489f` | 采集模块 Vector / LSPosed 插件 APK (0.1.3 基准归档) |

如需排查新版本兼容性问题，可重新下载上述 APK 覆盖安装以还原验证基准。
