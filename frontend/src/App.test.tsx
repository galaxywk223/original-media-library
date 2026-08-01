import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import App from './App'

class EventSourceMock {
  addEventListener = vi.fn()
  close = vi.fn()
}

beforeEach(() => {
  vi.stubGlobal('EventSource', EventSourceMock)
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('/api/settings')) {
      return new Response(JSON.stringify({
        download_dir: 'D:\\Downloads',
        browser_profile_ready: true,
        ffmpeg_ready: true,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    if (url.includes('/api/jobs')) {
      return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ items: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
})

test('renders the download workspace', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><App /></QueryClientProvider>)
  expect(await screen.findByText('粘贴分享内容')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '解析链接' })).toBeDisabled()
})
