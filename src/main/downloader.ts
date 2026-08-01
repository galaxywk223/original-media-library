import puppeteer, { type Browser } from 'puppeteer-core'
import { existsSync } from 'node:fs'
import { access, mkdir, open } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'
import type { AppPaths } from './paths'
import { extractAwemeId, normalizeSourceUrl } from './parser'

const DOUYIN_HOME = 'https://www.douyin.com/'
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36'

export interface DownloadResult {
  sourceUrl: string
  paths: string[]
  title: string | null
  author: string | null
  awemeId: string | null
  sourceCreatedAt: string | null
}

interface Candidate { url: string; kind: 'image' | 'video'; index: number }

export class DownloadCancelled extends Error {}

async function exists(path: string): Promise<boolean> {
  try { await access(path); return true } catch { return false }
}

export class BrowserDownloader {
  private loginBrowser: Browser | null = null

  constructor(private readonly paths: AppPaths) {}

  browserExecutable(): string | null {
    const env = process.env
    const candidates = [
      join(env['PROGRAMFILES(X86)'] || '', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      join(env.PROGRAMFILES || '', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      join(env.PROGRAMFILES || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
      join(env['PROGRAMFILES(X86)'] || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
      join(env.LOCALAPPDATA || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
    ]
    return candidates.find((candidate) => candidate && existsSync(candidate)) ?? null
  }

  async profileReady(): Promise<boolean> {
    return exists(join(this.paths.browserProfileDir, 'Default'))
  }

  loginOpen(): boolean {
    return Boolean(this.loginBrowser?.connected)
  }

  async openLogin(onClosed: () => void): Promise<void> {
    if (this.loginOpen()) throw new Error('登录浏览器已打开')
    const executablePath = this.browserExecutable()
    if (!executablePath) throw new Error('未找到 Microsoft Edge 或 Google Chrome')
    await mkdir(this.paths.browserProfileDir, { recursive: true })
    this.loginBrowser = await puppeteer.launch({
      executablePath,
      userDataDir: this.paths.browserProfileDir,
      headless: false,
      defaultViewport: null,
      args: ['--new-window'],
    })
    this.loginBrowser.on('disconnected', () => {
      this.loginBrowser = null
      onClosed()
    })
    const pages = await this.loginBrowser.pages()
    const page = pages[0] ?? await this.loginBrowser.newPage()
    await page.setUserAgent(USER_AGENT)
    await page.goto(DOUYIN_HOME, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  }

  async close(): Promise<void> {
    await this.loginBrowser?.close().catch(() => undefined)
    this.loginBrowser = null
  }

  async download(
    source: string,
    outputDir: string,
    signal: AbortSignal,
    progress: (current: number, total: number, downloaded: number, itemTotal: number) => void,
  ): Promise<DownloadResult> {
    if (this.loginOpen()) throw new Error('登录浏览器仍在运行，请关闭后重试')
    if (!(await this.profileReady())) throw new Error('需要先打开登录浏览器并完成抖音登录')
    const executablePath = this.browserExecutable()
    if (!executablePath) throw new Error('未找到 Microsoft Edge 或 Google Chrome')
    const url = await normalizeSourceUrl(source)
    const browser = await puppeteer.launch({
      executablePath,
      userDataDir: this.paths.browserProfileDir,
      headless: true,
      defaultViewport: { width: 1280, height: 900 },
    })
    try {
      const page = (await browser.pages())[0] ?? await browser.newPage()
      await page.setUserAgent(USER_AGENT)
      const detailPromise = new Promise<Record<string, any>>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('页面未返回媒体详情，登录状态可能已失效')), 60_000)
        const abort = () => { clearTimeout(timeout); reject(new DownloadCancelled('下载已取消')) }
        signal.addEventListener('abort', abort, { once: true })
        page.on('response', async (response) => {
          if (!response.url().includes('/aweme/v1/web/aweme/detail/')) return
          try {
            const data = await response.json() as { aweme_detail?: Record<string, any> }
            if (data.aweme_detail) {
              clearTimeout(timeout)
              signal.removeEventListener('abort', abort)
              resolve(data.aweme_detail)
            }
          } catch { /* another response may contain the detail */ }
        })
      })
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 })
      const detail = await detailPromise
      const candidates = this.candidates(detail)
      if (!candidates.length) throw new Error('页面详情中没有找到原始媒体')
      const title = safeName(detail.desc || detail.aweme_id || 'douyin')
      await mkdir(outputDir, { recursive: true })
      const paths: string[] = []
      for (const candidate of candidates) {
        if (signal.aborted) throw new DownloadCancelled('下载已取消')
        paths.push(await this.downloadCandidate(candidate, candidates.length, outputDir, title, signal, progress))
      }
      const author = typeof detail.author?.nickname === 'string' ? detail.author.nickname : null
      const created = typeof detail.create_time === 'number' ? new Date(detail.create_time * 1000).toISOString() : null
      return {
        sourceUrl: url, paths, title: typeof detail.desc === 'string' ? detail.desc : null, author,
        awemeId: String(detail.aweme_id || extractAwemeId(url) || '') || null, sourceCreatedAt: created,
      }
    } finally {
      await browser.close().catch(() => undefined)
    }
  }

