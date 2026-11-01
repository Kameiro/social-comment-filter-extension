# 评论筛选助手：双端架构

这个项目是一套产品的两个运行端：电脑端负责浏览器页面采集和后台浏览器任务，手机端负责在快手 App 内直接读取已经返回的公开评论数据。两端共用云端账户、数据模型和任务状态语义，平台适配代码仍留在各自运行时。

## 目录边界

```text
extension/                 Chrome/Chromium 扩展（电脑端，也可生成平板浏览器包）
services/cloud-server/     账户、租户、订阅和数据同步 API
services/playwright-worker 电脑端主页资料与发现任务服务
android/kuaishou-collector/快手 App 内 Vector/libxposed 模块（手机端主入口）
scripts/build-helium-android.mjs
                            生成可选的 Helium/Titanium 浏览器包
docs/                      用户说明、测试证据和端间契约
```

电脑端使用浏览器扩展读取抖音、小红书和快手页面。手机端的主要交付物是 `android/kuaishou-collector`，它注入快手 `com.smile.gifmaker`，把评论、帖子和公开用户资料保存到本机数据库，再按账户状态自动同步到云端。Helium/Titanium 包属于兼容运行时，保留用于平板浏览器场景，不再作为手机端 LSPosed 模块的替代品。

## 共享账户

两端都使用 `services/cloud-server` 的账户接口。默认地址由浏览器端和手机端同时配置为 `https://comment-filter.04210410.xyz`。登录成功后只保存账户令牌、账户邮箱和租户 ID；平台 Cookie、签名参数和验证码不进入云端同步数据。

同步采用本地优先策略：采集结果先写入本机数据库，网络可用且账户已登录时再发送快照。同步失败不会删除本机数据，界面显示“待同步”并在下一次任务完成或用户手动重试时再次发送。

云端快照使用以下稳定标识：

- 帖子：`kuaishou:post:{postId}`，并带 `https://www.kuaishou.com/short-video/{postId}`；浏览器端使用规范化帖子 URL。
- 评论：`kuaishou:comment:{commentId}`；同一条评论跨任务重复出现时由云端覆盖更新。
- 用户：`kwai://profile/{userId}`；浏览器端用户记录使用平台主页 URL，账户下仍按 `profile` 字段隔离。

## 端间规则

1. 账户状态只有四种：未登录、确认中、已登录、连接待恢复。超时不能被显示成“未登录”，401 才要求重新登录。
2. 采集任务和同步任务分开记录。任务完成不代表云端同步完成；同步失败必须保留原因和下次重试入口。
3. 只有平台明确返回末页证据，任务才标记为完成。滚动停止、游标重复、页数上限和页面被回收都只能标记为暂停或未确认。
4. 手机端所有操作都绑定任务 ID；查看数据库、离开面板或切换标签不能隐式停止任务。
5. 云端只保存公开评论、帖子和公开资料快照；不保存快手登录态、请求签名、验证码内容或本机接口密钥。

## 构建关系

修改电脑端公共逻辑后，运行 `node scripts/build-helium-android.mjs` 更新 `dist/helium-android-extension`。修改手机模块后，运行 `node android/kuaishou-collector/test.mjs` 和 `node android/kuaishou-collector/build.mjs`；构建产物放在上级本地 `runtime/`，不提交到仓库。
