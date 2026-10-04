import { existsSync } from 'node:fs'
import ffmpeg from '@ffmpeg-installer/ffmpeg'

if (!existsSync(ffmpeg.path)) {
  throw new Error(`FFmpeg binary not found: ${ffmpeg.path}`)
}

console.log(`FFmpeg ${ffmpeg.version}: ${ffmpeg.path}`)
