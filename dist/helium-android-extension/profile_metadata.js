(() => {
  if (globalThis.CommentFilterProfileMetadata) return;

  // Self-contained so the headless worker can evaluate the same DOM reader.
  function extractXhsProfilePublicInfo() {
    const info = { gender: "未知", profileAge: "", profileLocation: "" };
    const root = document.querySelector(".user-info");
    if (!root) return info;
    const tags = root.querySelector(".user-tags");
    if (!tags) return info;
    const references = [...tags.querySelectorAll(".gender use")].map(icon => String(
      icon.getAttribute("href") || icon.getAttribute("xlink:href") ||
      icon.getAttributeNS("http://www.w3.org/1999/xlink", "href") || ""
    ).toLowerCase());
    const female = references.some(reference => reference.endsWith("#female"));
    const male = references.some(reference => reference.endsWith("#male"));
    if (female !== male) info.gender = female ? "女" : "男";

    const locationPrefix = /^(?:北京|天津|上海|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆|香港|澳门|中国|美国|英国|法国|德国|日本|韩国|加拿大|澳大利亚|新加坡|马来西亚|泰国|越南|菲律宾|文莱|西班牙|意大利|俄罗斯|新西兰|印度尼西亚|瑞士|荷兰|比利时|葡萄牙|瑞典|挪威|芬兰|丹麦|阿联酋|南非)/;
    for (const tag of tags.querySelectorAll(".tag-item")) {
      const text = String(tag.innerText || tag.textContent || "").replace(/\s+/g, " ").trim();
      const age = text.match(/^(?:(?:男|女)\s*)?(\d{1,3})\s*岁$/);
      if (age && Number(age[1]) > 0 && Number(age[1]) <= 120) info.profileAge = `${Number(age[1])}岁`;
      const labeled = text.match(/^(?:所在地|现居地|居住地)[:：\s]+(.+)$/);
      if (labeled) info.profileLocation = labeled[1];
      else if (!info.profileLocation && locationPrefix.test(text) && text.length <= 30 &&
        !/博主|职业|毕业|大学|学校|公司|品牌|岁|IP属地/.test(text)) info.profileLocation = text;
    }
    // Homepage IP region is not the user's declared residence.
    return info;
  }

  function extractDouyinProfilePublicInfo({ html = "", expectedSecUid = "" } = {}) {
    if (!expectedSecUid) return null;
    const doc = html ? new DOMParser().parseFromString(html, "text/html") : document;
    const candidates = [];
    const visit = (value, depth = 0) => {
      if (!value || typeof value !== "object" || depth > 25) return;
      // RENDER_DATA also includes the signed-in account. Never select by nickname or position.
      if ((value.secUid || value.sec_uid) === expectedSecUid && Object.hasOwn(value, "gender") &&
        ["age", "user_age", "country", "province", "city"].some(key => Object.hasOwn(value, key))) {
        candidates.push(value);
      }
      for (const child of Object.values(value)) visit(child, depth + 1);
    };
    let flight = "";
    for (const script of doc.scripts) {
      const text = script.textContent || "";
      if (script.id === "RENDER_DATA") {
        try { visit(JSON.parse(text)); }
        catch { try { visit(JSON.parse(decodeURIComponent(text))); } catch {} }
      }
      const match = text.trim().match(/^self\.__pace_f\.push\(([\s\S]+)\);?$/);
      if (!match) continue;
      try {
        const chunk = JSON.parse(match[1]);
        if (chunk[0] === 1 && typeof chunk[1] === "string") flight += `${chunk[1]}\n`;
      } catch {}
    }
    for (const line of flight.split("\n")) {
      const match = line.match(/^[\da-f]+:([\s\S]+)$/i);
      if (!match) continue;
      try { visit(JSON.parse(match[1])); } catch {}
    }
    const user = candidates.sort((a, b) => Object.keys(b).length - Object.keys(a).length)[0];
    if (!user || user.userCanceled === true || user.isNotShow === true) return null;
    const publicTags = user.isNotShowBaseTag !== true;
    const age = Number(user.age ?? user.user_age);
    const location = [user.country, user.province, user.city, user.district]
      .filter(value => typeof value === "string" && value.trim() && value !== "$undefined")
      .map(value => value.trim());
    if (location.length > 1 && location[0] === "中国") location.shift();
    if (location.length > 1 && location[location.length - 1] === location[location.length - 2]) location.splice(-2, 1);
    return {
      gender: publicTags ? normalizeGender(firstValue(user.gender, user.gender_type, user.genderType, user.sex)) : "未知",
      profileAge: publicTags && Number.isInteger(age) && age > 0 && age <= 120 ? `${age}岁` : "",
      profileLocation: publicTags && user.hideLocation !== true ? [...new Set(location)].join("·") : "",
      profileReadSource: "douyin-ssr"
    };
  }

  function firstValue(...values) {
    return values.find(value => value !== undefined && value !== null && value !== "") ?? "";
  }

  function normalizeGender(value) {
    if (value === 1 || value === "1" || /^(?:male|man|男)$/i.test(String(value || "").trim())) return "男";
    if (value === 2 || value === "2" || /^(?:female|woman|女)$/i.test(String(value || "").trim())) return "女";
    return "未知";
  }

  function stringValue(value) {
    return typeof value === "string" ? value.trim() : "";
  }

  function findDouyinApiUser(value, expectedSecUid, depth = 0, visited = new Set()) {
    if (!value || typeof value !== "object" || depth > 16 || visited.has(value)) return null;
    visited.add(value);
    const hasIdentity = value.sec_uid === expectedSecUid || value.secUid === expectedSecUid || value.sec_user_id === expectedSecUid;
    const hasProfileFields = ["gender", "gender_type", "nickname", "nick_name", "uid", "age", "user_age",
      "follower_count", "following_count", "total_favorited", "aweme_count"].some(key => Object.hasOwn(value, key));
    if (hasIdentity && hasProfileFields) {
      return value;
    }
    if (Array.isArray(value)) {
      for (const child of value) {
        const found = findDouyinApiUser(child, expectedSecUid, depth + 1, visited);
        if (found) return found;
      }
      return null;
    }
    for (const child of Object.values(value)) {
      const found = findDouyinApiUser(child, expectedSecUid, depth + 1, visited);
      if (found) return found;
    }
    return null;
  }

  function publicLocationFromUser(user) {
    const values = [user.country, user.country_name, user.province, user.province_name, user.city, user.city_name,
      user.district, user.district_name, user.county, user.county_name]
      .map(stringValue)
      .filter(value => value && value !== "$undefined");
    const unique = [...new Set(values)];
    if (unique.length > 1 && unique[unique.length - 1] === unique[unique.length - 2]) unique.splice(-2, 1);
    if (unique[0] === "中国") unique.shift();
    return unique.join("·");
  }

  function extractDouyinApiProfileInfo(payload, expectedSecUid = "") {
    if (!expectedSecUid) return null;
    const user = findDouyinApiUser(payload, expectedSecUid);
    if (!user || user.userCanceled === true || user.user_canceled === true || user.isNotShow === true || user.is_not_show === true) return null;

    const rawGender = firstValue(user.gender, user.gender_type, user.genderType, user.sex);
    const rawAge = Number(firstValue(user.age, user.user_age));
    const profileAge = Number.isInteger(rawAge) && rawAge > 0 && rawAge <= 120 ? `${rawAge}岁` : "";
    const publicTags = user.isNotShowBaseTag !== true && user.is_not_show_base_tag !== true;
    const hiddenLocation = user.hideLocation === true || user.hide_location === true;
    const profileLocation = publicTags && !hiddenLocation ? publicLocationFromUser(user) : "";
    return {
      gender: publicTags ? normalizeGender(rawGender) : "未知",
      profileAge: publicTags ? profileAge : "",
      profileLocation,
      profileNickname: stringValue(firstValue(user.nickname, user.nick_name, user.nickName)),
      profileUid: stringValue(firstValue(user.uid, user.user_id, user.userId)),
      profileSecUid: stringValue(firstValue(user.sec_uid, user.secUid, user.sec_user_id)),
      profileStats: {
        followerCount: Number(firstValue(user.follower_count, user.followerCount)) || 0,
        followingCount: Number(firstValue(user.following_count, user.followingCount)) || 0,
        totalFavorited: Number(firstValue(user.total_favorited, user.totalFavorited)) || 0,
        awemeCount: Number(firstValue(user.aweme_count, user.awemeCount)) || 0
      },
      profileReadSource: "douyin-profile-api"
    };
  }

  function normalizedSvgSource(svg) {
    return String(svg?.outerHTML || "")
      .toLowerCase()
      .replace(/\s+/g, " ")
      .trim();
  }

  function douyinGenderFromSvg(svg) {
    if (!svg) return "未知";
    const source = normalizedSvgSource(svg);
    const viewBox = String(svg.getAttribute?.("viewBox") || "").replace(/\s+/g, " ").trim();
    const masks = [...svg.querySelectorAll?.("mask") || []].map(mask =>
      String(mask.getAttribute?.("id") || mask.id || "").toLowerCase()
    );
    const paths = [...svg.querySelectorAll?.("path") || []];
    const pathData = paths.map(path => String(path.getAttribute?.("d") || "")).join(" ");
    const paint = [source, ...paths.map(path => [
      path.getAttribute?.("fill"), path.getAttribute?.("stroke"), path.getAttribute?.("style")
    ].join(" "))].join(" ").toLowerCase();

    // Douyin has shipped several compiled SVG variants. Some keep the
    // woman_svg__a mask, while newer builds keep only the pink stroke and the
    // characteristic two-line female glyph.
    const femaleMask = masks.some(id => id.includes("woman_svg__a") || id.includes("woman_svg"));
    const femaleReference = /(?:xlink:href|href)=["'][^"']*#female(?:["'#])/i.test(source);
    const femalePink = /#f5588e|rgb\(\s*245\s*,\s*88\s*,\s*142\s*\)|rgba\(\s*245\s*,\s*88\s*,\s*142\s*,/i.test(paint);
    const femaleShape = /m1\.617\s*10\.511/i.test(pathData) && /m1\.904\s*7\.396/i.test(pathData);
    if (femaleMask || femaleReference || (femalePink && femaleShape)) return "女";

    const maleReference = /(?:xlink:href|href)=["'][^"']*#male(?:["'#])/i.test(source);
    const maleClass = /(?:man|male)_svg(?:__a|\b)/i.test(source);
    const maleBlue = /#168ef9|rgb\(\s*22\s*,\s*142\s*,\s*249\s*\)|rgba\(\s*22\s*,\s*142\s*,\s*249\s*,/i.test(paint);
    const maleShape = /m8\s*1\.25/i.test(pathData) && /m5\s*10/i.test(pathData);
    if (maleReference || maleClass || (viewBox === "0 0 12 12" && maleBlue && maleShape)) return "男";
    return "未知";
  }

  function douyinGenderFromDom(root) {
    if (!root) return "未知";
    const svgs = [...root.querySelectorAll?.("svg") || []];
    for (const svg of svgs) {
      const gender = douyinGenderFromSvg(svg);
      if (gender !== "未知") return gender;
    }
    return "未知";
  }

  function extractDouyinRenderedProfileInfo({ html = "" } = {}) {
    const doc = html ? new DOMParser().parseFromString(html, "text/html") : document;
    const profileRoot = doc.querySelector?.('[data-e2e="user-info"]');
    const root = profileRoot || doc.body;
    if (!root) return { gender: "未知", profileAge: "", profileLocation: "", profileReadSource: "douyin-rendered" };
    let gender = douyinGenderFromDom(root);
    // In some Douyin builds the age/location block has data-e2e but the SVG is
    // rendered in a sibling node. Only use the full body as a second pass.
    if (gender === "未知" && doc.body && doc.body !== root) gender = douyinGenderFromDom(doc.body);
    const text = String((profileRoot || doc.body)?.innerText || (profileRoot || doc.body)?.textContent || "").replace(/\s+/g, " ").trim();
    const ageMatch = text.match(/(\d{1,3})\s*岁/);
    const locationMatch = text.match(/(?:^|\s)([\u4e00-\u9fa5A-Za-z]{2,12}[·•・][\u4e00-\u9fa5A-Za-z]{2,20})(?:\s|$)/);
    const age = ageMatch && Number(ageMatch[1]) > 0 && Number(ageMatch[1]) <= 120
      ? `${Number(ageMatch[1])}岁`
      : "";
    return {
      gender,
      profileAge: age,
      profileLocation: locationMatch?.[1]?.replace(/[•・]/g, "·") || "",
      profileReadSource: "douyin-rendered"
    };
  }

  globalThis.CommentFilterProfileMetadata = {
    extractXhsProfilePublicInfo,
    extractDouyinProfilePublicInfo,
    extractDouyinApiProfileInfo,
    extractDouyinRenderedProfileInfo
  };
})();
