import { app, BrowserWindow } from 'electron'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

app.whenReady().then(async () => {
  try {
    const source = join(process.cwd(), 'assets', 'icon.svg')
    const output = join(process.cwd(), 'assets', 'icon.png')
    const window = new BrowserWindow({
      width: 512,
      height: 512,
      show: false,
      frame: false,
      skipTaskbar: true,
    })
    await window.loadFile(source)
    window.showInactive()
    await new Promise((resolve) => setTimeout(resolve, 100))
    const image = await window.webContents.capturePage()
    await writeFile(output, image.toPNG())
    app.exit(0)
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    app.exit(1)
  }
})
