export type PageId = 'download' | 'library' | 'tasks'

export interface ParsedSource {
  url: string
  aweme_id: string | null
  duplicate: boolean
  existing_collection_id: string | null
}

export interface Job {
  id: string
  source_url: string
  aweme_id: string | null
  title: string | null
  status: string
  progress: number
  downloaded_bytes: number
  total_bytes: number
  current_item: number
  total_items: number
  error: string | null
  collection_id: string | null
  created_at: string
  updated_at: string
  completed_at: string | null
}

export interface Asset {
  id: string
  filename: string
  kind: 'image' | 'video'
  mime_type: string
  extension: string
  size: number
  width: number | null
  height: number | null
  duration: number | null
  sequence: number
}

export interface Collection {
  id: string
  aweme_id: string | null
  source_url: string | null
  title: string
  author: string | null
  media_type: 'image' | 'video' | 'mixed'
  item_count: number
  imported: boolean
  source_created_at: string | null
  created_at: string
  updated_at: string
  cover_asset_id: string | null
  total_size: number
  assets: Asset[] | null
}

export interface LibraryResult {
  items: Collection[]
  total: number
}

export interface Settings {
  download_dir: string
  browser_profile_ready: boolean
  ffmpeg_ready: boolean
}

