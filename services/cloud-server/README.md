# 评论筛选云端服务

这是个人云端和商业客户自托管共用的 API 模板。个人模式部署在你的服务器；商业客户部署自己的实例，把插件中的 API 地址改为客户服务器地址。

## 启动

1. 准备 PostgreSQL，执行 `schema.sql`。
2. 复制 `.env.example` 为 `.env`，设置 `DATABASE_URL`、随机的 `JWT_SECRET` 和一次性随机的 `ADMIN_SETUP_TOKEN`。
3. 安装依赖：`npm install`。
4. 启动：`npm start`。
5. 打开 `https://你的域名/admin`，用 `ADMIN_SETUP_TOKEN` 创建首个管理员；初始化成功后立即从服务器环境中删除或轮换这个令牌。
6. 在管理后台创建普通账户，或让用户在评论数据库页面自行注册。

评论数据库页面提供普通的账户登录、账户注册和邮箱验证码登录。管理员后台只用于管理账户和服务，不提供给普通用户使用。

邮箱验证码可以先在 `.env` 中配置 SMTP：`SMTP_HOST`、`SMTP_PORT`、`SMTP_SECURE`、`SMTP_USER`、`SMTP_PASS` 和 `SMTP_FROM`。管理员登录 `/admin` 后，也可以在“SMTP 邮件配置”页面输入并验证 Brevo SMTP Key；页面保存的配置会加密写入 PostgreSQL，并优先于环境变量使用。SMTP Key 不会回显。验证码保存哈希值，10 分钟有效，最多尝试 5 次；发送接口同一邮箱同一用途 60 秒内只能请求一次。

管理后台提供首个管理员初始化、管理员登录、SMTP 邮件配置、普通账户创建和账户列表。所有管理接口都要求管理员 JWT，密码只保存为 bcrypt 哈希，不保存明文。

## 生产要求

请在 HTTPS 反向代理后运行，设置 `CORS_ORIGIN` 为扩展实际来源或管理站点来源，不要长期使用 `*`；为 PostgreSQL 做备份和最小权限配置。收费状态当前由 `subscription_status` 和 `subscription_expires_at` 预留，支付回调和套餐管理后台尚未实现。
