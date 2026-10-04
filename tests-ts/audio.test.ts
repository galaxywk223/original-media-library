// @vitest-environment node

import { EventEmitter } from 'node:events'
import { mkdir, stat, writeFile, readFile, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import type { ChildProcessWithoutNullStreams } from 'node:child_process'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { AudioExtractor, resolveFfmpegPath, type AudioExtractorDependencies } from '../src/main/audio'

const directories: string[] = []

afterEach(async () => {
  await Promise.all(directories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

function fakeChild(exitCode: number, outputPath: string, stderrText = ''): ChildProcessWithoutNullStreams {
  const child = new EventEmitter() as ChildProcessWithoutNullStreams
  const stderr = new EventEmitter() as ChildProcessWithoutNullStreams['stderr']
  stderr.setEncoding = vi.fn()
  child.stderr = stderr
  queueMicrotask(async () => {
    if (exitCode === 0) await writeFile(outputPath, Buffer.from('id3'))
    if (stderrText) stderr.emit('data', stderrText)
    child.emit('close', exitCode)
  })
  return child
}

describe('AudioExtractor', () => {
  test('resolves packaged FFmpeg from app.asar.unpacked', async () => {
    expect(resolveFfmpegPath('C:\\Program Files\\original-media-library\\resources\\app.asar\\node_modules\\ffmpeg.exe'))
      .toBe('C:\\Program Files\\original-media-library\\resources\\app.asar.unpacked\\node_modules\\ffmpeg.exe')
    expect(resolveFfmpegPath('C:/Program Files/original-media-library/resources/app.asar/node_modules/ffmpeg.exe'))
      .toBe('C:/Program Files/original-media-library/resources/app.asar.unpacked/node_modules/ffmpeg.exe')
    expect(resolveFfmpegPath('D:\\Code\\Apps\\video-downloader\\node_modules\\ffmpeg.exe'))
      .toBe('D:\\Code\\Apps\\video-downloader\\node_modules\\ffmpeg.exe')
  })

  test('reports a packaged FFmpeg binary as available', async () => {
    const root = await mkdtemp(join(tmpdir(), 'oml-ffmpeg-path-'))
    directories.push(root)
    const packagedPath = join(root, 'resources', 'app.asar', 'node_modules', 'ffmpeg.exe')
    const unpackedPath = packagedPath.replace('app.asar', 'app.asar.unpacked')
    await mkdir(dirname(unpackedPath), { recursive: true })
    await writeFile(unpackedPath, Buffer.from('ffmpeg'))
    expect(new AudioExtractor({ ffmpegPath: packagedPath }).isAvailable()).toBe(true)
  })

  test('extracts MP3 to an atomic output path', async () => {
    const root = await mkdtemp(join(tmpdir(), 'oml-audio-'))
    directories.push(root)
    const source = join(root, 'video.mp4')
    const output = join(root, 'video.mp3')
    await writeFile(source, Buffer.from('video'))
    const spawnMock = vi.fn((_: string, args: string[]) => fakeChild(0, args.at(-1)!))
    const spawn = spawnMock as unknown as NonNullable<AudioExtractorDependencies['spawn']>
    await new AudioExtractor({ ffmpegPath: 'ffmpeg.exe', spawn }).extract(source, output)
    expect(await readFile(output)).toEqual(Buffer.from('id3'))
    await expect(stat(`${output}.part`)).rejects.toThrow()
    expect(spawnMock).toHaveBeenCalledWith('ffmpeg.exe', expect.arrayContaining(['-vn', '-codec:a', 'libmp3lame', '-b:a', '192k', `${output}.part`]), expect.any(Object))
  })

  test('cleans the temporary output when FFmpeg fails', async () => {
    const root = await mkdtemp(join(tmpdir(), 'oml-audio-'))
    directories.push(root)
    const source = join(root, 'video.mp4')
    const output = join(root, 'video.mp3')
    await writeFile(source, Buffer.from('video'))
    const spawn = vi.fn(() => fakeChild(1, output, 'invalid input')) as unknown as NonNullable<AudioExtractorDependencies['spawn']>
    await expect(new AudioExtractor({ ffmpegPath: 'ffmpeg.exe', spawn }).extract(source, output)).rejects.toThrow('invalid input')
    await expect(stat(`${output}.part`)).rejects.toThrow()
    await expect(stat(output)).rejects.toThrow()
  })
})
