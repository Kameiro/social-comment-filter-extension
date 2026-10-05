# ZeroTermux 本地核验环境（实验性）

这是一条给安卓平板准备本地无头浏览器的最短路径。当前扩展**还不会自动调用**这个环境；完成后，评论抓取照常可用，但性别仍会显示为“未知”。

## 只需做这些

1. 安装并打开 [ZeroTermux](https://zerotermux.dev/)，等待首次初始化结束，看到命令提示符 `~ $`。
2. 在 ZeroTermux 中依次执行下面四段命令。每段完成后再执行下一段：

```bash
pkg update -y
pkg upgrade -y
```

```bash
pkg install -y x11-repo
pkg install -y nodejs firefox termux-tools
```

```bash
mkdir -p ~/comment-filter-termux
cd ~/comment-filter-termux
npm init -y
npm install puppeteer-core
```

```bash
node -e "const p=require('puppeteer-core');(async()=>{const b=await p.launch({product:'firefox',browser:'firefox',executablePath:'/data/data/com.termux/files/usr/bin/firefox',headless:true,args:['--no-remote']});const page=await b.newPage();await page.goto('https://example.com',{waitUntil:'domcontentloaded',timeout:30000});console.log('OK:',await page.title());await b.close()})().catch(e=>{console.error(e);process.exit(1)})"
```

最后一段出现 `OK: Example Domain`，说明平板上的 Firefox 与 Puppeteer 已经可以在后台协作运行。

## 现在不用做的事

- 不需要安装或配置 `Termux X11`、VNC、Boot、Tasker。
- 不需要给 ZeroTermux root 权限。
- 不需要登录抖音或小红书。

这些只会在后续把“主页性别核验”正式接入安卓扩展时才需要。届时会单独提供一个本机服务和登录步骤；不会要求通过验证码或绕过平台限制。

## 常见问题

| 现象 | 处理方式 |
| --- | --- |
| `pkg` 提示镜像问题 | 执行 `termux-change-repo` 选择可用镜像，再重新执行 `pkg update -y`。 |
| `node` 或 `firefox` 找不到 | 重新执行第二段安装命令。 |
| 测试命令提示 `No command :node` | 开头不要输入冒号；命令应从 `node -e` 开始。 |
| 测试没有出现 `OK: Example Domain` | 截取完整终端输出，保留错误前后的几行。 |

## 后续状态

本教程只验证本机运行能力。等扩展接入 `127.0.0.1` 本地服务后，才会使用这里准备的 Firefox 去读取公开主页上的性别图标。
