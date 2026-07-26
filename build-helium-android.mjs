import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const output = join(root, "helium-android-extension");
const files = [
  "background.js",
  "douyin_content.js",
  "popup.css",
  "popup.html",
  "popup.js",
  "task_float.js",
  "xhs_content.js"
];

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await Promise.all(files.map(file => cp(join(root, file), join(output, file))));

const manifest = JSON.parse(await readFile(join(root, "manifest.json"), "utf8"));
manifest.name = "评论筛选助手（安卓）";
manifest.description = "按关键词、IP属地和日期筛选当前页面已加载的抖音/小红书评论。安卓版本不含主页资料核验。";
manifest.permissions = manifest.permissions.filter(permission => permission !== "nativeMessaging");
manifest.host_permissions = manifest.host_permissions.filter(pattern => !pattern.startsWith("http://127.0.0.1:"));
delete manifest.key;
await writeFile(join(output, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
await writeFile(join(output, "README-安卓安装说明.txt"), [
  "评论筛选助手 - Helium/Titanium Android 安装说明",
  "",
  "1. 将整个 helium-android-extension 文件夹复制到安卓平板的下载目录并解压。",
  "2. 在 Helium（现名 Titanium Browser）地址栏打开 chrome://extensions。",
  "3. 打开 Developer mode（开发者模式），选择 Load unpacked（加载已解压扩展）。",
  "4. 在系统文件选择器中选择本文件夹。",
  "5. 在扩展管理页把“评论筛选助手（安卓）”固定到工具栏，然后打开抖音或小红书帖子使用。",
  "",
  "可用：评论抓取、关键词/IP/日期筛选、滚动、复制结果、写入飞书普通表格。",
  "不可用：主页性别/年龄/所在地核验、删除已确认男性、目标女性用户数。这些功能依赖电脑本地的 Playwright 服务。"
].join("\n") + "\n");

console.log(`Android extension built: ${output}`);
