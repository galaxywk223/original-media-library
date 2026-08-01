const URL_PATTERN = /https?:\/\/[^\s，。；、！!？?）)】\]]+/g
const SUPPORTED_HOSTS = ['douyin.com', 'iesdouyin.com']
const AWEME_PATTERN = /\/(?:note|video|share\/video)\/(\d+)/

export function extractUrls(text: string): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const raw of text.match(URL_PATTERN) ?? []) {
    const url = raw.replace(/[.,;:]+$/, '')
    if (!seen.has(url)) {
      seen.add(url)
      result.push(url)
    }
  }
  return result
}

export function isSupportedSource(value: string): boolean {
  try {
    const host = new URL(value).hostname.toLowerCase()
    return SUPPORTED_HOSTS.some((allowed) => host === allowed || host.endsWith(`.${allowed}`))
  } catch {
    return false
  }
}

export function extractAwemeId(value: string): string | null {
  if (!isSupportedSource(value)) return null
  return new URL(value).pathname.match(AWEME_PATTERN)?.[1] ?? null
}

export async function normalizeSourceUrl(value: string): Promise<string> {
  let url = value.trim()
  if (!isSupportedSource(url)) return url
  if (!extractAwemeId(url)) {
    try {
      const response = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(20_000) })
      url = response.url
    } catch {
      // Short-link resolution is retried by the browser resolver.
    }
  }
  const id = extractAwemeId(url)
  return id ? `https://www.douyin.com/video/${id}` : url
}
