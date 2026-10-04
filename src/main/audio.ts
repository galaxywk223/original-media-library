import ffmpeg from '@ffmpeg-installer/ffmpeg'
import { spawn as defaultSpawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { existsSync } from 'node:fs'
import { unlink } from 'node:fs/promises'

export interface AudioExtractorDependencies {
  ffmpegPath?: string
  spawn?: typeof defaultSpawn
}

export function resolveFfmpegPath(path: string): string {
  return path.replace(/app\.asar([\\/])/i, 'app.asar.unpacked$1')
}

export class AudioExtractor {
  private readonly ffmpegPath: string
  private readonly spawn: typeof defaultSpawn

  constructor(dependencies: AudioExtractorDependencies = {}) {
    this.ffmpegPath = resolveFfmpegPath(dependencies.ffmpegPath ?? ffmpeg.path)
    this.spawn = dependencies.spawn ?? defaultSpawn
  }

  isAvailable(): boolean {
    return existsSync(this.ffmpegPath)
  }

  async extract(sourcePath: string, outputPath: string): Promise<void> {
    const temporaryPath = `${outputPath}.part`
    await unlink(temporaryPath).catch(() => undefined)
    const child = this.spawnProcess(sourcePath, temporaryPath)
    let stderr = ''
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', (chunk: string) => { stderr += chunk })
    try {
      const code = await new Promise<number>((resolve, reject) => {
        child.once('error', reject)
        child.once('close', (exitCode) => resolve(exitCode ?? 1))
      })
      if (code !== 0) throw new Error(stderr.trim() || `FFmpeg 转换失败（退出码 ${code}）`)
      const { rename } = await import('node:fs/promises')
      await rename(temporaryPath, outputPath)
    } catch (error) {
      await unlink(temporaryPath).catch(() => undefined)
      if (error instanceof Error && error.message) throw error
      throw new Error('FFmpeg 转换失败')
    }
  }

  private spawnProcess(sourcePath: string, outputPath: string): ChildProcessWithoutNullStreams {
    return this.spawn(this.ffmpegPath, [
      '-hide_banner', '-loglevel', 'error', '-y', '-i', sourcePath,
      '-vn', '-codec:a', 'libmp3lame', '-b:a', '192k', outputPath,
    ], { windowsHide: true }) as ChildProcessWithoutNullStreams
  }
}
