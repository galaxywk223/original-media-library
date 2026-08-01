import { _electron as electron, expect, test } from '@playwright/test'
import { mkdir, mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

test('desktop shell renders and navigates to the empty library', async () => {
  const dataDir = await mkdtemp(join(tmpdir(), 'oml-e2e-'))
  const downloadDir = join(dataDir, 'downloads')
  await mkdir(join(dataDir, 'browser-profile', 'Default'), { recursive: true })
  await mkdir(downloadDir, { recursive: true })
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
    await expect(window).toHaveTitle('原片库')
    await expect(window.getByRole('heading', { name: '粘贴分享内容' })).toBeVisible()
    await window.getByRole('button', { name: '媒体库' }).click()
    await expect(window.getByRole('heading', { name: '媒体库', exact: true })).toBeVisible()
    await expect(window.getByRole('heading', { name: '媒体库为空' })).toBeVisible()
  } finally {
    await application.close()
    await rm(dataDir, { recursive: true, force: true })
  }
})
