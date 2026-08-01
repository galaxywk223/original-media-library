import { protocol } from 'electron'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { extname } from 'node:path'
import { Readable } from 'node:stream'
import { AppServices } from './services'

const MIME: Record<string, string> = {
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif',
  '.bmp': 'image/bmp', '.avif': 'image/avif', '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime',
  '.mkv': 'video/x-matroska', '.m4v': 'video/x-m4v',
}

export function registerMediaProtocol(services: AppServices): void {
  protocol.handle('oml-media', async (request) => {
    try {
      const url = new URL(request.url)
      if (url.hostname !== 'asset') return new Response('Not found', { status: 404 })
      const [assetId, variant] = url.pathname.split('/').filter(Boolean)
      if (!/^[a-f0-9]{32}$/i.test(assetId) || !['content', 'thumbnail'].includes(variant)) {
        return new Response('Not found', { status: 404 })
      }
      const settings = await services.settings()
      const path = variant === 'thumbnail'
        ? await services.library.thumbnailPath(assetId, settings.download_dir)
        : services.library.assetPath(assetId, settings.download_dir)
      if (!path) return new Response('Not found', { status: 404 })
      const info = await stat(path)
      const range = request.headers.get('range')
      const contentType = variant === 'thumbnail' ? 'image/jpeg' : (MIME[extname(path).toLowerCase()] ?? 'application/octet-stream')
      if (range) {
        const match = range.match(/bytes=(\d+)-(\d*)/)
        if (!match) return new Response(null, { status: 416 })
        const start = Number(match[1])
        const end = match[2] ? Math.min(Number(match[2]), info.size - 1) : info.size - 1
        if (start > end || start >= info.size) return new Response(null, { status: 416 })
        return new Response(Readable.toWeb(createReadStream(path, { start, end })) as ReadableStream, {
          status: 206,
          headers: {
            'Content-Type': contentType, 'Content-Length': String(end - start + 1),
            'Content-Range': `bytes ${start}-${end}/${info.size}`, 'Accept-Ranges': 'bytes',
          },
        })
      }
      return new Response(Readable.toWeb(createReadStream(path)) as ReadableStream, {
        headers: { 'Content-Type': contentType, 'Content-Length': String(info.size), 'Accept-Ranges': 'bytes' },
      })
    } catch {
      return new Response('Not found', { status: 404 })
    }
  })
}
