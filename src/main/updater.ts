import type { AppUpdater, ProgressInfo, UpdateInfo } from 'electron-updater'
import type { UpdateStatus } from '../shared/contracts'

interface UpdateManagerOptions {
  client: AppUpdater
  currentVersion: string
  supported: boolean
  notify: (status: UpdateStatus) => void
  confirmInstall: () => Promise<boolean>
  beforeInstall: () => Promise<void>
  onDownloaded: (status: UpdateStatus) => void
}

export class UpdateManager {
  private status: UpdateStatus
  private checkPromise: Promise<UpdateStatus> | null = null
  private autoCheckTimer: NodeJS.Timeout | null = null

  constructor(private readonly options: UpdateManagerOptions) {
    this.status = {
      phase: options.supported ? 'idle' : 'unsupported',
      current_version: options.currentVersion,
      available_version: null,
      download_percent: null,
      message: options.supported ? null : '仅正式安装版支持应用内更新',
    }
    if (!options.supported) return

    options.client.autoDownload = true
    options.client.autoInstallOnAppQuit = false
    options.client.allowPrerelease = false
    options.client.on('checking-for-update', () => this.setStatus({ phase: 'checking', message: null }))
    options.client.on('update-not-available', () => this.setStatus({
      phase: 'up-to-date', available_version: null, download_percent: null, message: null,
    }))
    options.client.on('update-available', (info: UpdateInfo) => this.setStatus({
      phase: 'available', available_version: info.version, download_percent: 0, message: null,
    }))
    options.client.on('download-progress', (info: ProgressInfo) => this.setStatus({
      phase: 'downloading', download_percent: Math.round(Math.max(0, Math.min(100, info.percent))), message: null,
    }))
    options.client.on('update-downloaded', (info: UpdateInfo) => {
      const status = this.setStatus({
        phase: 'downloaded', available_version: info.version, download_percent: 100, message: null,
      })
      options.onDownloaded(status)
    })
    options.client.on('error', (error: Error) => this.setError(error))
  }

  getStatus(): UpdateStatus {
    return { ...this.status }
  }

  startAutoCheck(delayMs = 3000): void {
    if (!this.options.supported || this.autoCheckTimer) return
    this.autoCheckTimer = setTimeout(() => {
      this.autoCheckTimer = null
      void this.check()
    }, delayMs)
  }

  stop(): void {
    if (this.autoCheckTimer) clearTimeout(this.autoCheckTimer)
    this.autoCheckTimer = null
  }

  check(): Promise<UpdateStatus> {
    if (!this.options.supported) return Promise.resolve(this.getStatus())
    if (this.checkPromise) return this.checkPromise
    if (this.status.phase === 'downloading' || this.status.phase === 'downloaded') {
      return Promise.resolve(this.getStatus())
    }

    this.setStatus({ phase: 'checking', message: null, download_percent: null })
    this.checkPromise = this.options.client.checkForUpdates()
      .then(() => this.getStatus())
      .catch((error: unknown) => {
        this.setError(error)
        return this.getStatus()
      })
      .finally(() => { this.checkPromise = null })
    return this.checkPromise
  }

  async install(): Promise<{ started: boolean }> {
    if (this.status.phase !== 'downloaded') throw new Error('尚未下载可安装的更新')
    if (!(await this.options.confirmInstall())) return { started: false }
    await this.options.beforeInstall()
    this.options.client.quitAndInstall(false, true)
    return { started: true }
  }

  private setStatus(update: Partial<UpdateStatus>): UpdateStatus {
    this.status = { ...this.status, ...update }
    const snapshot = this.getStatus()
    this.options.notify(snapshot)
    return snapshot
  }

  private setError(error: unknown): void {
    const message = error instanceof Error ? error.message : String(error)
    this.setStatus({ phase: 'error', download_percent: null, message })
  }
}
