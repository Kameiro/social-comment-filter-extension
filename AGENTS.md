# 项目 Agent 说明

这份文件是给维护本项目的编码 Agent 和协作者使用的工作约定。先阅读本文件，再阅读 `README.md` 和对应平台说明。

## 目录约定

- `extension/`：桌面端与安卓 Chromium 扩展源码，按 `background`、`content`、`popup`、`database` 和平台模块组织。
- `services/playwright-worker/`：电脑端独立 Playwright 服务，负责主页资料核验、发现帖子和需要后台浏览器的任务。
- `scripts/`：构建、安装和回归测试脚本。
- `docs/`：安卓端、服务器和开发说明。
- `dist/`：由构建脚本生成的发布产物，不要手工修改其中的文件。

## 行为约定

- 优先复用现有消息协议、任务编号、状态字段和数据库结构，不要在平台模块外重复实现一套任务状态。
- `scrollLimit=0` 表示读取到平台明确返回没有下一页；`targetGenderCount=0` 表示不因命中数量提前停止。默认任务应尽量获取全部符合筛选条件的评论。
- 只有接口明确返回 `has_more=false`、等价的 `hasNext=false` 或 `no_more` 游标时，才能把任务标记为“已确认到末页”。缺少字段、游标重复、游标缺失、页数上限、验证码和接口失败都只能标记为未完成或未确认到末页。
- 不绕过登录、验证码、风控或平台权限。捕获网页已经发出的请求时，使用当前页面会话产生的 Cookie、签名和动态参数，不保存固定签名。
- 独立 Playwright 浏览器没有抖音登录态时，必须明确返回需要登录并启动可见登录流程；不能静默把性别、年龄或所在地记成已确认，也不能把主浏览器的登录状态假设为独立浏览器已登录。
- 性别、年龄、所在地只使用平台公开资料或公开图标。无法确认时保留“未知”或空白，并在任务详情中保留失败原因。
- 所有停止、验证暂停和失败路径都要保存已抓取数据，且不能自动关闭用户正在处理的验证页面。
- 修改扩展源码后同步更新用户可见的 `README.md` 或 `docs/` 说明；如果涉及安卓端，重新生成 `dist/helium-android-extension`。

## 修改与验证

- 使用 `apply_patch` 做人工编辑，保留用户已有的未提交改动；不要使用 `git reset --hard`、`git checkout --` 或清理未跟踪文件。
- 修改完成后运行：

```bash
for file in scripts/test-*.mjs; do node "$file"; done
node scripts/build-helium-android.mjs
unzip -tq dist/helium-android-extension.zip
git diff --check
```

- 电脑端扩展源码更新后，需要在浏览器扩展管理页重新加载扩展；安卓端加载 `dist/helium-android-extension`，不直接加载源码目录。
- 提交前检查 `git status --short`、`git diff --stat`，确认没有把登录凭据、验证码内容、浏览器 Cookie 或运行时用户数据加入 Git。
