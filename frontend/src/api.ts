import type { Collection, Job, LibraryResult, ParsedSource, Settings, UpdateStatus } from './types'

async function call<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation()
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(message.replace(/^Error invoking remote method '[^']+': Error: /, ''))
  }
}

export const api = {
  parse: (text: string) => call<{ sources: ParsedSource[] }>(() => window.originalMedia.parse(text)),
  createJobs: (urls: string[], force = false) => call(() => window.originalMedia.createJobs(urls, force)),
  jobs: () => call<Job[]>(window.originalMedia.jobs),
  cancelJob: (id: string) => call<Job>(() => window.originalMedia.cancelJob(id)),
  retryJob: (id: string) => call<Job>(() => window.originalMedia.retryJob(id)),
  library: (query: URLSearchParams) => call<LibraryResult>(() => window.originalMedia.library({
    search: query.get('search') ?? '', media_type: query.get('media_type') ?? 'all', sort: query.get('sort') ?? 'newest',
  })),
  collection: (id: string) => call<Collection>(() => window.originalMedia.collection(id)),
  extractAudio: (collectionId: string, assetId: string) => call<Collection>(() => window.originalMedia.extractAudio(collectionId, assetId)),
  renameCollection: (id: string, title: string) => call<Collection>(() => window.originalMedia.renameCollection(id, title)),
  libraryAction: (action: 'open' | 'reveal' | 'trash', ids: string[]) => call<{ affected: number }>(() => window.originalMedia.libraryAction(action, ids)),
  settings: () => call<Settings>(window.originalMedia.settings),
  updateSettings: (downloadDir: string) => call<Settings>(() => window.originalMedia.updateSettings(downloadDir)),
  selectDirectory: () => call<Settings>(window.originalMedia.selectDirectory),
  openLogin: () => call<{ opened: boolean }>(window.originalMedia.openLogin),
  openDataDirectory: () => call<{ opened: boolean }>(window.originalMedia.openDataDirectory),
  rescan: () => call<{ started: boolean }>(window.originalMedia.rescan),
  getUpdateStatus: () => call<UpdateStatus>(window.originalMedia.getUpdateStatus),
  checkForUpdates: () => call<UpdateStatus>(window.originalMedia.checkForUpdates),
  installUpdate: () => call<{ started: boolean }>(window.originalMedia.installUpdate),
}
