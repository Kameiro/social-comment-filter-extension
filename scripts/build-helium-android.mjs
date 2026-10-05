import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptsDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = dirname(scriptsDir);
const extensionRoot = join(projectRoot, "extension");
const output = join(projectRoot, "dist", "helium-android-extension");
const files = [
  ["background/background.js", "background.js"], ["content/common/post_utils.js", "post_utils.js"],
  ["background/douyin_message_sdk.js", "douyin_message_sdk.js"],
  ["data/comment_database.js", "comment_database.js"], ["content/common/comment_api.js", "comment_api.js"],
  ["content/common/profile_metadata.js", "profile_metadata.js"],
  ["content/common/profile_fetch.js", "profile_fetch.js"], ["background/profile_lookup.js", "profile_lookup.js"],
  ["cloud/cloud_sync.js", "cloud_sync.js"], ["dashboard/dashboard.css", "dashboard.css"],
  ["dashboard/dashboard.html", "dashboard.html"], ["dashboard/dashboard.js", "dashboard.js"],
  ["content/platforms/douyin.js", "douyin_content.js"], ["content/platforms/douyin_search.js", "douyin_search.js"], ["content/common/direct_message.js", "direct_message.js"],
  ["content/platforms/kuaishou.js", "kuaishou_content.js"], ["content/common/network_capture_main.js", "network_capture_main.js"],
  ["popup/popup.css", "popup.css"], ["popup/popup.html", "popup.html"], ["popup/popup.js", "popup.js"],
  ["content/common/task_float.js", "task_float.js"], ["content/platforms/xhs.js", "xhs_content.js"]
];
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await Promise.all(files.map(([src, dest]) => cp(join(extensionRoot, src), join(output, dest))));
for (const name of ["background.js", "popup.js", "popup.html", "dashboard.html"]) {
  const file = join(output, name);
  let text = await readFile(file, "utf8");
  text = text.replaceAll("../content/common/", "").replaceAll("../data/", "").replaceAll("../cloud/", "").replaceAll("content/common/direct_message.js", "direct_message.js");
  text = text.replaceAll("content/common/", "").replaceAll("content/platforms/douyin.js", "douyin_content.js").replaceAll("content/platforms/kuaishou.js", "kuaishou_content.js").replaceAll("content/platforms/xhs.js", "xhs_content.js");
  text = text.replaceAll("dashboard/dashboard.html", "dashboard.html");
  await writeFile(file, text);
}
const manifest = JSON.parse(await readFile(join(extensionRoot, "manifest.json"), "utf8"));
manifest.name = "评论筛选助手（安卓）";
manifest.description = "按关键词、IP属地和日期筛选当前页面已加载的抖音/小红书/快手评论。安卓版本不含主页资料核验。";
manifest.permissions = manifest.permissions.filter(permission => permission !== "nativeMessaging");
manifest.host_permissions = manifest.host_permissions.filter(pattern => !pattern.startsWith("http://127.0.0.1:"));
delete manifest.key;
// Flattened Android package keeps the historical filenames and must point at them.
manifest.action.default_popup = "popup.html";
manifest.background.service_worker = "background.js";
manifest.content_scripts.forEach(entry => { entry.js = entry.js.map(path => path.split("/").pop().replace("douyin.js", "douyin_content.js").replace("xhs.js", "xhs_content.js").replace("kuaishou.js", "kuaishou_content.js")); });
await writeFile(join(output, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
await writeFile(join(output, "README-安卓安装说明.txt"), "请参见项目根目录 README.md 与 docs/ANDROID.md。\n");
console.log(`Android extension built: ${output}`);
