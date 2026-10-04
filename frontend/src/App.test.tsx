import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import type { UpdateStatus } from './types'

beforeEach(() => {
  window.originalMedia = {
    parse: vi.fn(), createJobs: vi.fn(), jobs: vi.fn(async () => []), cancelJob: vi.fn(), retryJob: vi.fn(),
    library: vi.fn(async () => ({ items: [], total: 0 })), collection: vi.fn(), extractAudio: vi.fn(), renameCollection: vi.fn(),
    libraryAction: vi.fn(), settings: vi.fn(async () => ({
      download_dir: 'D:\\Downloads', browser_profile_ready: true, browser_ready: true, app_version: '1.2.0',
    })), updateSettings: vi.fn(), selectDirectory: vi.fn(), openLogin: vi.fn(), openDataDirectory: vi.fn(),
    rescan: vi.fn(), getUpdateStatus: vi.fn<() => Promise<UpdateStatus>>(async () => ({
      phase: 'unsupported', current_version: '1.1.2', available_version: null, download_percent: null,
      message: '仅正式安装版支持应用内更新',
    })), checkForUpdates: vi.fn(), installUpdate: vi.fn(), onUpdateStatus: vi.fn(() => vi.fn()),
    onSnapshot: vi.fn(() => vi.fn()),
  }
})

test('renders the download workspace', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><App /></QueryClientProvider>)
  expect(await screen.findByText('粘贴分享内容')).toBeTruthy()
  expect((screen.getByRole('button', { name: '解析链接' }) as HTMLButtonElement).disabled).toBe(true)
})

test('shows a downloaded update and requests restart installation', async () => {
  window.originalMedia.getUpdateStatus = vi.fn<() => Promise<UpdateStatus>>(async () => ({
    phase: 'downloaded', current_version: '1.1.0', available_version: '1.2.0', download_percent: 100, message: null,
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><App /></QueryClientProvider>)

  fireEvent.click(await screen.findByRole('button', { name: '设置' }))
  expect(await screen.findByText('v1.2.0 已准备就绪')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '重启更新' }))
  await waitFor(() => expect(window.originalMedia.installUpdate).toHaveBeenCalledTimes(1))
})
