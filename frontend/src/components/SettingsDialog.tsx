import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, FolderOpen, RefreshCw, X } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api'
import type { Settings } from '../types'

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
              <CheckCircle2 size={18} className={settings?.ffmpeg_ready ? 'success' : 'muted'} />
              <span>视频缩略图</span>
              <strong>{settings?.ffmpeg_ready ? '可用' : 'FFmpeg 不可用'}</strong>
            </div>
          </div>
          {(save.error || choose.error || rescan.error) ? (
            <p className="inline-error">{(save.error || choose.error || rescan.error)?.message}</p>
          ) : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
