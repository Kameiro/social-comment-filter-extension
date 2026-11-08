export class CollectorClient {
  constructor({ baseUrl = 'http://127.0.0.1:8765', token, timeoutMs = 15000 }) {
    if (!token) throw new Error('Collector token is required');
    this.baseUrl = baseUrl.replace(/\/$/, ''); this.token = token; this.timeoutMs = timeoutMs;
  }
  async request(path, body) {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${this.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }) },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(this.timeoutMs),
    });
    const result = await response.json();
    if (!response.ok || !result.ok) {
      const error = new Error(result.error?.message || `Collector HTTP ${response.status}`);
      error.code = result.error?.code; error.status = response.status; throw error;
    }
    return result.data;
  }
  list(kind, filters = {}) {
    if (!['comments', 'users', 'posts', 'tasks'].includes(kind)) throw new Error('Unknown collection');
    return this.request(`/v1/${kind}?${new URLSearchParams(Object.entries(filters).filter(([, value]) => value !== undefined && value !== null))}`);
  }
  health() { return this.request('/v1/health'); }
  task(id) { return this.request(`/v1/tasks/${id}`); }
  events(id) { return this.request(`/v1/tasks/${id}/events`); }
  targets(id) { return this.request(`/v1/tasks/${id}/targets`); }
  setAuto(id, enabled) { return this.request(`/v1/tasks/${id}/auto`, { enabled }); }
  startComment(photoId, filters = {}) { return this.request('/v1/tasks', { ...filters, kind: 'comment', photoId, auto: true, enqueue: true }); }
  startProfiles(userIds) { return this.request('/v1/tasks', { kind: 'profile', userIds, auto: true, enqueue: true }); }
  discover(keyword, options = {}) { return this.request('/v1/tasks', { target: 20, maxPages: 20, ...options, kind: 'discovery', keyword, auto: true, enqueue: true }); }
  batch(tasks) { return this.request('/v1/tasks/batch', { tasks }); }
  action(id, action) {
    if (!['resume', 'stop', 'pause', 'retry'].includes(action)) throw new Error('Unknown task action');
    return this.request(`/v1/tasks/${id}/${action}`, {});
  }
  analyze(filters = {}) { return this.request('/v1/analysis', filters); }
  async *comments(filters = {}) {
    let offset = 0;
    while (true) {
      const page = await this.list('comments', { ...filters, limit: 200, offset });
      for (const row of page.items) yield row;
      offset += page.items.length;
      if (!page.items.length || offset >= page.total) return;
    }
  }
}
