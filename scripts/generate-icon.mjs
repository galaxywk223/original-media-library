import { app, BrowserWindow } from 'electron'
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

app.whenReady().then(async () => {
  let window
  try {
    const source = join(process.cwd(), 'assets', 'icon.svg')
    const output = join(process.cwd(), 'assets', 'icon.png')
    const svg = await readFile(source, 'utf8')
    const svgDataUrl = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`
    window = new BrowserWindow({
      width: 1,
      height: 1,
      show: false,
      skipTaskbar: true,
      webPreferences: { offscreen: true },
    })
    await window.loadURL('about:blank')
    const pngDataUrl = await window.webContents.executeJavaScript(`
      new Promise((resolve, reject) => {
        const image = new Image()
        image.onload = () => {
          const canvas = document.createElement('canvas')
          canvas.width = 512
          canvas.height = 512
          const context = canvas.getContext('2d')
          if (!context) {
            reject(new Error('Unable to create the icon canvas'))
            return
          }
          context.drawImage(image, 0, 0, 512, 512)
          resolve(canvas.toDataURL('image/png'))
        }
        image.onerror = () => reject(new Error('Unable to load the icon SVG'))
        image.src = ${JSON.stringify(svgDataUrl)}
      })
    `)
    const prefix = 'data:image/png;base64,'
    if (!pngDataUrl.startsWith(prefix)) throw new Error('Invalid PNG data URL')
    await writeFile(output, Buffer.from(pngDataUrl.slice(prefix.length), 'base64'))
    app.exit(0)
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    app.exit(1)
  } finally {
    window?.destroy()
  }
})
