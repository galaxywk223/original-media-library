import { ipcMain } from 'electron'
import { z } from 'zod'
import { AppServices } from './services'

const id = z.string().regex(/^[a-f0-9]{32}$/i)
const parseRequest = z.object({ text: z.string().min(1) })
const createJobsRequest = z.object({ urls: z.array(z.string().url()).min(1), force: z.boolean() })
const libraryQuery = z.object({
  search: z.string(), media_type: z.enum(['all', 'image', 'video']), sort: z.enum(['newest', 'oldest', 'name', 'size']),
  page: z.number().int().positive().optional(), page_size: z.number().int().min(1).max(200).optional(),
})

export function registerIpc(services: AppServices): void {
  ipcMain.handle('sources:parse', (_, payload) => services.parse(parseRequest.parse(payload).text))
  ipcMain.handle('jobs:create', (_, payload) => {
    const input = createJobsRequest.parse(payload)
    return services.createJobs(input.urls, input.force)
  })
  ipcMain.handle('jobs:list', () => services.jobs.list())
  ipcMain.handle('jobs:cancel', (_, payload) => services.jobs.cancel(z.object({ id }).parse(payload).id))
  ipcMain.handle('jobs:retry', (_, payload) => services.jobs.retry(z.object({ id }).parse(payload).id))
  ipcMain.handle('library:list', (_, payload) => services.queryLibrary(libraryQuery.parse(payload)))
  ipcMain.handle('library:get', (_, payload) => services.getCollection(z.object({ id }).parse(payload).id))
  ipcMain.handle('library:rename', (_, payload) => {
    const input = z.object({ id, title: z.string().min(1).max(120) }).parse(payload)
    return services.renameCollection(input.id, input.title)
  })
  ipcMain.handle('library:action', (_, payload) => {
    const input = z.object({ action: z.enum(['open', 'reveal', 'trash']), ids: z.array(id).min(1) }).parse(payload)
    return services.libraryAction(input.action, input.ids).then((affected) => ({ affected }))
  })
  ipcMain.handle('settings:get', () => services.settings())
  ipcMain.handle('settings:update', (_, payload) => services.updateSettings(z.object({ downloadDir: z.string().min(1) }).parse(payload).downloadDir))
  ipcMain.handle('settings:select-directory', () => services.selectDirectory())
  ipcMain.handle('auth:open-login', () => services.openLogin())
  ipcMain.handle('system:open-data-directory', () => services.openDataDirectory())
  ipcMain.handle('library:rescan', () => services.rescan())
}
