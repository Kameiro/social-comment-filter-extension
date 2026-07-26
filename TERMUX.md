# Termux 本地核验环境教程（实验性）

这份教程的目标是在安卓平板本机准备一个可自动打开网页的浏览器环境，为后续“读取主页男女图标”做准备。

> 当前安卓扩展包**尚未自动调用 Termux 服务**。完成本教程后，插件仍只抓取和筛选评论，性别仍显示为“未知”。本教程先用于确认你的平板能稳定运行本地浏览器和保存登录状态；Termux 服务接入插件会在后续版本单独提供。

## 需要安装的应用

1. [Termux](https://github.com/termux/termux-app/releases) 或 [F-Droid 版本](https://f-droid.org/packages/com.termux/)。不要使用已经长期停止更新的 Google Play 旧版。
2. [Termux:X11](https://github.com/termux/termux-x11/releases)，仅用于首次显示浏览器并登录；后续核验可使用隐藏模式。

安装后请在 Android 设置中：

- 允许 Termux 的文件访问权限。
- 将 Termux 和 Termux:X11 的电池策略设为“不受限制”或关闭电池优化。
- 首次安装 Termux 后先打开一次，等待基础环境初始化完成。

## 第一步：安装浏览器和 Node.js

在 Termux 中依次执行：

```bash
pkg update -y
pkg upgrade -y
pkg install -y x11-repo
pkg install -y nodejs firefox termux-tools
```

确认 Firefox 可以被 Termux 找到：

```bash
firefox --version
```

如果这里提示找不到命令，重新执行 `pkg install -y firefox`；不要继续进行后面的步骤。

## 第二步：安装 Puppeteer Core

创建独立目录，避免和其他 Termux 项目混在一起：

```bash
mkdir -p ~/comment-filter-termux
cd ~/comment-filter-termux
npm init -y
npm install puppeteer-core
```

安装完成后查看版本：

```bash
npm list puppeteer-core
```

这里使用 `puppeteer-core`，它不会额外下载桌面版 Chrome，而是使用已经安装在 Termux 里的 Firefox。

## 第三步：测试隐藏浏览器

先执行：

```bash
firefox --headless --version
```

能够显示版本号即表示隐藏模式基础环境可用。某些平板的 Firefox 在隐藏模式下可能无法启动或直接退出；这类情况先跳到下一步，用 Termux:X11 的可视模式测试登录。

## 第四步：首次可视登录

首次登录抖音或小红书时，使用 Termux:X11 打开 Firefox。Termux:X11 应用需要先单独安装并打开一次。

在 Termux 依次执行：

```bash
pkg install -y termux-x11-nightly xfce
export TERMUX_X11_XSTARTUP="xfce4-session"
termux-x11 :1 &
export DISPLAY=:1
mkdir -p ~/.mozilla/firefox/comment-filter
firefox --no-remote --profile ~/.mozilla/firefox/comment-filter
```

随后切到 Termux:X11 窗口，在 Firefox 中手动访问并登录：

- `https://www.douyin.com/`
- `https://www.xiaohongshu.com/`

建议优先用短信登录。若页面显示二维码，需要另一台设备扫码；同一台平板通常无法对自己屏幕上的二维码扫码。

登录完成后直接关闭 Firefox。登录资料会保存在 `~/.mozilla/firefox/comment-filter`，以后可复用这个目录。

## 第五步：平板保活

在需要持续运行本地服务前执行：

```bash
termux-wake-lock
```

结束后可执行：

```bash
termux-wake-unlock
```

即使加了唤醒锁，部分系统仍会在内存紧张或省电模式下终止 Termux。因此建议核验时保持 Termux 在最近任务列表中，并关闭系统对它的电池优化。

## 常见问题

| 现象 | 处理方式 |
| --- | --- |
| `pkg` 下载很慢或报错 | 更换网络后执行 `pkg update`，再重试。 |
| Firefox 能安装但无法显示窗口 | 确认 Termux:X11 已安装、已打开，并重新执行第四步的 `termux-x11 :1`。 |
| 账号要求验证 | 在 Termux:X11 的 Firefox 窗口中手动完成；不要尝试绕过验证码。 |
| 锁屏后服务停止 | 检查电池优化设置，重新执行 `termux-wake-lock`。 |
| 抖音/小红书页面打不开或被要求重新登录 | 这是独立 Firefox 的登录状态，不会与 Helium 自动共享；在 Termux:X11 中重新登录即可。 |

## 下一步

环境测试通过后，下一步才是把安卓插件的主页核验请求接到 Termux 本机 `127.0.0.1` 服务。该接入会保留现在的评论抓取逻辑，并让 Termux 浏览器在后台逐个读取公开主页图标。
