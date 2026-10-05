function firstText(...values) {
  for (const value of values) {
    const text = String(value ?? "").trim();
    if (text) return text;
  }
  return "";
}

function number(value) {
  const result = Number(value);
  return Number.isFinite(result) ? result : 0;
}

function metric(value) {
  if (typeof value === "object" && value) {
    return number(value.count ?? value.value ?? value.num);
  }
  return number(value);
}

function formatCreateTime(value) {
  const raw = number(value);
  if (!raw) return { createTime: 0, createTimeText: "" };
  const seconds = raw > 10_000_000_000 ? raw / 1000 : raw;
  return {
    createTime: Math.floor(seconds),
    createTimeText: new Date(seconds * 1000).toLocaleString("zh-CN")
  };
}

function idFromUrl(value, patterns) {
  const text = String(value || "");
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match?.[1]) return match[1];
  }
  return "";
}

export function canonicalDouyinPostId(item = {}) {
  return firstText(
    item.aweme_id,
    item.awemeId,
    item.item_id,
    item.itemId,
    item.group_id,
    idFromUrl(item.share_url || item.url || item.link, [
      /(?:\/video\/|\/note\/|\/slides\/)(\d+)/i,
      /(?:modal_id|aweme_id|video_id)=(\d+)/i
    ])
  );
}

export function normalizeDouyinSearchItem(item = {}) {
  const author = item.author && typeof item.author === "object" ? item.author : {};
  const id = canonicalDouyinPostId(item);
  const time = formatCreateTime(item.create_time ?? item.createTime ?? item.create_time_ms);
  return {
    platform: "douyin",
    id,
    url: id ? `https://www.douyin.com/video/${id}` : firstText(item.share_url, item.url, item.link),
    title: firstText(item.desc, item.title, item.description),
    nickname: firstText(author.nickname, author.unique_id, author.uniqueId),
    authorSecUid: firstText(author.sec_uid, author.secUid, author.sec_user_id, author.secUserId),
    authorUrl: author.sec_uid || author.secUid || author.sec_user_id
      ? `https://www.douyin.com/user/${firstText(author.sec_uid, author.secUid, author.sec_user_id)}`
      : "",
    diggCount: metric(item.statistics?.digg_count ?? item.digg_count),
    commentCount: metric(item.statistics?.comment_count ?? item.comment_count),
    shareCount: metric(item.statistics?.share_count ?? item.share_count),
    collectCount: metric(item.statistics?.collect_count ?? item.collect_count),
    ...time
  };
}

export function canonicalXhsPostId(item = {}) {
  const card = item.note_card && typeof item.note_card === "object" ? item.note_card : item.noteCard || {};
  return firstText(
    item.id,
    item.note_id,
    item.noteId,
    card.note_id,
    card.noteId,
    idFromUrl(item.url || item.link, [/\/explore\/([^/?#]+)/i, /(?:note_id|id)=([^&#]+)/i])
  );
}

export function normalizeXhsSearchItem(item = {}) {
  const card = item.note_card && typeof item.note_card === "object" ? item.note_card : item.noteCard || {};
  const user = card.user && typeof card.user === "object" ? card.user : item.user && typeof item.user === "object" ? item.user : {};
  const interact = card.interact_info && typeof card.interact_info === "object"
    ? card.interact_info
    : item.interact_info && typeof item.interact_info === "object" ? item.interact_info : {};
  const id = canonicalXhsPostId(item);
  const token = firstText(item.xsec_token, item.xsecToken, card.xsec_token, card.xsecToken);
  const source = firstText(item.xsec_source, item.xsecSource, card.xsec_source, "pc_search");
  const time = formatCreateTime(card.time ?? card.last_update_time ?? item.time ?? item.create_time);
  const authorId = firstText(user.user_id, user.userId, user.user_id_str);
  const url = id
    ? `https://www.xiaohongshu.com/explore/${id}?xsec_token=${encodeURIComponent(token)}&xsec_source=${encodeURIComponent(source)}`
    : firstText(item.url, item.link);
  return {
    platform: "xhs",
    id,
    url,
    title: firstText(card.display_title, card.title, card.desc, item.title),
    nickname: firstText(user.nickname, user.nick_name, user.nickName, item.nickname),
    authorId,
    authorUrl: authorId ? `https://www.xiaohongshu.com/user/profile/${authorId}` : "",
    xsecToken: token,
    xsecSource: source,
    diggCount: metric(interact.liked_count ?? interact.likedCount ?? card.liked_count ?? item.liked_count),
    commentCount: metric(interact.comment_count ?? interact.commentCount ?? card.comment_count ?? item.comment_count),
    shareCount: metric(interact.share_count ?? interact.shareCount ?? card.share_count ?? item.share_count),
    collectCount: metric(interact.collected_count ?? interact.collectedCount ?? card.collected_count ?? item.collected_count),
    ...time
  };
}

function preferNumber(left, right) {
  return number(right) > 0 ? number(right) : number(left);
}

export function mergeSearchItem(existing = {}, incoming = {}) {
  return {
    ...existing,
    ...incoming,
    id: firstText(existing.id, incoming.id),
    url: firstText(incoming.url, existing.url),
    title: firstText(incoming.title, existing.title),
    nickname: firstText(incoming.nickname, existing.nickname),
    authorSecUid: firstText(incoming.authorSecUid, existing.authorSecUid),
    authorId: firstText(incoming.authorId, existing.authorId),
    authorUrl: firstText(incoming.authorUrl, existing.authorUrl),
    xsecToken: firstText(incoming.xsecToken, existing.xsecToken),
    xsecSource: firstText(incoming.xsecSource, existing.xsecSource),
    diggCount: preferNumber(existing.diggCount, incoming.diggCount),
    commentCount: preferNumber(existing.commentCount, incoming.commentCount),
    shareCount: preferNumber(existing.shareCount, incoming.shareCount),
    collectCount: preferNumber(existing.collectCount, incoming.collectCount),
    createTime: Math.max(number(existing.createTime), number(incoming.createTime)),
    createTimeText: firstText(incoming.createTimeText, existing.createTimeText)
  };
}
