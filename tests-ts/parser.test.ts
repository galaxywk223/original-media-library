import { describe, expect, test } from 'vitest'
import { extractAwemeId, extractUrls, isSupportedSource } from '../src/main/parser'

describe('source parser', () => {
  test('deduplicates links and removes Chinese punctuation', () => {
    expect(extractUrls('作品 https://v.douyin.com/abc/，重复 https://v.douyin.com/abc/。')).toEqual([
      'https://v.douyin.com/abc/',
    ])
  })

  test('accepts only Douyin hosts and extracts work ids', () => {
    expect(isSupportedSource('https://www.douyin.com/video/123456')).toBe(true)
    expect(isSupportedSource('https://example.com/video/123456')).toBe(false)
    expect(extractAwemeId('https://www.douyin.com/video/123456')).toBe('123456')
  })
})
