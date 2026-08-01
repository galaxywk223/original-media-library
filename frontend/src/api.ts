import type { Collection, Job, LibraryResult, ParsedSource, Settings } from './types'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? `请求失败 (${response.status})`)
  }
  return response.json() as Promise<T>
}

export const api = {
  parse: (text: string) =>
    request<{ sources: ParsedSource[] }>('/api/parse', {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),
  createJobs: (urls: string[], force = false) =>
    request<Array<{ source_url: string; duplicate: boolean; job: Job | null }>>('/api/jobs', {
      method: 'POST',
      body: JSON.stringify({ urls, force }),
    }),
  jobs: () => request<Job[]>('/api/jobs'),
  cancelJob: (id: string) => request<Job>(`/api/jobs/${id}/cancel`, { method: 'POST' }),
  retryJob: (id: string) => request<Job>(`/api/jobs/${id}/retry`, { method: 'POST' }),
  library: (query: URLSearchParams) => request<LibraryResult>(`/api/library?${query}`),
  collection: (id: string) => request<Collection>(`/api/library/${id}`),
  renameCollection: (id: string, title: string) =>
    request<Collection>(`/api/library/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    }),
  libraryAction: (action: 'open' | 'reveal' | 'trash', ids: string[]) =>
    request<{ affected: number }>('/api/library/actions', {
      method: 'POST',
      body: JSON.stringify({ action, ids }),
    }),
  settings: () => request<Settings>('/api/settings'),
  updateSettings: (downloadDir: string) =>
    request<Settings>('/api/settings', {
      method: 'PATCH',
      body: JSON.stringify({ download_dir: downloadDir }),
    }),
  selectDirectory: () => request<Settings>('/api/system/select-directory', { method: 'POST' }),
  openLogin: () => request<{ opened: boolean }>('/api/auth/douyin/open', { method: 'POST' }),
  rescan: () => request<{ started: boolean }>('/api/library/rescan', { method: 'POST' }),
}

