import { _electron as electron } from '@playwright/test'
import { mkdir, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const dataDir = await mkdtemp(join(tmpdir(), 'oml-screenshots-'))
const downloadDir = join(dataDir, 'downloads')
const outputDir = join(process.cwd(), 'docs', 'screenshots')

await mkdir(join(dataDir, 'browser-profile', 'Default'), { recursive: true })
await mkdir(downloadDir, { recursive: true })
await mkdir(outputDir, { recursive: true })

const application = await electron.launch({
  executablePath: join(process.cwd(), 'node_modules', 'electron', 'dist', 'electron.exe'),
  args: ['.'],
  env: {
    ...process.env,
    ORIGINAL_MEDIA_LIBRARY_DATA_DIR: dataDir,
    ORIGINAL_MEDIA_LIBRARY_DOWNLOAD_DIR: downloadDir,
  },
})

try {
  const window = await application.firstWindow()
  await window.getByRole('heading', { name: '粘贴分享内容' }).waitFor()
  await window.screenshot({ path: join(outputDir, 'download.png') })
  await window.getByRole('button', { name: '设置' }).click()
  await window.getByRole('heading', { name: '设置', exact: true }).waitFor()
  await window.getByLabel('下载目录').fill('C:\\Users\\Public\\Downloads\\素材下载器')
  await window.screenshot({ path: join(outputDir, 'settings.png') })
} finally {
  await application.close()
  await rm(dataDir, { recursive: true, force: true })
}
