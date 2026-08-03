// @vitest-environment node

import { EventEmitter } from 'node:events'
import type { AppUpdater } from 'electron-updater'
import { describe, expect, test, vi } from 'vitest'
import { UpdateManager } from '../src/main/updater'

class FakeUpdater extends EventEmitter {
  autoDownload = false
  autoInstallOnAppQuit = true
  allowPrerelease = true
  checkForUpdates = vi.fn<() => Promise<null>>(async () => null)
  quitAndInstall = vi.fn()
}

function createManager(client = new FakeUpdater(), supported = true) {
  const notifications: string[] = []
  const onDownloaded = vi.fn()
  const confirmInstall = vi.fn(async () => true)
  const beforeInstall = vi.fn(async () => undefined)
  const manager = new UpdateManager({
    client: client as unknown as AppUpdater,
    currentVersion: '1.1.2',
    supported,
    notify: (status) => notifications.push(status.phase),
    confirmInstall,
    beforeInstall,
    onDownloaded,
  })
  return { manager, client, notifications, onDownloaded, confirmInstall, beforeInstall }
}

describe('UpdateManager', () => {
  test('disables network checks outside packaged builds', async () => {
    const { manager, client } = createManager(new FakeUpdater(), false)
    expect(manager.getStatus().phase).toBe('unsupported')
    expect((await manager.check()).phase).toBe('unsupported')
    expect(client.checkForUpdates).not.toHaveBeenCalled()
  })

  test('reports the current version when no update is available', async () => {
    const client = new FakeUpdater()
    client.checkForUpdates.mockImplementation(async () => {
      client.emit('checking-for-update')
      client.emit('update-not-available', { version: '1.1.2' })
      return null
    })
    const { manager, notifications } = createManager(client)

    expect((await manager.check()).phase).toBe('up-to-date')
    expect(notifications).toEqual(['checking', 'checking', 'up-to-date'])
  })

  test('tracks an available update through download completion', async () => {
    const client = new FakeUpdater()
    client.checkForUpdates.mockImplementation(async () => {
      client.emit('update-available', { version: '1.2.0' })
      client.emit('download-progress', { percent: 43.6 })
      client.emit('update-downloaded', { version: '1.2.0' })
      return null
    })
    const { manager, onDownloaded } = createManager(client)

    const status = await manager.check()
    expect(status).toMatchObject({
      phase: 'downloaded', available_version: '1.2.0', download_percent: 100,
    })
    expect(onDownloaded).toHaveBeenCalledWith(status)
  })

  test('turns check failures into a retryable error state', async () => {
    const client = new FakeUpdater()
    client.checkForUpdates.mockRejectedValue(new Error('network unavailable'))
    const { manager } = createManager(client)

    expect(await manager.check()).toMatchObject({ phase: 'error', message: 'network unavailable' })
  })

  test('deduplicates concurrent checks', async () => {
    const client = new FakeUpdater()
    let resolveCheck: (() => void) | undefined
    client.checkForUpdates.mockImplementation(() => new Promise<null>((resolve) => {
      resolveCheck = () => resolve(null)
    }))
    const { manager } = createManager(client)

    const first = manager.check()
    const second = manager.check()
    expect(second).toBe(first)
    expect(client.checkForUpdates).toHaveBeenCalledTimes(1)
    resolveCheck?.()
    await first
  })

  test('installs only after confirmation and shutdown preparation', async () => {
    const client = new FakeUpdater()
    const { manager, confirmInstall, beforeInstall } = createManager(client)
    client.emit('update-downloaded', { version: '1.2.0' })

    confirmInstall.mockResolvedValueOnce(false)
    expect(await manager.install()).toEqual({ started: false })
    expect(client.quitAndInstall).not.toHaveBeenCalled()

    expect(await manager.install()).toEqual({ started: true })
    expect(beforeInstall).toHaveBeenCalledTimes(1)
    expect(client.quitAndInstall).toHaveBeenCalledWith(false, true)
  })
})