  private candidates(detail: Record<string, any>): Candidate[] {
    if (Array.isArray(detail.images) && detail.images.length) {
      return detail.images.flatMap((image: any, index: number) => {
        const urls = image?.download_url_list || image?.url_list
        return Array.isArray(urls) && urls[0] ? [{ url: String(urls[0]), kind: 'image' as const, index: index + 1 }] : []
      })
    }
    const video = detail.video
    for (const key of ['play_addr', 'download_addr']) {
      const urls = video?.[key]?.url_list || video?.[key]?.download_url_list
      if (Array.isArray(urls) && urls[0]) return [{ url: String(urls[0]), kind: 'video', index: 1 }]
    }
    return []
  }

  private async downloadCandidate(
    candidate: Candidate, total: number, outputDir: string, title: string, signal: AbortSignal,
    progress: (current: number, total: number, downloaded: number, itemTotal: number) => void,
  ): Promise<string> {
    const response = await fetch(candidate.url, { headers: { 'User-Agent': USER_AGENT, Referer: DOUYIN_HOME }, signal })
    if (!response.ok || !response.body) throw new Error(`媒体请求失败 (${response.status})`)
    const extension = extensionFor(candidate.url, response.headers.get('content-type') || '', candidate.kind)
    const suffix = total > 1 ? `_${String(candidate.index).padStart(2, '0')}` : '_01'
    const path = await uniquePath(join(outputDir, `${title}${suffix}${extension}`))
    const partial = `${path}.part`
    const file = await open(partial, 'w')
    const reader = response.body.getReader()
    const itemTotal = Number(response.headers.get('content-length') || 0)
    let downloaded = 0
    try {
      while (true) {
        if (signal.aborted) throw new DownloadCancelled('下载已取消')
        const { done, value } = await reader.read()
        if (done) break
        await file.write(value)
        downloaded += value.byteLength
        progress(candidate.index, total, downloaded, itemTotal)
      }
      await file.close()
      const { rename } = await import('node:fs/promises')
      await rename(partial, path)
      return path
    } catch (error) {
      await file.close().catch(() => undefined)
      const { unlink } = await import('node:fs/promises')
      await unlink(partial).catch(() => undefined)
      throw error
    }
  }
}

function safeName(value: unknown): string {
  return String(value).trim().slice(0, 80).replace(/[<>:"/\\|?*\r\n]+/g, '_').replace(/[ .]+$/, '') || 'douyin'
}

function extensionFor(url: string, contentType: string, kind: 'image' | 'video'): string {
  const extension = extname(new URL(url).pathname).toLowerCase()
  if (['.mp4', '.webm', '.mov', '.jpg', '.jpeg', '.png', '.webp'].includes(extension)) return extension
  if (contentType.includes('png')) return '.png'
  if (contentType.includes('jpeg') || contentType.includes('jpg')) return '.jpg'
  if (contentType.includes('webp')) return '.webp'
  return kind === 'video' ? '.mp4' : '.bin'
}

async function uniquePath(path: string): Promise<string> {
  if (!(await exists(path)) && !(await exists(`${path}.part`))) return path
  const extension = extname(path)
  const stem = basename(path, extension)
  const directory = path.slice(0, path.length - basename(path).length)
  for (let number = 2; number < 10_000; number += 1) {
    const candidate = join(directory, `${stem} (${number})${extension}`)
    if (!(await exists(candidate)) && !(await exists(`${candidate}.part`))) return candidate
  }
  throw new Error('无法生成不冲突的文件名')
}
