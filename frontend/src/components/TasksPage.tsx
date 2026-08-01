import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { api } from '../api'
import { JobList } from './JobList'

export function TasksPage() {
  const queryClient = useQueryClient()
  const jobs = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  const cancel = useMutation({ mutationFn: api.cancelJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }) })
  const retry = useMutation({ mutationFn: api.retryJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }) })

  return (
    <section className="tasks-page">
      <div className="section-heading tasks-heading">
        <div>
          <h2>下载任务</h2>
          <p>当前队列和最近任务</p>
        </div>
        <button className="icon-button bordered" onClick={() => jobs.refetch()} type="button" aria-label="刷新任务">
          <RefreshCw size={17} className={jobs.isFetching ? 'spin' : ''} />
        </button>
      </div>
      {jobs.error ? <p className="inline-error">{jobs.error.message}</p> : null}
      <JobList
        jobs={jobs.data ?? []}
        onCancel={(id) => cancel.mutate(id)}
        onRetry={(id) => retry.mutate(id)}
      />
    </section>
  )
}
