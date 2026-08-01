import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, ExternalLink, FolderOpen, Pencil, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api'

interface MediaViewerProps {
  collectionId: string | null
  onClose: () => void
}

export function MediaViewer({ collectionId, onClose }: MediaViewerProps) {
  const queryClient = useQueryClient()
  const [index, setIndex] = useState(0)
  const [renaming, setRenaming] = useState(false)
  const [title, setTitle] = useState('')
  const detail = useQuery({
    queryKey: ['collection', collectionId],
    queryFn: () => api.collection(collectionId!),
    enabled: Boolean(collectionId),
  })
  const action = useMutation({
    mutationFn: ({ name }: { name: 'open' | 'reveal' | 'trash' }) => api.libraryAction(name, [collectionId!]),
    onSuccess: (_, variables) => {
      if (variables.name === 'trash') {
        setIndex(0)
        setRenaming(false)
        setTitle('')
        onClose()
      }
      void queryClient.invalidateQueries({ queryKey: ['library'] })
    },
  })
  const rename = useMutation({
    mutationFn: () => api.renameCollection(collectionId!, title),
    onSuccess: () => {
      setRenaming(false)
      void queryClient.invalidateQueries({ queryKey: ['library'] })
      void queryClient.invalidateQueries({ queryKey: ['collection', collectionId] })
    },
  })

  const assets = detail.data?.assets ?? []
  const asset = assets[index]
  const close = () => {
    setIndex(0)
    setRenaming(false)
    setTitle('')
    onClose()
  }

  return (
    <Dialog.Root open={Boolean(collectionId)} onOpenChange={(open) => { if (!open) close() }}>
      <Dialog.Portal>
        <Dialog.Overlay className="viewer-overlay" />
        <Dialog.Content className="viewer-dialog">
          <Dialog.Title className="sr-only">{detail.data?.title || '媒体预览'}</Dialog.Title>
          <Dialog.Description className="sr-only">查看本地媒体文件</Dialog.Description>
          <div className="viewer-stage">
            {asset?.kind === 'video' ? (
              <video controls autoPlay src={`oml-media://asset/${asset.id}/content`} />
            ) : null}
            {asset?.kind === 'image' ? (
              <img src={`oml-media://asset/${asset.id}/content`} alt={detail.data?.title || asset.filename} />
            ) : null}
            {!asset && !detail.isLoading ? <p className="viewer-empty">媒体文件不存在</p> : null}
            {assets.length > 1 ? (
              <>
                <button
                  className="viewer-arrow previous"
                  onClick={() => setIndex((index - 1 + assets.length) % assets.length)}
                  type="button"
                  aria-label="上一项"
                ><ChevronLeft /></button>
                <button
                  className="viewer-arrow next"
                  onClick={() => setIndex((index + 1) % assets.length)}
                  type="button"
                  aria-label="下一项"
                ><ChevronRight /></button>
                <span className="viewer-count">{index + 1} / {assets.length}</span>
              </>
            ) : null}
          </div>
          <aside className="viewer-info">
            <div className="viewer-topline">
              <span className="media-kind">{detail.data?.media_type === 'video' ? '视频' : detail.data?.item_count ? `${detail.data.item_count} 张图片` : '媒体'}</span>
              <Dialog.Close className="icon-button" aria-label="关闭"><X size={20} /></Dialog.Close>
            </div>
            {renaming ? (
              <div className="rename-field">
                <input value={title} onChange={(event) => setTitle(event.target.value)} autoFocus />
                <button className="primary-button" onClick={() => rename.mutate()} type="button">保存</button>
              </div>
            ) : (
              <div className="viewer-title">
                <h2>{detail.data?.title}</h2>
                <button className="icon-button" onClick={() => { setTitle(detail.data?.title ?? ''); setRenaming(true) }} type="button" aria-label="重命名"><Pencil size={16} /></button>
              </div>
            )}
            {detail.data?.author ? <p className="viewer-author">{detail.data.author}</p> : null}
            {asset ? (
              <dl className="metadata-list">
                <div><dt>文件</dt><dd>{asset.filename}</dd></div>
                <div><dt>格式</dt><dd>{asset.extension.toUpperCase()}</dd></div>
                <div><dt>尺寸</dt><dd>{asset.width && asset.height ? `${asset.width} × ${asset.height}` : '未知'}</dd></div>
                <div><dt>大小</dt><dd>{formatBytes(asset.size)}</dd></div>
              </dl>
            ) : null}
            <div className="viewer-actions">
              <button className="secondary-button" onClick={() => action.mutate({ name: 'open' })} type="button"><ExternalLink size={16} />打开</button>
              <button className="secondary-button" onClick={() => action.mutate({ name: 'reveal' })} type="button"><FolderOpen size={16} />定位</button>
              <button className="danger-button" onClick={() => action.mutate({ name: 'trash' })} type="button"><Trash2 size={16} />移入回收站</button>
            </div>
            {(detail.error || action.error || rename.error) ? (
              <p className="inline-error">{(detail.error || action.error || rename.error)?.message}</p>
            ) : null}
          </aside>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function formatBytes(bytes: number) {
  const units = ['B', 'KB', 'MB', 'GB']
  if (!bytes) return '0 B'
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}
