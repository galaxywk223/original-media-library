import { AlertCircle, CheckCircle2, CircleX, LoaderCircle, RefreshCw, Square } from 'lucide-react'
import type { Job } from '../types'

const statusLabels: Record<string, string> = {
  queued: '等待中',
  resolving: '正在解析',
  downloading: '正在下载',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
  interrupted: '已中断',
}

function formatBytes(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}

interface JobListProps {
  jobs: Job[]
  compact?: boolean
  onCancel: (id: string) => void
  onRetry: (id: string) => void
}

export function JobList({ jobs, compact = false, onCancel, onRetry }: JobListProps) {
  if (!jobs.length) {
    return (
      <div className="empty-state compact-empty">
        <LoaderCircle size={23} />
        <p>暂无下载任务</p>
      </div>
    )
  }

  return (
    <div className={`job-list ${compact ? 'is-compact' : ''}`}>
      {jobs.map((job) => {
        const active = ['queued', 'resolving', 'downloading'].includes(job.status)
        const failed = ['failed', 'interrupted', 'cancelled'].includes(job.status)
        const title = job.title || `抖音作品 ${job.aweme_id || ''}`.trim()
        return (
          <article className="job-row" key={job.id}>
            <div className={`job-icon status-${job.status}`}>
              {job.status === 'completed' ? <CheckCircle2 size={18} /> : null}
              {job.status === 'failed' || job.status === 'interrupted' ? <AlertCircle size={18} /> : null}
              {job.status === 'cancelled' ? <CircleX size={18} /> : null}
              {active ? <LoaderCircle size={18} className="spin" /> : null}
            </div>
            <div className="job-main">
              <div className="job-title-line">
                <strong title={title}>{title}</strong>
                <span>{statusLabels[job.status] || job.status}</span>
              </div>
              {active ? (
                <>
                  <div className="progress-track" aria-label={`进度 ${Math.round(job.progress * 100)}%`}>
                    <span style={{ width: `${Math.max(job.progress * 100, job.status === 'queued' ? 0 : 2)}%` }} />
                  </div>
                  <div className="job-meta">
                    <span>{Math.round(job.progress * 100)}%</span>
                    <span>
                      {job.total_items ? `${job.current_item}/${job.total_items} 个文件` : '准备媒体信息'}
                    </span>
                    {job.total_bytes ? <span>{formatBytes(job.downloaded_bytes)} / {formatBytes(job.total_bytes)}</span> : null}
                  </div>
                </>
              ) : null}
              {job.error ? <p className="job-error">{job.error}</p> : null}
            </div>
            <div className="job-actions">
              {active ? (
                <button className="icon-button" onClick={() => onCancel(job.id)} type="button" aria-label="取消任务">
                  <Square size={15} />
                </button>
              ) : null}
              {failed ? (
                <button className="icon-button" onClick={() => onRetry(job.id)} type="button" aria-label="重试任务">
                  <RefreshCw size={16} />
                </button>
              ) : null}
            </div>
          </article>
        )
      })}
    </div>
  )
}

