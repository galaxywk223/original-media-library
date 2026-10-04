import { app, BrowserWindow, dialog, protocol } from 'electron'
import updaterPackage from 'electron-updater'
import { join } from 'node:path'
import { buildPaths } from './paths'
import { AppServices } from './services'
import { registerIpc } from './ipc'
import { registerMediaProtocol } from './media-protocol'
import { UpdateManager } from './updater'

const { autoUpdater } = updaterPackage

protocol.registerSchemesAsPrivileged([{
  scheme: 'oml-media',
  privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true },
}])

const localAppData = process.env.LOCALAPPDATA || app.getPath('appData')
app.setPath('userData', process.env.ORIGINAL_MEDIA_LIBRARY_DATA_DIR || join(localAppData, 'OriginalMediaLibrary'))

let mainWindow: BrowserWindow | null = null
let services: AppServices | null = null
let updates: UpdateManager | null = null
let quitting = false
let shutdownPromise: Promise<void> | null = null

function shutdown(): Promise<void> {
  shutdownPromise ??= services?.stop() ?? Promise.resolve()
  return shutdownPromise
}

async function createWindow(): Promise<BrowserWindow> {
  const window = new BrowserWindow({
    title: '素材下载器',
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    backgroundColor: '#f4f5f7',
    show: false,
    webPreferences: {
      preload: join(__dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith('file:') && !url.startsWith('http://127.0.0.1:')) event.preventDefault()
  })
  window.on('close', (event) => {
    if (quitting || !services?.jobs.hasActive()) return
    const choice = dialog.showMessageBoxSync(window, {
      type: 'warning', title: '退出素材下载器', message: '当前仍有下载任务',
      detail: '退出后未完成任务将标记为中断，可在下次启动后重试。',
      buttons: ['继续下载', '退出应用'], defaultId: 0, cancelId: 0,
    })
    if (choice === 0) event.preventDefault()
  })
  window.once('ready-to-show', () => window.show())
  const devUrl = process.env.ELECTRON_RENDERER_URL
  if (devUrl) await window.loadURL(devUrl)
  else await window.loadFile(join(__dirname, '../renderer/index.html'))
  return window
}

async function confirmUpdateInstall(): Promise<boolean> {
  if (!services?.jobs.hasActive()) return true
  const options = {
    type: 'warning' as const,
    title: '重启并更新',
    message: '当前仍有下载任务',
    detail: '重启更新会中断未完成任务，可在下次启动后重试。',
    buttons: ['继续下载', '重启更新'],
    defaultId: 0,
    cancelId: 0,
  }
  const result = mainWindow
    ? await dialog.showMessageBox(mainWindow, options)
    : await dialog.showMessageBox(options)
  return result.response === 1
}

async function promptForDownloadedUpdate(version: string | null): Promise<void> {
  if (!mainWindow || mainWindow.isDestroyed() || !updates) return
  const result = await dialog.showMessageBox(mainWindow, {
    type: 'info',
    title: '更新已下载',
    message: version ? `素材下载器 v${version} 已准备就绪` : '新版素材下载器已准备就绪',
    detail: '重启应用后完成更新安装。',
    buttons: ['稍后', '重启更新'],
    defaultId: 1,
    cancelId: 0,
  })
  if (result.response === 1) await updates.install()
}

async function start(): Promise<void> {
  if (process.argv.includes('--version')) {
    process.stdout.write(`${app.getVersion()}\n`)
    app.quit()
    return
  }
  const paths = await buildPaths()
  services = new AppServices(paths)
  await services.initialize()
  if (!services.audio.isAvailable()) throw new Error('FFmpeg 转换引擎不可用')
  if (process.argv.includes('--self-test')) {
    const settings = await services.settings()
    process.stdout.write(`${JSON.stringify({ status: 'ok', database: paths.database, settings })}\n`)
    await shutdown()
    app.exit(0)
    return
  }
  updates = new UpdateManager({
    client: autoUpdater,
    currentVersion: app.getVersion(),
    supported: app.isPackaged,
    notify: (status) => {
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('events:update-status', status)
    },
    confirmInstall: confirmUpdateInstall,
    beforeInstall: async () => {
      await shutdown()
      quitting = true
    },
    onDownloaded: (status) => { void promptForDownloadedUpdate(status.available_version) },
  })
  registerIpc(services, updates)
  registerMediaProtocol(services)
  mainWindow = await createWindow()
  services.attachWindow(mainWindow)
  updates.startAutoCheck()
  mainWindow.on('closed', () => { mainWindow = null })
}

const hasLock = app.requestSingleInstanceLock()
if (!hasLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (!mainWindow) return
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    mainWindow.focus()
  })
  app.whenReady().then(start).catch((error) => {
    dialog.showErrorBox('素材下载器启动失败', error instanceof Error ? error.message : String(error))
    app.exit(1)
  })
  app.on('activate', () => {
    if (mainWindow) mainWindow.show()
  })
  app.on('window-all-closed', () => app.quit())
  app.on('before-quit', (event) => {
    if (quitting || !services) return
    event.preventDefault()
    quitting = true
    updates?.stop()
    void shutdown().finally(() => app.exit(0))
  })
}
