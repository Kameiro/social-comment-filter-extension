import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../android/comso/src/dev/local/commentcollector/CollectorPanel.java', import.meta.url), 'utf8');
const poll = source.slice(source.indexOf('private void poll()'), source.indexOf('private void maybeAutoSync('));
assert(!poll.includes('render('), 'poll must not rebuild the page');
assert.match(poll, /sameShape\).*liveUpdate\.accept\(t\)/s, 'stable task shape updates existing controls');
assert.match(poll, /oldId != newId.*previous\.optLong\("taskId"\) != task\.optLong\("taskId"\).*api\("tasks"/s, 'active and terminal identity changes fetch history');
assert.match(poll, /generation == renderGeneration.*previousSnapshot\.equals\(lastTaskSnapshot\)/s, 'stale worker results are discarded');
assert.match(poll, /if \(h != null\) updateHistory\(t, h\)/, 'finished tasks return to history');
console.log('Collector panel refresh checks passed');
