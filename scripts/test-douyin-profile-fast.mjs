import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const metadataSource = await readFile(new URL("../extension/content/common/profile_metadata.js", import.meta.url), "utf8");
const cacheSource = await readFile(new URL("../extension/background/profile_lookup.js", import.meta.url), "utf8");
const target = "MS4wLjABAAAAfVaQP6tapRUF1e29SAlNOh4t9c_F6drlnUd1OFkjWJM";
const other = "MS4wLjABAAAALH1V_Rk7OHyT2KsaNLOuFYKLItCrfAAFtezZVz15aWEwwFIE4nMdfHrtfSs9jzZY";
const url = `https://www.douyin.com/user/${target}`;
const user = (secUid, extras = {}) => ({ secUid, gender: 1, age: -1, country: "安道尔", ...extras });
const fakeHtml = (...scripts) => scripts.map(({ id, text }) => `<script${id ? ` id="${id}"` : ""}>${text}</script>`).join("");
const fakeDocument = html => ({ scripts: [...html.matchAll(/<script(?: id="([^"]+)")?>([\s\S]*?)<\/script>/g)]
  .map(match => ({ id: match[1] || "", textContent: match[2] })) });
const context = vm.createContext({
  DOMParser: class { parseFromString(html) { return fakeDocument(html); } },
  document: fakeDocument(""),
  URL, setTimeout, clearTimeout
});
vm.runInContext(metadataSource, context);
const read = (html, expectedSecUid = target) => context.CommentFilterProfileMetadata.extractDouyinProfilePublicInfo({ html, expectedSecUid });
const readApi = (payload, expectedSecUid = target) => context.CommentFilterProfileMetadata.extractDouyinApiProfileInfo(payload, expectedSecUid);
const renderHtml = fakeHtml({ id: "RENDER_DATA", text: encodeURIComponent(JSON.stringify({
  app: { user: { info: user(other, { age: 26, country: "中国", province: "北京" }) } }
})) });
assert.equal(read(renderHtml), null, "do not confuse the signed-in account with the target");

const profile = user(target, { gender: 1, age: -1, ipLocation: "IP属地：山东" });
const line = `7:${JSON.stringify(["$", "$L9", null, { user: { user: profile } }])}\n`;
const flightHtml = fakeHtml(
  { id: "", text: `self.__pace_f.push(${JSON.stringify([1, 'a:T42b,{"client_params":{}}'])})` },
  { id: "", text: `self.__pace_f.push(${JSON.stringify([1, line])})` }
);
assert.deepEqual(JSON.parse(JSON.stringify(read(renderHtml + flightHtml))), {
  gender: "男", profileAge: "", profileLocation: "安道尔", profileReadSource: "douyin-ssr"
});
assert.equal(read(flightHtml, other), null);
assert.deepEqual(JSON.parse(JSON.stringify(read(fakeHtml({ id: "RENDER_DATA", text: encodeURIComponent(JSON.stringify({
  app: { user: { info: user(target, { gender: 2, age: 27, country: "中国", province: "上海", city: "上海", district: "闵行" }) } }
})) })))), { gender: "女", profileAge: "27岁", profileLocation: "上海·闵行", profileReadSource: "douyin-ssr" });
assert.equal(read(fakeHtml({ id: "RENDER_DATA", text: "%invalid" })), null);

const apiProfile = {
  sec_uid: target,
  uid: "123456789",
  nickname: "目标用户",
  gender: 2,
  age: 31,
  country: "中国",
  province: "广东",
  city: "深圳",
  follower_count: 20,
  following_count: 5,
  total_favorited: 99,
  aweme_count: 3
};
assert.deepEqual(JSON.parse(JSON.stringify(readApi({ user: apiProfile }))), {
  gender: "女",
  profileAge: "31岁",
  profileLocation: "广东·深圳",
  profileNickname: "目标用户",
  profileUid: "123456789",
  profileSecUid: target,
  profileStats: { followerCount: 20, followingCount: 5, totalFavorited: 99, awemeCount: 3 },
  profileReadSource: "douyin-profile-api"
});
assert.equal(readApi({ user: { ...apiProfile, sec_uid: other } }), null, "do not accept another user from the API response");
assert.equal(readApi({ user: { sec_uid: target } }), null, "do not accept a wrapper that has no profile fields");
assert.equal(readApi({ user: { ...apiProfile, gender: "female", sec_uid: target } }).gender, "女",
  "API gender strings must be normalized");
assert.deepEqual(JSON.parse(JSON.stringify(readApi({ data: { user: { ...apiProfile, gender: 0, age: -1, country: "" } } }))), {
  gender: "未知",
  profileAge: "",
  profileLocation: "广东·深圳",
  profileNickname: "目标用户",
  profileUid: "123456789",
  profileSecUid: target,
  profileStats: { followerCount: 20, followingCount: 5, totalFavorited: 99, awemeCount: 3 },
  profileReadSource: "douyin-profile-api"
});

const womanMask = { getAttribute: name => name === "id" ? "woman_svg__a" : "" };
const womanSvg = {
  querySelectorAll: selector => selector === "mask" ? [womanMask] : [],
  getAttribute: () => ""
};
const renderedRoot = {
  innerText: "抖音号：94170663731 IP属地：北京 30岁 北京·丰台",
  textContent: "抖音号：94170663731 IP属地：北京 30岁 北京·丰台",
  querySelectorAll: selector => selector === "svg" ? [womanSvg] : []
};
context.document = {
  body: renderedRoot,
  querySelector: selector => selector === '[data-e2e="user-info"]' ? renderedRoot : null
};
assert.deepEqual(JSON.parse(JSON.stringify(context.CommentFilterProfileMetadata.extractDouyinRenderedProfileInfo())), {
  gender: "女",
  profileAge: "30岁",
  profileLocation: "北京·丰台",
  profileReadSource: "douyin-rendered"
}, "rendered gender icon fills an API response with gender=null");

const malePath = {
  getAttribute: name => name === "viewBox" ? "0 0 12 12" : ""
};
const maleRoot = {
  innerText: "抖音号：male IP属地：北京",
  textContent: "抖音号：male IP属地：北京",
  querySelectorAll: selector => selector === "svg" ? [{
    getAttribute: name => name === "viewBox" ? "0 0 12 12" : "",
    querySelectorAll: childSelector => childSelector === "path" ? [{
      getAttribute: name => name === "d" ? "M8 1.25a.75.75 0 0 0 0 1.5 M5 10a2.5 2.5 0 1 0 0-5" : name === "fill" ? "#168EF9" : ""
    }] : []
  }] : []
};
context.document = { body: maleRoot, querySelector: () => null };
assert.equal(context.CommentFilterProfileMetadata.extractDouyinRenderedProfileInfo().gender, "男",
  "blue male SVG icon must be recognized without relying on the text 男");

vm.runInContext(cacheSource, context);
const helper = context.CommentFilterProfileLookup;
let now = 1000000;
const data = {};
const storage = { async get(defaults) { return { ...defaults, ...data }; }, async set(patch) { Object.assign(data, patch); } };
let directCalls = 0;
let renderedCalls = 0;
const lookup = helper.create({ storage, now: () => now,
  async readDirect() { directCalls++; return read(renderHtml + flightHtml); },
  async readRendered() { renderedCalls++; throw new Error("rendered fallback failed"); }
});
await lookup.read(url);
await lookup.read(`${url}?from_tab_name=main`);
assert.equal(directCalls, 1);
assert.equal(renderedCalls, 0);
assert.equal(helper.needsLookup({ profile: url, gender: "男", profileAge: "", profileLocation: "安道尔" }, data[helper.cacheKey], now), false);
now += helper.ttl + 1;
assert.equal(helper.cachedInfo(data[helper.cacheKey], url, now), null);
await lookup.read(url);
assert.equal(directCalls, 2);

const fallback = helper.create({ storage: { async get(defaults) { return defaults; }, async set() {} },
  async readDirect() { throw new Error("blocked"); },
  async readRendered() { return { gender: "女", profileAge: "", profileLocation: "" }; }
});
assert.equal((await fallback.read(url)).gender, "女");
let fallbackOnUnknownCalls = 0;
const unknownThenRendered = helper.create({
  storage: { async get(defaults) { return defaults; }, async set() {} },
  async readDirect() { return { gender: "未知", profileAge: "", profileLocation: "", profileReadSource: "douyin-rendered" }; },
  async readRendered() { fallbackOnUnknownCalls += 1; return { gender: "男", profileAge: "30岁", profileLocation: "北京" }; }
});
assert.deepEqual(JSON.parse(JSON.stringify(await unknownThenRendered.read(url))), { gender: "男", profileAge: "30岁", profileLocation: "北京", profileReadSource: "douyin-rendered" });
assert.equal(fallbackOnUnknownCalls, 1, "unknown structured results must fall back to rendered profile DOM");
let retryCalls = 0;
const unknownCache = {
  [helper.identity(url)]: {
    checkedAt: now,
    info: { gender: "未知", profileAge: "18岁", profileLocation: "北京·海淀", profileReadSource: "douyin-profile-api" }
  }
};
const retryUnknown = helper.create({
  storage: { async get(defaults) { return { ...defaults, [helper.cacheKey]: unknownCache }; }, async set() {} },
  async readDirect() { retryCalls += 1; return { gender: "男", profileAge: "18岁", profileLocation: "北京·海淀", profileReadSource: "douyin-profile-api" }; },
  async readRendered() { throw new Error("rendered fallback should not be needed"); }
});
assert.equal((await retryUnknown.read(url)).gender, "男");
assert.equal(retryCalls, 1, "cached unknown gender must be retried instead of treated as final");
let attempts = 0;
const failingStore = {};
const failing = helper.create({
  storage: { async get(defaults) { return { ...defaults, ...failingStore }; }, async set(patch) { Object.assign(failingStore, patch); } },
  async readDirect() { attempts++; throw new Error("network blocked"); },
  async readRendered() { throw new Error("login required"); }
});
await assert.rejects(failing.read(url), /login required/);
await assert.rejects(failing.read(url), /login required/);
assert.equal(attempts, 2, "failed reads are never cached as checked");
assert.equal(failingStore[helper.cacheKey], undefined);

let forwardedOptions = null;
const fastLookup = helper.create({
  storage: { async get(defaults) { return defaults; }, async set() {} },
  async readDirect(_url, options) {
    forwardedOptions = options;
    return { gender: "女", profileAge: "", profileLocation: "", profileReadSource: "douyin-profile-api" };
  },
  async readRendered() { throw new Error("fast gender path should not render"); }
});
assert.equal((await fastLookup.read(url, { genderOnly: true })).gender, "女");
assert.equal(forwardedOptions?.genderOnly, true, "gender-only mode must pass through the profile cache layer");
console.log("Douyin fast profile tests passed: target identity, SSR fields, absent data, TTL cache, fallback.");
