import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/content/common/profile_metadata.js", import.meta.url), "utf8");

function extract({ references = [], tags = [], profile = true, metadata = true } = {}) {
  const panel = {
    querySelectorAll: selector => selector === ".gender use"
      ? references.map(reference => ({ getAttribute: name => name === "xlink:href" ? reference : null, getAttributeNS: () => null }))
      : tags.map(textContent => ({ textContent }))
  };
  const root = { querySelector: () => metadata ? panel : null };
  const document = {
    body: { innerText: "笔记：我今年37岁，所在地：山东，男性朋友" },
    querySelector: selector => selector === ".user-info" && profile ? root : null
  };
  const context = vm.createContext({ document });
  vm.runInContext(source, context);
  return JSON.parse(JSON.stringify(context.CommentFilterProfileMetadata.extractXhsProfilePublicInfo()));
}

assert.deepEqual(extract({ references: ["/universal/web-static/svg-sprite.6.56.2.svg#female"],
  tags: ["", "北京丰台", "时尚博主", "旅行博主"] }),
  { gender: "女", profileAge: "", profileLocation: "北京丰台" });
assert.deepEqual(extract({ references: ["https://static.example/icons.svg#male"],
  tags: ["27岁", "广东 深圳"] }), { gender: "男", profileAge: "27岁", profileLocation: "广东 深圳" });
assert.deepEqual(extract({ references: ["#female"], tags: ["IP属地：河北", "河北旅行博主"] }),
  { gender: "女", profileAge: "", profileLocation: "" });
assert.deepEqual(extract({ tags: ["女 26岁", "现居地：新西兰 奥克兰"] }),
  { gender: "未知", profileAge: "26岁", profileLocation: "新西兰 奥克兰" });
assert.equal(extract({ references: ["#male", "#female"] }).gender, "未知");
assert.equal(extract({ references: ["#female-extra"] }).gender, "未知");
assert.equal(extract({ tags: ["999岁"] }).profileAge, "");
assert.deepEqual(extract({ profile: false }), { gender: "未知", profileAge: "", profileLocation: "" });
assert.deepEqual(extract({ metadata: false }), { gender: "未知", profileAge: "", profileLocation: "" });
console.log("Profile metadata tests passed: sprite paths, age/location scope, IP separation, absent metadata.");
