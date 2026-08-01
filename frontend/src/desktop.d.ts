import type { DesktopBridge } from '../../src/shared/contracts'

declare global {
  interface Window {
    originalMedia: DesktopBridge
  }
}

export {}
