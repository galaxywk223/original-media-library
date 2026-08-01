// @vitest-environment node

import { afterEach, describe, expect, test } from 'vitest'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { AppDatabase, toIso } from '../src/main/database'

const directories: string[] = []
afterEach(async () => Promise.all(directories.splice(0).map((path) => rm(path, { recursive: true, force: true }))))

describe('database', () => {
  test('creates the compatible schema and migration marker', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'oml-db-'))
    directories.push(directory)
    const database = new AppDatabase(join(directory, 'library.db'))
    const tables = database.connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all()
      .map((row) => String((row as Record<string, unknown>).name))
    expect(tables).toEqual(expect.arrayContaining(['app_settings', 'download_jobs', 'media_collections', 'media_assets', 'schema_migrations']))
    expect(toIso('2026-08-01 02:22:57.952917')).toBe('2026-08-01T02:22:57.952917Z')
    database.close()
  })
})
