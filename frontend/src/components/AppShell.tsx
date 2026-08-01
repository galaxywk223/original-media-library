import * as Tooltip from '@radix-ui/react-tooltip'
import { Download, FolderCog, Images, ListChecks, Settings as SettingsIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import type { PageId, Settings } from '../types'

const pages: Array<{ id: PageId; label: string; icon: typeof Download }> = [
  { id: 'download', label: '下载', icon: Download },
  { id: 'library', label: '媒体库', icon: Images },
  { id: 'tasks', label: '任务', icon: ListChecks },
]

interface AppShellProps {
  page: PageId
  title: string
  settings?: Settings
  onPageChange: (page: PageId) => void
  onOpenSettings: () => void
  children: ReactNode
}

export function AppShell({
  page,
  title,
  settings,
  onPageChange,
  onOpenSettings,
  children,
}: AppShellProps) {
  return (
    <Tooltip.Provider delayDuration={500}>
      <div className="app-shell">
        <aside className="sidebar">
          <div className="brand" aria-label="原片库">
            <span className="brand-mark"><Images size={19} strokeWidth={2.2} /></span>
            <span>原片库</span>
          </div>
          <nav className="primary-nav" aria-label="主导航">
            {pages.map(({ id, label, icon: Icon }) => (
              <button
                className={`nav-item ${page === id ? 'is-active' : ''}`}
                key={id}
                onClick={() => onPageChange(id)}
                type="button"
              >
                <Icon size={18} />
                <span>{label}</span>
              </button>
            ))}
          </nav>
          <button className="nav-item settings-nav" onClick={onOpenSettings} type="button">
            <SettingsIcon size={18} />
            <span>设置</span>
          </button>
        </aside>

        <main className="main-area">
          <header className="topbar">
            <h1>{title}</h1>
            <div className="topbar-actions">
              <Tooltip.Root>
                <Tooltip.Trigger asChild>
                  <button className="path-indicator" onClick={onOpenSettings} type="button">
                    <FolderCog size={16} />
                    <span>{settings?.download_dir?.split(/[\\/]/).pop() || '下载目录'}</span>
                  </button>
                </Tooltip.Trigger>
                <Tooltip.Portal>
                  <Tooltip.Content className="tooltip" sideOffset={8}>
                    {settings?.download_dir || '查看下载目录'}
                  </Tooltip.Content>
                </Tooltip.Portal>
              </Tooltip.Root>
              <span className={`connection-state ${settings?.browser_profile_ready ? 'is-ready' : ''}`}>
                <span aria-hidden="true" />
                {settings?.browser_profile_ready ? '登录环境已配置' : '未配置登录环境'}
              </span>
            </div>
          </header>
          <div className="page-content">{children}</div>
        </main>

        <nav className="mobile-nav" aria-label="移动端导航">
          {pages.map(({ id, label, icon: Icon }) => (
            <button
              className={page === id ? 'is-active' : ''}
              key={id}
              onClick={() => onPageChange(id)}
              type="button"
            >
              <Icon size={20} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
      </div>
    </Tooltip.Provider>
  )
}

