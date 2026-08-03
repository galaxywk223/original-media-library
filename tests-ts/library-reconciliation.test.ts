// @vitest-environment node

import { mkdir, mkdtemp, rm, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, test, vi } from 'vitest'
import type { BrowserDownloader } from '../src/main/downloader'
import { AppDatabase, sqliteNow } from '../src/main/database'
import { JobManager } from '../src/main/jobs'
import { LibraryService, MediaPathConflictError } from '../src/main/library'
import type { AppPaths } from '../src/main/paths'

vi.mock('electron', () => ({
  nativeImage: {
    createFromPath: () => ({ getSize: () => ({ width: 0, height: 0 }) }),
    createThumbnailFromPath: async () => ({ isEmpty: () => true }),
  },
  shell: { openPath: vi.fn(), showItemInFolder: vi.fn(), trashItem: vi.fn() },
}))

interface TestContext {
  paths: AppPaths
  database: AppDatabase
  library: LibraryService
}

const contexts: TestContext[] = []

afterEach(async () => {
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  for (const context of contexts.splice(0)) {
    context.database.close()
    await rm(context.paths.root, { recursive: true, force: true })
  }
})

async function createContext(): Promise<TestContext> {
  const root = await mkdtemp(join(tmpdir(), 'media-library-reconcile-'))
  const paths: AppPaths = {
    root,
    dataDir: root,
    database: join(root, 'library.db'),
    thumbnailDir: join(root, 'thumbnails'),
    browserProfileDir: join(root, 'browser-profile'),
    defaultDownloadDir: join(root, 'downloads'),
  }
  await mkdir(paths.thumbnailDir, { recursive: true })
  await mkdir(paths.defaultDownloadDir, { recursive: true })
  const database = new AppDatabase(paths.database)
  const library = new LibraryService(database, paths, vi.fn())
  const context = { paths, database, library }
  contexts.push(context)
  return context
}

function input(paths: string[], awemeId = '123') {
  return {
    title: '测试作品', author: '作者', sourceUrl: `https://www.douyin.com/video/${awemeId}`,
    awemeId, sourceCreatedAt: '2026-08-01T00:00:00.000Z', paths,
  }
}

function count(database: AppDatabase, table: string): number {
  return Number((database.connection.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get() as { count: number }).count)
}

describe('download registration reconciliation', () => {
  test('adopts a file indexed by a concurrent scan and remains idempotent', async () => {
    const { paths, database, library } = await createContext()
    const path = join(paths.defaultDownloadDir, '测试作品_01.mp4')
    await writeFile(path, new Uint8Array([1, 2, 3, 4]))

    const scan = library.scan(paths.defaultDownloadDir)
    const registration = library.addDownload(input([path]))
    await scan
    const collection = await registration
    const repeated = await library.addDownload(input([path]))

    expect(repeated.id).toBe(collection.id)
    expect(count(database, 'media_collections')).toBe(1)
    expect(count(database, 'media_assets')).toBe(1)
    expect(database.connection.prepare('SELECT imported, aweme_id FROM media_collections').get())
      .toEqual({ imported: 0, aweme_id: '123' })
  })

  test('rejects a path owned by a different completed download', async () => {
    const { paths, database, library } = await createContext()
    const path = join(paths.defaultDownloadDir, 'conflict.mp4')
    await writeFile(path, new Uint8Array([1]))
    await library.addDownload(input([path], '123'))

    await expect(library.addDownload(input([path], '456'))).rejects.toBeInstanceOf(MediaPathConflictError)
    expect(count(database, 'media_collections')).toBe(1)
    expect(count(database, 'media_assets')).toBe(1)
  })

  test('moves every item from a scanned multi-file collection', async () => {
    const { paths, database, library } = await createContext()
    const first = join(paths.defaultDownloadDir, '多图作品_01.jpg')
    const second = join(paths.defaultDownloadDir, '多图作品_02.jpg')
    await writeFile(first, new Uint8Array([1, 2]))
    await writeFile(second, new Uint8Array([3, 4, 5]))
    await library.scan(paths.defaultDownloadDir)

    const collection = await library.addDownload(input([first, second]))

    expect(collection.item_count).toBe(2)
    expect(collection.assets?.map((asset) => asset.sequence)).toEqual([1, 2])
    expect(count(database, 'media_collections')).toBe(1)
    expect(database.connection.prepare('SELECT imported FROM media_collections').get()).toEqual({ imported: 0 })
  })

  test('rolls back the collection when asset insertion fails', async () => {
    const { paths, database, library } = await createContext()
    const path = join(paths.defaultDownloadDir, 'rollback.mp4')
    await writeFile(path, new Uint8Array([1]))
    database.connection.exec(`CREATE TRIGGER reject_asset BEFORE INSERT ON media_assets
      BEGIN SELECT RAISE(ABORT, 'forced asset failure'); END`)

    await expect(library.addDownload(input([path]))).rejects.toThrow('forced asset failure')
    expect(count(database, 'media_collections')).toBe(0)
    expect(count(database, 'media_assets')).toBe(0)
  })

  test('restores scanned ownership when moving an asset fails', async () => {
    const { paths, database, library } = await createContext()
    const path = join(paths.defaultDownloadDir, 'move-rollback.mp4')
    await writeFile(path, new Uint8Array([1]))
    await library.scan(paths.defaultDownloadDir)
    const imported = database.connection.prepare('SELECT id FROM media_collections').get() as { id: string }
    database.connection.exec(`CREATE TRIGGER reject_asset_move BEFORE UPDATE OF collection_id ON media_assets
      BEGIN SELECT RAISE(ABORT, 'forced asset move failure'); END`)

    await expect(library.addDownload(input([path]))).rejects.toThrow('forced asset move failure')
    expect(count(database, 'media_collections')).toBe(1)
    expect(database.connection.prepare('SELECT collection_id FROM media_assets').get())
      .toEqual({ collection_id: imported.id })
    expect(database.connection.prepare('SELECT imported FROM media_collections').get()).toEqual({ imported: 1 })
  })
})

