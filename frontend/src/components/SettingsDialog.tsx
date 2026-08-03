import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, DownloadCloud, FolderCog, FolderOpen, LogIn, RefreshCw, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Settings, UpdateStatus } from '../types'

interface SettingsDialogProps {
  open: boolean
  settings?: Settings
  onOpenChange: (open: boolean) => void
}

export function SettingsDialog({ open, settings, onOpenChange }: SettingsDialogProps) {
  const queryClient = useQueryClient()
  const [downloadDir, setDownloadDir] = useState(settings?.download_dir ?? '')

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['settings'] })
    void queryClient.invalidateQueries({ queryKey: ['library'] })
  }
  const save = useMutation({ mutationFn: () => api.updateSettings(downloadDir), onSuccess: refresh })
  const choose = useMutation({
    mutationFn: api.selectDirectory,
    onSuccess: (value) => {
      setDownloadDir(value.download_dir)
      refresh()
    },
  })
  const rescan = useMutation({ mutationFn: api.rescan, onSuccess: refresh })
  const login = useMutation({ mutationFn: api.openLogin, onSuccess: refresh })
  const openData = useMutation({ mutationFn: api.openDataDirectory })
  const update = useQuery({ queryKey: ['update-status'], queryFn: api.getUpdateStatus })
  const checkUpdate = useMutation({
    mutationFn: api.checkForUpdates,
    onSuccess: (value) => queryClient.setQueryData(['update-status'], value),
  })
  const installUpdate = useMutation({ mutationFn: api.installUpdate })

  useEffect(() => window.originalMedia.onUpdateStatus((value) => {
    queryClient.setQueryData(['update-status'], value)
  }), [queryClient])

  const updateStatus = update.data
  const updateAction = updateStatus?.phase === 'downloaded' ? installUpdate : checkUpdate
  const updateDisabled = !updateStatus
    || updateStatus.phase === 'unsupported'
    || updateStatus.phase === 'checking'
    || updateStatus.phase === 'available'
    || updateStatus.phase === 'downloading'

  const runUpdateAction = () => {
    if (updateStatus?.phase === 'downloaded') installUpdate.mutate()
    else checkUpdate.mutate()
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog settings-dialog">
          <div className="dialog-heading">
            <div>
              <Dialog.Title>设置</Dialog.Title>
              <Dialog.Description>本机下载目录与媒体处理状态</Dialog.Description>
            </div>
            <Dialog.Close className="icon-button" aria-label="关闭"><X size={19} /></Dialog.Close>
          </div>

          <div className="settings-section">
            <label htmlFor="download-dir">下载目录</label>
            <div className="field-with-action">
              <input
                id="download-dir"
                value={downloadDir}
                onChange={(event) => setDownloadDir(event.target.value)}
              />
              <button className="icon-button bordered" onClick={() => choose.mutate()} type="button" aria-label="选择目录">
                <FolderOpen size={18} />
              </button>
            </div>
            <div className="settings-actions">
              <button className="secondary-button" onClick={() => rescan.mutate()} type="button">
                <RefreshCw size={16} />重新扫描
              </button>
              <button className="primary-button" disabled={!downloadDir || save.isPending} onClick={() => save.mutate()} type="button">
                保存
              </button>
            </div>
          </div>

          <div className="capability-list">
            <div>
              <CheckCircle2 size={18} className={settings?.browser_profile_ready ? 'success' : 'muted'} />
              <span>抖音登录环境</span>
              <strong>{settings?.browser_profile_ready ? '已配置' : '未配置'}</strong>
            </div>
            <div>
              <CheckCircle2 size={18} className={settings?.browser_ready ? 'success' : 'muted'} />
              <span>系统浏览器</span>
              <strong>{settings?.browser_ready ? '可用' : '未找到'}</strong>
            </div>
            <div>
              <FolderCog size={18} className="muted" />
              <span>应用数据</span>
              <button className="text-button" onClick={() => openData.mutate()} type="button">打开目录</button>
            </div>
            <div className="update-status-row">
              <DownloadCloud size={18} className={updateStatus?.phase === 'downloaded' ? 'success' : 'muted'} />
              <span className="update-copy">
                <span>应用更新</span>
                <small title={updateStatus?.message ?? undefined}>{updateStatusText(updateStatus, settings?.app_version)}</small>
                {updateStatus?.phase === 'downloading' ? (
                  <span className="update-progress" aria-label={`更新下载进度 ${updateStatus.download_percent ?? 0}%`}>
                    <span style={{ width: `${updateStatus.download_percent ?? 0}%` }} />
                  </span>
                ) : null}
              </span>
              <button
                className="text-button update-action"
                onClick={runUpdateAction}
                disabled={updateDisabled || updateAction.isPending}
                type="button"
              >
                {updateActionText(updateStatus)}
              </button>
            </div>
          </div>
          <div className="settings-footer-actions">
            <button className="secondary-button" onClick={() => login.mutate()} disabled={!settings?.browser_ready || login.isPending} type="button">
              <LogIn size={16} />{settings?.browser_profile_ready ? '重新登录' : '配置登录'}
            </button>
          </div>
          {(save.error || choose.error || rescan.error || login.error || openData.error || checkUpdate.error || installUpdate.error) ? (
            <p className="inline-error">{(save.error || choose.error || rescan.error || login.error || openData.error || checkUpdate.error || installUpdate.error)?.message}</p>
          ) : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function updateStatusText(status: UpdateStatus | undefined, fallbackVersion: string | undefined): string {
  if (!status) return '正在读取更新状态'
  switch (status.phase) {
    case 'unsupported': return status.message ?? '仅正式安装版支持应用内更新'
    case 'idle': return `当前版本 v${status.current_version}`
    case 'checking': return '正在检查更新'
    case 'available': return `发现 v${status.available_version}，正在准备下载`
    case 'downloading': return `正在下载 v${status.available_version} · ${status.download_percent ?? 0}%`
    case 'downloaded': return `v${status.available_version} 已准备就绪`
    case 'up-to-date': return `已是最新版本 · v${status.current_version}`
    case 'error': return status.message ? `检查失败：${status.message}` : '检查更新失败，可重试'
    default: return `当前版本 v${fallbackVersion ?? status.current_version}`
  }
}

function updateActionText(status: UpdateStatus | undefined): string {
  if (!status) return '读取中'
  switch (status.phase) {
    case 'unsupported': return '不可用'
    case 'checking': return '检查中'
    case 'available': return '准备下载'
    case 'downloading': return `下载中 ${status.download_percent ?? 0}%`
    case 'downloaded': return '重启更新'
    case 'error': return '重试'
    default: return '检查更新'
  }
}
