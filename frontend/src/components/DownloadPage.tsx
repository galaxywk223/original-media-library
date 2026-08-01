import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Clipboard, Download, ExternalLink, Link2, LogIn, RefreshCw } from 'lucide-react'
import { useMemo, useState } from 'react'
import { api } from '../api'
import type { ParsedSource } from '../types'
import { JobList } from './JobList'

export function DownloadPage() {
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [sources, setSources] = useState<ParsedSource[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [message, setMessage] = useState('')
  const jobsQuery = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  const activeJobs = useMemo(
    () => (jobsQuery.data ?? []).filter((job) => ['queued', 'resolving', 'downloading'].includes(job.status)).slice(0, 5),
    [jobsQuery.data],
  )

  const parse = useMutation({
    mutationFn: () => api.parse(text),
    onSuccess: ({ sources: parsed }) => {
      setSources(parsed)
      setSelected(new Set(parsed.filter((source) => !source.duplicate).map((source) => source.url)))
      setMessage(parsed.length ? `已识别 ${parsed.length} 个作品` : '未识别到可用链接')
    },
  })
  const create = useMutation({
    mutationFn: ({ urls, force }: { urls: string[]; force: boolean }) => api.createJobs(urls, force),
    onSuccess: (results) => {
      const accepted = results.filter((result) => result.job).length
      setMessage(accepted ? `已加入 ${accepted} 个下载任务` : '所选作品已存在于媒体库')
      void queryClient.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
  const cancel = useMutation({
    mutationFn: api.cancelJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
  const retry = useMutation({
    mutationFn: api.retryJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
  const login = useMutation({
    mutationFn: api.openLogin,
    onSuccess: () => setMessage('已打开登录浏览器，完成登录后关闭窗口'),
  })

  const paste = async () => {
    try {
      const clipboard = await navigator.clipboard.readText()
      setText(clipboard)
      setMessage('已从剪贴板载入文本')
    } catch {
      setMessage('浏览器未授予剪贴板读取权限')
    }
  }

  const toggle = (url: string) => {
    setSelected((previous) => {
      const next = new Set(previous)
      if (next.has(url)) next.delete(url)
      else next.add(url)
      return next
    })
  }

  return (
    <div className="download-layout">
      <section className="download-workspace">
        <div className="section-heading">
          <div>
            <h2>粘贴分享内容</h2>
            <p>支持分享文案、短链接和多个抖音作品链接</p>
          </div>
          <button className="secondary-button" onClick={() => login.mutate()} type="button">
            <LogIn size={16} />打开登录浏览器
          </button>
        </div>

        <div className="paste-area">
          <textarea
            aria-label="分享内容"
            placeholder="粘贴抖音分享文本或链接，支持多条"
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
          <div className="paste-actions">
            <button className="secondary-button" onClick={paste} type="button">
              <Clipboard size={16} />粘贴
            </button>
            <button className="primary-button" disabled={!text.trim() || parse.isPending} onClick={() => parse.mutate()} type="button">
              <Link2 size={16} />{parse.isPending ? '解析中' : '解析链接'}
            </button>
          </div>
        </div>

        {message ? <p className="status-message">{message}</p> : null}
        {(parse.error || create.error || login.error) ? (
          <p className="inline-error">{(parse.error || create.error || login.error)?.message}</p>
        ) : null}

        {sources.length ? (
          <div className="parsed-section">
            <div className="parsed-toolbar">
              <div>
                <strong>解析结果</strong>
                <span>{selected.size} 项已选择</span>
              </div>
              <button className="primary-button" disabled={!selected.size || create.isPending} onClick={() => create.mutate({ urls: [...selected], force: false })} type="button">
                <Download size={16} />开始下载
              </button>
            </div>
            <div className="source-list">
              {sources.map((source) => (
                <button
                  className={`source-row ${selected.has(source.url) ? 'is-selected' : ''}`}
                  key={source.url}
                  onClick={() => toggle(source.url)}
                  type="button"
                >
                  <span className="selection-box">{selected.has(source.url) ? <Check size={14} /> : null}</span>
                  <span className="source-icon"><Link2 size={18} /></span>
                  <span className="source-info">
                    <strong>{source.aweme_id ? `抖音作品 ${source.aweme_id}` : '抖音分享链接'}</strong>
                    <small>{source.url}</small>
                  </span>
                  {source.duplicate ? <span className="duplicate-label">已在媒体库</span> : <ExternalLink size={15} />}
                </button>
              ))}
            </div>
            {sources.some((source) => source.duplicate) ? (
              <button
                className="text-button"
                onClick={() => {
                  const urls = sources.map((source) => source.url)
                  setSelected(new Set(urls))
                  create.mutate({ urls, force: true })
                }}
                type="button"
              >
                <RefreshCw size={14} />重新下载全部
              </button>
            ) : null}
          </div>
        ) : null}
      </section>

      <aside className="active-queue">
        <div className="rail-heading">
          <div>
            <h2>当前任务</h2>
            <span>{activeJobs.length} 个进行中</span>
          </div>
          <LoaderState active={jobsQuery.isFetching} />
        </div>
        <JobList
          compact
          jobs={activeJobs}
          onCancel={(id) => cancel.mutate(id)}
          onRetry={(id) => retry.mutate(id)}
        />
      </aside>
    </div>
  )
}

function LoaderState({ active }: { active: boolean }) {
  return <RefreshCw className={active ? 'spin muted' : 'muted'} size={16} aria-label={active ? '正在刷新' : '已同步'} />
}
