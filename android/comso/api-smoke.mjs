import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { CollectorClient } from './client.mjs';

const baseUrl = process.env.COLLECTOR_URL || 'http://127.0.0.1:28765';
const token = process.env.COLLECTOR_TOKEN || (process.env.COLLECTOR_TEST_DB ?
  execFileSync('sqlite3', [resolve(process.env.COLLECTOR_TEST_DB), "SELECT data FROM settings WHERE name='api_token'"], { encoding: 'utf8' }).trim() : '');
const client = new CollectorClient({ baseUrl, token });
let assertions = 0;
const check = (condition, message) => { assert.ok(condition, message); assertions++; };
const plain = await fetch(`${baseUrl}/v1/health`); check(plain.status === 401, 'Unauthenticated requests rejected');
const origin = await fetch(`${baseUrl}/v1/health`, { headers: { Authorization: `Bearer ${token}`, Origin: 'https://example.invalid' } });
check(origin.status === 403, 'Browser-origin requests rejected');
const health = await client.health(); check(health.storageReady && health.platform === 'kuaishou', 'Storage health');
const runtime = await client.request('/v1/runtime');
check(runtime.installedVersionCode >= 3 && typeof runtime.loadedBuild === 'string', 'Installed and injected builds reported separately');
check(runtime.heartbeatAt > 0 && Number.isInteger(runtime.boundPagers), 'Hook heartbeat and native binding diagnostics available');
check(!('token' in runtime) && !('authorization' in runtime), 'Runtime diagnostics exclude authentication fields');
const previousUi = await client.request('/v1/settings?name=ui');
try {
  const sample = { ...previousUi, encodingProbe: { keyword: '旅行', ipRegion: '北京', text: '评论与回复' } };
  const roundtrip = await fetch(`${baseUrl}/v1/settings`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'ui', value: sample }),
  });
  check(roundtrip.status === 200 && (await roundtrip.json()).data.encodingProbe.keyword === '旅行', 'UTF-8 JSON without charset');
  const saved = await client.request('/v1/settings?name=ui');
  check(saved.encodingProbe.ipRegion === '北京' && saved.encodingProbe.text === '评论与回复', 'Chinese fields stored intact');
} finally { await client.request('/v1/settings', { name: 'ui', value: previousUi }); }
const page = await client.list('comments', { limit: 200, sort: 'created_desc' });
check(page.items.length > 0, 'Real comments present');
check(page.items.every(row => typeof row.commentId === 'string' && typeof row.photoId === 'string'), 'IDs stay strings');
check(page.items.some(row => row.ipRegion && row.ipRegion !== 'null'), 'Real mobile comment IP regions');
check(page.items.some(row => row.likes > 0), 'Real nonzero likes');
const region = page.items.find(row => row.ipRegion)?.ipRegion;
const filtered = await client.list('comments', { ipRegion: region });
check(filtered.items.every(row => row.ipRegion.includes(region)), 'IP filter');
const regionUnion = await client.list('comments', { ipRegions: ['北京', '河北'], limit: 200 });
const beijing = await client.list('comments', { ipRegion: '北京' });
const hebei = await client.list('comments', { ipRegion: '河北' });
check(regionUnion.total === beijing.total + hebei.total, 'Beijing OR Hebei includes both disjoint regions');
check(regionUnion.items.every(row => /北京|河北/.test(row.ipRegion)), 'Multi-region query excludes other regions');
const joinedRegions = await client.list('comments', { ipRegion: '北京、河北' });
check(joinedRegions.total === regionUnion.total, 'Native form region text matches API region arrays');
const femaleRegions = await client.list('comments', { ipRegions: ['北京', '河北'], gender: 'female', limit: 200 });
check(femaleRegions.total > 0 && femaleRegions.items.every(row => row.user?.gender === 'female' && /北京|河北/.test(row.ipRegion)), 'Explicit female regional matches exclude unknown gender');
const unknown = await client.list('comments', { gender: 'unknown' });
check(unknown.items.every(row => !row.user?.gender || row.user.gender === 'unknown'), 'Unknown gender filter');
const tasks = await client.list('tasks'); check(Array.isArray(tasks.items), 'Tasks endpoint');
const task = process.env.COLLECTOR_TASK_ID ? tasks.items.find(row => row.taskId === Number(process.env.COLLECTOR_TASK_ID)) : tasks.items.find(row => row.kind === 'comment');
check(task && (await client.task(task.taskId)).comments === task.comments, 'Task-specific counts');
check(Number.isInteger(task.rootPages) && task.rootPages >= 0 && task.rootPages <= task.pages, 'Root pages reported separately from replies');
check(task.total == null || task.platformCountGap === Math.max(0, task.total - task.comments), 'Platform count gap is explicit');
const discovery = tasks.items.find(row => row.kind === 'discovery' && row.status === 'complete' && row.params.keyword === '旅行');
if (discovery) {
  const found = await client.list('posts', { taskId: discovery.taskId, limit: 200 });
  check(found.total === discovery.discoveredPosts, 'Discovery results match their own task');
  check((await client.task(discovery.taskId)).params.keyword === discovery.params.keyword, 'Discovery task retains its original keyword');
  check((await client.list('posts', { taskId: task.taskId })).total === 0, 'Other saved posts excluded from discovery results');
}
const profiles = tasks.items.find(row => row.kind === 'profile' && row.status === 'complete');
if (profiles) {
  const targets = await client.targets(profiles.taskId);
  check(targets.items.length === profiles.profilesTotal && targets.items.every(row => row.status === 'complete'), 'Every profile target actually completed');
  check(profiles.profilesDone === profiles.profilesTotal && profiles.profilesFailed === 0, 'Profile task success reconciles');
}
if (['paused', 'running', 'running-replies'].includes(task.status)) {
  const duplicate = await client.startComment(task.photoId);
  check(duplicate.taskId === task.taskId && duplicate.duplicate, 'Concurrent duplicate rejected without new job');
}
const analysis = await client.analyze(); check(analysis.commentsAnalyzed > 0 && analysis.basis === 'stored_public_comments_only', 'Real stored-comment analysis');
check(analysis.items.reduce((total, user) => total + user.commentCount, 0) <= analysis.commentsAnalyzed, 'Analysis count reconciliation');
let missingRejected = false;
try { await client.request('/v1/tasks', { kind: 'comment' }); } catch (error) { missingRejected = error.status === 400; }
check(missingRejected, 'Missing post ID rejected');
if (process.env.COLLECTOR_QUEUE_TEST === '1') {
  check(task.status === 'paused' || task.status.startsWith('running'), 'Queue test needs an existing active task');
  const users = await client.list('users', { limit: 1 }); const uid = users.items[0].userId;
  let queued;
  try {
    queued = await client.startProfiles([uid]); check(queued.status === 'queued', 'Next job waits instead of taking the App page');
    const duplicate = await client.startProfiles([uid]); check(duplicate.taskId === queued.taskId && duplicate.duplicate, 'Duplicate queued job reuses original');
    const previous = await client.task(task.taskId); check(previous.status === task.status, 'Current job unaffected by queue creation');
    await client.action(queued.taskId, 'stop'); check((await client.task(queued.taskId)).status === 'stopped', 'Queued job cancellable');
  } finally { if (queued && (await client.task(queued.taskId)).status === 'queued') await client.action(queued.taskId, 'stop'); }
}
console.log(JSON.stringify({ result: 'PASS', assertions, comments: health.comments, users: health.users, posts: health.posts,
  commentsWithIpInPage: page.items.filter(row => row.ipRegion && row.ipRegion !== 'null').length, taskId: task.taskId,
  taskStatus: task.status, taskComments: task.comments, rootComplete: task.rootComplete, pendingReplies: task.pendingReplies }, null, 2));