interface RecoveryFixture {
  context: TestContext
  files: string[]
  manager: JobManager
}

async function createRecoveryFixture(candidateCount = 1): Promise<RecoveryFixture> {
  const context = await createContext()
  const { paths, database, library } = context
  const files: string[] = []
  for (let index = 0; index < candidateCount; index += 1) {
    const directory = index ? join(paths.defaultDownloadDir, `candidate-${index}`) : paths.defaultDownloadDir
    await mkdir(directory, { recursive: true })
    const path = join(directory, '待恢复作品_01.mp4')
    await writeFile(path, new Uint8Array([1, 2, 3, 4]))
    files.push(path)
  }
  await library.scan(paths.defaultDownloadDir)
  const createdAt = '2000-01-01 00:00:00.000'
  const completedAt = '2099-01-01 00:00:00.000'
  const now = sqliteNow()
  database.connection.prepare(`INSERT INTO media_collections
    (id, aweme_id, source_url, title, author, media_type, item_count, imported,
     source_created_at, created_at, updated_at)
    VALUES ('empty-official', '7668271556358966569', 'https://www.douyin.com/video/7668271556358966569',
      '待恢复作品', '栖光', 'video', 1, 0, '2026-07-30T10:50:08.000Z', ?, ?)`).run(now, now)
  database.connection.prepare(`INSERT INTO download_jobs
    (id, source_url, aweme_id, title, status, progress, downloaded_bytes, total_bytes,
     current_item, total_items, error, output_dir, force, collection_id, created_at, updated_at, completed_at)
    VALUES ('failed-job', 'https://www.douyin.com/video/7668271556358966569', '7668271556358966569',
      NULL, 'failed', 0.999, 4, 4, 1, 1, 'UNIQUE constraint failed: media_assets.path',
      ?, 0, NULL, ?, ?, ?)`).run(paths.defaultDownloadDir, createdAt, completedAt, completedAt)
  const manager = new JobManager(database, null as unknown as BrowserDownloader, library, vi.fn())
  return { context, files, manager }
}

describe('indexed failure recovery', () => {
  test('promotes the unique imported file and completes the existing job without downloading', async () => {
    const { context, files, manager } = await createRecoveryFixture()
    const { database } = context

    expect(await manager.recoverIndexedFailures()).toBe(1)

    const job = database.connection.prepare('SELECT * FROM download_jobs WHERE id = ?').get('failed-job') as Record<string, unknown>
    const collection = database.connection.prepare('SELECT * FROM media_collections').get() as Record<string, unknown>
    expect(job).toMatchObject({ status: 'completed', progress: 1, error: null, collection_id: collection.id })
    expect(collection).toMatchObject({ imported: 0, aweme_id: '7668271556358966569', author: '栖光' })
    expect(count(database, 'media_collections')).toBe(1)
    expect((database.connection.prepare('SELECT path FROM media_assets').get() as { path: string }).path).toBe(files[0])
    expect(await manager.recoverIndexedFailures()).toBe(0)
  })

  test('leaves the failure unchanged when the media file is missing', async () => {
    const { context, files, manager } = await createRecoveryFixture()
    await unlink(files[0])

    expect(await manager.recoverIndexedFailures()).toBe(0)
    expect(context.database.connection.prepare('SELECT status FROM download_jobs').get()).toEqual({ status: 'failed' })
  })

  test('leaves the failure unchanged when the downloaded size does not match', async () => {
    const { context, manager } = await createRecoveryFixture()
    context.database.connection.prepare('UPDATE download_jobs SET downloaded_bytes = 5').run()

    expect(await manager.recoverIndexedFailures()).toBe(0)
    expect(context.database.connection.prepare('SELECT status FROM download_jobs').get()).toEqual({ status: 'failed' })
  })

  test('does not choose between multiple matching imported collections', async () => {
    const { context, manager } = await createRecoveryFixture(2)

    expect(await manager.recoverIndexedFailures()).toBe(0)
    await context.library.scan(context.paths.defaultDownloadDir)
    expect(context.database.connection.prepare('SELECT status FROM download_jobs').get()).toEqual({ status: 'failed' })
    expect(count(context.database, 'media_collections')).toBe(3)
  })
})
