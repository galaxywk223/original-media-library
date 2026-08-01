import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from './api'
import { AppShell } from './components/AppShell'
import { DownloadPage } from './components/DownloadPage'
import { LibraryPage } from './components/LibraryPage'
import { SettingsDialog } from './components/SettingsDialog'
import { TasksPage } from './components/TasksPage'
import type { PageId } from './types'

const pageTitles: Record<PageId, string> = {
  download: '下载',
  library: '媒体库',
  tasks: '任务',
}

function initialPage(): PageId {
  const value = window.location.hash.replace('#/', '')
  return value === 'library' || value === 'tasks' ? value : 'download'
}

export default function App() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState<PageId>(initialPage)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [setupDismissed, setSetupDismissed] = useState(false)
  const settings = useQuery({ queryKey: ['settings'], queryFn: api.settings })

  useEffect(() => {
    const syncPageFromHash = () => setPage(initialPage())
    window.addEventListener('hashchange', syncPageFromHash)
    return () => window.removeEventListener('hashchange', syncPageFromHash)
  }, [])

  useEffect(() => {
    const unsubscribe = window.originalMedia.onSnapshot(() => {
      void queryClient.invalidateQueries({ queryKey: ['jobs'] })
      void queryClient.invalidateQueries({ queryKey: ['library'] })
      void queryClient.invalidateQueries({ queryKey: ['settings'] })
    })
    return unsubscribe
  }, [queryClient])

  const changePage = (next: PageId) => {
    window.location.hash = `/${next}`
    setPage(next)
  }

  return (
    <AppShell
      page={page}
      title={pageTitles[page]}
      settings={settings.data}
      onPageChange={changePage}
      onOpenSettings={() => { setSetupDismissed(false); setSettingsOpen(true) }}
    >
      {page === 'download' ? <DownloadPage /> : null}
      {page === 'library' ? <LibraryPage /> : null}
      {page === 'tasks' ? <TasksPage /> : null}
      {settings.data ? (
        <SettingsDialog
          key={settings.data.download_dir}
          open={settingsOpen || (!settings.data.browser_profile_ready && !setupDismissed)}
          settings={settings.data}
          onOpenChange={(open) => { setSettingsOpen(open); if (!open) setSetupDismissed(true) }}
        />
      ) : null}
    </AppShell>
  )
}
