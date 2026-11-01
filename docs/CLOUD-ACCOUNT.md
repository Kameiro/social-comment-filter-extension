# 双端云端账户契约

## 登录

浏览器端和快手手机模块使用同一邮箱和密码登录：

```text
POST /v1/auth/login
{ "email": "user@example.com", "password": "..." }

GET /v1/auth/me
Authorization: Bearer <accessToken>
```

令牌有效期由服务端决定。客户端不能因为一次连接超时就清除令牌；收到 401 时才进入“重新登录”状态。退出登录是用户主动操作，退出时只清除本机令牌，不删除云端数据。

## 同步

```text
POST /v1/sync/snapshot
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求体包含 `posts`、`comments`、`users` 和 `analysisScopes` 四个数组。服务端按 `tenant_id` 隔离数据，重复 ID 采用更新写入。客户端先落本机库，再提交快照；服务端不可用时不影响当前采集任务。

手机模块的本地数据映射：

| 本地记录 | 云端字段 | 规则 |
| --- | --- | --- |
| `posts.id` | `id`, `url` | `id = kuaishou:post:{postId}` |
| `comments.data.commentId` | `id` | `id = kuaishou:comment:{commentId}` |
| `users.id` | `profile` | `profile = kwai://profile/{userId}` |

## 手机端状态

手机端账户面板显示：账户邮箱、服务地址、上次同步时间和待同步错误。账户设置不展示访问令牌，也不允许复制令牌。同步操作在后台线程执行，按钮在请求期间禁用；成功后显示本次上传的评论、帖子和用户数量，失败后保留本地数据并提供重试。

## 服务器切换

自托管服务器只需满足同一 `/v1` 接口契约。客户端允许修改 HTTPS 服务地址，地址必须使用 HTTPS；默认服务器仍为项目的托管地址。开发测试不要把生产账户、平台登录态或真实评论数据写入测试日志。
