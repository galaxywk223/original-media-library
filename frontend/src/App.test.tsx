import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import App from './App'

beforeEach(() => {
  window.originalMedia = {
    parse: vi.fn(), createJobs: vi.fn(), jobs: vi.fn(async () => []), cancelJob: vi.fn(), retryJob: vi.fn(),
    library: vi.fn(async () => ({ items: [], total: 0 })), collection: vi.fn(), renameCollection: vi.fn(),
    libraryAction: vi.fn(), settings: vi.fn(async () => ({
      download_dir: 'D:\\Downloads', browser_profile_ready: true, browser_ready: true, app_version: '1.0.0',
    })), updateSettings: vi.fn(), selectDirectory: vi.fn(), openLogin: vi.fn(), openDataDirectory: vi.fn(),
    rescan: vi.fn(), onSnapshot: vi.fn(() => vi.fn()),
  }
})

test('renders the download workspace', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><App /></QueryClientProvider>)
  expect(await screen.findByText('粘贴分享内容')).toBeTruthy()
  expect((screen.getByRole('button', { name: '解析链接' }) as HTMLButtonElement).disabled).toBe(true)
})
