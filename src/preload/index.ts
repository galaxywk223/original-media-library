import { contextBridge, ipcRenderer } from 'electron'
import type { DesktopBridge, LibraryQuery } from '../shared/contracts'

const bridge: DesktopBridge = {
  parse: (text) => ipcRenderer.invoke('sources:parse', { text }),
  createJobs: (urls, force = false) => ipcRenderer.invoke('jobs:create', { urls, force }),
  jobs: () => ipcRenderer.invoke('jobs:list'),
  cancelJob: (id) => ipcRenderer.invoke('jobs:cancel', { id }),
  retryJob: (id) => ipcRenderer.invoke('jobs:retry', { id }),
  library: (query: LibraryQuery) => ipcRenderer.invoke('library:list', query),
  collection: (id) => ipcRenderer.invoke('library:get', { id }),
  renameCollection: (id, title) => ipcRenderer.invoke('library:rename', { id, title }),
  libraryAction: (action, ids) => ipcRenderer.invoke('library:action', { action, ids }),
  settings: () => ipcRenderer.invoke('settings:get'),
  updateSettings: (downloadDir) => ipcRenderer.invoke('settings:update', { downloadDir }),
  selectDirectory: () => ipcRenderer.invoke('settings:select-directory'),
  openLogin: () => ipcRenderer.invoke('auth:open-login'),
  openDataDirectory: () => ipcRenderer.invoke('system:open-data-directory'),
  rescan: () => ipcRenderer.invoke('library:rescan'),
  onSnapshot: (callback) => {
    const listener = () => callback()
    ipcRenderer.on('events:snapshot', listener)
    return () => ipcRenderer.removeListener('events:snapshot', listener)
  },
}

contextBridge.exposeInMainWorld('originalMedia', bridge)
