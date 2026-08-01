import { app } from 'electron'
import { cp, mkdir, stat } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'

export interface AppPaths {
  root: string
  dataDir: string
  database: string
  thumbnailDir: string
  browserProfileDir: string
  defaultDownloadDir: string
}

async function exists(path: string): Promise<boolean> {
  try {
    await stat(path)
    return true
  } catch {
    return false
  }
}

export async function buildPaths(): Promise<AppPaths> {
  const root = resolve(app.getAppPath(), app.isPackaged ? '..' : '.')
  const localAppData = process.env.LOCALAPPDATA || app.getPath('appData')
  const overriddenDataDir = process.env.ORIGINAL_MEDIA_LIBRARY_DATA_DIR
  const dataDir = overriddenDataDir || join(localAppData, 'OriginalMediaLibrary')
  const paths: AppPaths = {
    root,
    dataDir,
    database: join(dataDir, 'library.db'),
    thumbnailDir: join(dataDir, 'thumbnails'),
    browserProfileDir: join(dataDir, 'browser-profile'),
    defaultDownloadDir: process.env.ORIGINAL_MEDIA_LIBRARY_DOWNLOAD_DIR || join(app.getPath('downloads'), '原片库'),
  }
  await mkdir(paths.thumbnailDir, { recursive: true })
  await mkdir(paths.defaultDownloadDir, { recursive: true })

  if (!app.isPackaged && !overriddenDataDir) {
    const legacyData = join(root, '.app-data')
    const legacyBrowser = join(root, '.browser-profile')
    if (!(await exists(paths.database)) && (await exists(join(legacyData, 'library.db')))) {
      await mkdir(dirname(paths.database), { recursive: true })
      await cp(join(legacyData, 'library.db'), paths.database, { errorOnExist: true })
    }
    if (!(await exists(paths.browserProfileDir)) && (await exists(legacyBrowser))) {
      await cp(legacyBrowser, paths.browserProfileDir, { recursive: true, errorOnExist: true })
    }
  }
  return paths
}
